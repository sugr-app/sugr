package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The manifest size cap, which has to bound what's read - not be checked after reading everything. */
class UpdateDownloaderTest {

    @Test
    void readsABodyUpToTheCap() throws IOException {
        String body = "x".repeat(UpdateDownloader.MAX_MANIFEST_BYTES);
        assertEquals(body, UpdateDownloader.readCapped(
                new ByteArrayInputStream(body.getBytes()), UpdateDownloader.MAX_MANIFEST_BYTES, "test"));
    }

    @Test
    void stopsReadingOneByteAfterTheCap() {
        // An endless stream: if the cap only applied after buffering, this
        // would never return.
        InputStream endless = new InputStream() {
            long served;

            @Override
            public int read() {
                served++;
                if (served > 2L * UpdateDownloader.MAX_MANIFEST_BYTES) {
                    throw new AssertionError("read far past the cap");
                }
                return 'x';
            }
        };
        assertThrows(IOException.class,
                () -> UpdateDownloader.readCapped(endless, UpdateDownloader.MAX_MANIFEST_BYTES, "test"));
    }
}
