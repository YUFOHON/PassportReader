package com.example.reader.readers.eep;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Stream utility methods
 */
public final class StreamUtils {

    private StreamUtils() {}

    public static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;

        while ((bytesRead = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }

        return buffer.toByteArray();
    }
}