package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HttpResponseMediaStreamingTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final String NO_CACHE = "Cache-Control: no-cache\r\n";
    private static final String CACHED = "Cache-Control: private, max-age=86400, immutable\r\n"
            + "ETag: \"test\"\r\n";

    @Test
    public void fullMediaAndVideoPreserveHeadersAndBytesAcrossChunks() throws Exception {
        byte[] data = sampleData();
        File file = writeFile(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpResponse.sendMediaFile(out, file, "audio/wav");
        assertResponse(out, fullHeaders("audio/wav", data.length, NO_CACHE), data);
        out.reset();
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", "items=0-5");
        assertResponse(out, fullHeaders("audio/wav", data.length, NO_CACHE), data);
        out.reset();
        HttpResponse.sendVideo(out, file);
        assertResponse(out, fullHeaders("video/mp4", data.length, NO_CACHE), data);
        out.reset();
        HttpResponse.sendVideo(out, file, "\"test\"");
        assertResponse(out, fullHeaders("video/mp4", data.length, CACHED), data);
    }

    @Test
    public void rangesPreserveOffsetsLengthsAndCachePolicy() throws Exception {
        byte[] data = sampleData();
        File file = writeFile(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int start = 13, end = 65550;
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", "bytes=" + start + "-" + end);
        assertResponse(out, rangeHeaders("audio/wav", data.length, start, end, NO_CACHE),
                Arrays.copyOfRange(data, start, end + 1));
        out.reset();
        HttpResponse.sendVideoRange(out, file, start, end, "\"test\"");
        assertResponse(out, rangeHeaders("video/mp4", data.length, start, end, CACHED),
                Arrays.copyOfRange(data, start, end + 1));
        out.reset();
        HttpResponse.sendVideoRange(out, file, data.length - 3, -1);
        assertResponse(out, rangeHeaders("video/mp4", data.length, data.length - 3, data.length - 1, NO_CACHE),
                Arrays.copyOfRange(data, data.length - 3, data.length));
        out.reset();
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", "bytes=-3");
        assertResponse(out, rangeHeaders("audio/wav", data.length, data.length - 3, data.length - 1, NO_CACHE),
                Arrays.copyOfRange(data, data.length - 3, data.length));
    }

    @Test
    public void overlappingRequestsKeepIndependentPositionsAndExactRangeEnds() throws Exception {
        byte[] data = sampleData();
        File file = writeFile(data);
        int[][] ranges = {
                {0, 0}, {1, 65536}, {65535, 65537}, {65536, 131071},
                {13, 131072}, {131072, data.length - 1}, {data.length - 1, data.length - 1}
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Each HTTP request must own its position even when reading the same file.
            List<Future<?>> pending = new ArrayList<>();
            for (int[] range : ranges) {
                pending.add(pool.submit(() -> {
                    int start = range[0], end = range[1];
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    HttpResponse.sendVideoRange(out, file, start, end, "\"test\"");
                    assertResponse(out, rangeHeaders("video/mp4", data.length, start, end, CACHED),
                            Arrays.copyOfRange(data, start, end + 1));
                    return null;
                }));
            }
            for (Future<?> request : pending) request.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void failedRangeWriteDoesNotAffectNextRequest() throws Exception {
        File first = writeFile(sampleData());
        OutputStream broken = new OutputStream() {
            private boolean headersWritten;

            @Override public void write(int value) throws IOException {
                throw new IOException("disconnected");
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                // Allow the headers, reject the first body chunk.
                if (headersWritten) throw new IOException("disconnected");
                headersWritten = true;
            }
        };
        assertThrows(IOException.class,
                () -> HttpResponse.sendVideoRange(broken, first, 0, 65536));

        byte[] data = {10, 20, 30, 40};
        File second = writeFile(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendVideoRange(out, second, 1, 2);
        assertResponse(out, rangeHeaders("video/mp4", data.length, 1, 2, NO_CACHE),
                new byte[]{20, 30});
    }

    @Test
    public void zeroLengthFilesAndRangeErrorsRetainExistingResponses() throws Exception {
        byte[] empty = new byte[0];
        File file = writeFile(empty);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendVideo(out, file);
        assertResponse(out, fullHeaders("video/mp4", 0, NO_CACHE), empty);
        out.reset();
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", null);
        assertResponse(out, fullHeaders("audio/wav", 0, NO_CACHE), empty);
        out.reset();
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", "bytes=0-");
        byte[] error = "Range Not Satisfiable".getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Range: bytes */0\r\nContent-Length: " + error.length + "\r\nConnection: close\r\n\r\n";
        assertResponse(out, headers, error);
        out.reset();
        HttpResponse.sendVideoRange(out, file, 0, 3);
        assertResponse(out, headers, error);
        out.reset();
        HttpResponse.sendMediaFileRanged(out, file, "audio/wav", "bytes=broken");
        assertPlainError(out, 400, "Invalid Range header");
    }

    @Test
    public void imageStreamingRetainsItsOwnHeadersAndMissingFileMessage() throws Exception {
        byte[] data = sampleData();
        File file = writeFile(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendImage(out, file, "image/jpeg", HttpResponse.NO_STORE);
        assertResponse(out, "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + data.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", data);
        out.reset();
        File missing = new File(temp.getRoot(), "missing");
        HttpResponse.sendImage(out, missing, "image/jpeg");
        assertPlainError(out, 404, "Image not found");
        out.reset();
        HttpResponse.sendVideo(out, missing);
        assertPlainError(out, 404, "File not found");
    }

    private File writeFile(byte[] data) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), data);
        return file;
    }

    private static byte[] sampleData() {
        byte[] data = new byte[65536 * 2 + 7];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 256);
        return data;
    }

    private static String fullHeaders(String type, int length, String cache) {
        return "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + length
                + "\r\nAccept-Ranges: bytes\r\n" + cache + "Connection: close\r\n\r\n";
    }

    private static String rangeHeaders(String type, int length, int start, int end, String cache) {
        return "HTTP/1.1 206 Partial Content\r\nContent-Type: " + type + "\r\nContent-Length: " + (end - start + 1)
                + "\r\nContent-Range: bytes " + start + "-" + end + "/" + length
                + "\r\nAccept-Ranges: bytes\r\n" + cache + "Connection: close\r\n\r\n";
    }

    private static void assertPlainError(ByteArrayOutputStream out, int code, String message) throws Exception {
        assertResponse(out, "HTTP/1.1 " + code + " " + message
                + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n",
                message.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertResponse(ByteArrayOutputStream out, String headers, byte[] body) throws Exception {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(headers.getBytes(StandardCharsets.UTF_8));
        expected.write(body);
        assertArrayEquals(expected.toByteArray(), out.toByteArray());
    }
}
