package at.rtr.rmbt.service.export.opendata;


import at.rtr.rmbt.dto.OpenTestExportResult;
import at.rtr.rmbt.mapper.OpenTestMapper;
import at.rtr.rmbt.repository.OpenTestExportRepository;
import at.rtr.rmbt.response.OpenTestExportDto;
import lombok.RequiredArgsConstructor;
import org.apache.poi.util.IOUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public abstract class AbstractExportService {

    private final OpenTestExportRepository openTestExportRepository;
    private final OpenTestMapper openTestMapper;
    private static long cacheThresholdMs;

    public ResponseEntity<Object> exportOpenData(Integer year, Integer month, Integer hour) {
        //Before doing anything => check if a cached file already exists and is new enough


        //allow filtering by month/year
        boolean hoursExport = false;
        boolean dateExport = false;

        if (Objects.nonNull(hour)) { // export by hours
            if (hour <= 7 * 24 && hour >= 1) {  //limit to 1 week (avoid DoS)
                hoursExport = true;
            }
        } else if (Objects.nonNull(year) && Objects.nonNull(month)) {  // export by month/year
            if (year < 2099 && month > 0 && month <= 12 && year > 2000) {
                dateExport = true;
            }
        }

        String property = System.getProperty("java.io.tmpdir");
        final String filename;
        final List<OpenTestExportResult> exportResults;

        if (hoursExport) {
            filename = getFileNameHours().replace("%HOURS%", String.format("%03d", hour));
            cacheThresholdMs = 5 * 60 * 1000; //5 minutes
            exportResults = openTestExportRepository.getOpenTestExportHour(hour);
        } else if (dateExport) {
            filename = getFileName().replace("%YEAR%", Integer.toString(year)).replace("%MONTH%", String.format("%02d", month));
            cacheThresholdMs = 23 * 60 * 60 * 1000; //23 hours
            exportResults = openTestExportRepository.getOpenTestExportMonth(year, month);
        } else {
            filename = getFileNameCurrent();
            cacheThresholdMs = 3 * 60 * 60 * 1000; //3 hours
            exportResults = openTestExportRepository.getOpenTestExportLast31Days();
        }

        MediaType mediaType = getMediaType();

        ResponseEntity.BodyBuilder responseEntity = ResponseEntity.ok()
                .contentType(mediaType);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            final File cachedFile = new File(property + File.separator + filename);
            final File generatingFile = new File(property + File.separator + filename + "_tmp");
            if (cachedFile.exists()) {
                //check if file has been recently created OR a file is currently being created
                if (((cachedFile.lastModified() + cacheThresholdMs) > (new Date()).getTime()) ||
                        (generatingFile.exists() && (generatingFile.lastModified() + cacheThresholdMs) > (new Date()).getTime())) {

                    //if so, return the cached file instead of a cost-intensive new one
                    InputStream is = new FileInputStream(cachedFile);
                    IOUtils.copy(is, out);

                    return responseEntity
                            .body(out.toByteArray());

                }
            }

            final List<OpenTestExportDto> results = exportResults.stream()
                    .map(openTestMapper::openTestExportResultToOpenTestExportDto)
                    .collect(Collectors.toList());
            writeNewFile(out, results, filename);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }

        return responseEntity.body(out.toByteArray());
    }

    protected void writeNewFile(OutputStream out, List<OpenTestExportDto> results, String fileName) throws IOException {
        //cache in file => create temporary temporary file (to
        // handle errors while fulfilling a request)
        String property = System.getProperty("java.io.tmpdir");
        final File cachedFile = new File(property + File.separator + fileName + "_tmp");
        OutputStream outf = new FileOutputStream(cachedFile);

        //custom logic
        writeCustomLogic(results, outf, fileName);
        //end custom logic

        //if we reach this code, the data is now cached in a temporary tmp-file
        //so, rename the file for "production use2
        //concurrency issues should be solved by the operating system
        File newCacheFile = new File(property + File.separator + fileName);
        Files.move(cachedFile.toPath(), newCacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        FileInputStream fis = new FileInputStream(newCacheFile);
        IOUtils.copy(fis, out);
        fis.close();
        out.close();
    }

    protected abstract void writeCustomLogic(List<OpenTestExportDto> results, OutputStream out, String fileName) throws IOException;

    protected abstract MediaType getMediaType();

    protected abstract String getFileNameHours();

    protected abstract String getFileName();

    protected abstract String getFileNameCurrent();
}
