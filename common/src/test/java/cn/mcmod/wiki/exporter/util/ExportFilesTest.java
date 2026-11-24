package cn.mcmod.wiki.exporter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportFilesTest {
    @Test
    void failedResourceClosePreservesBytesWrittenByAnEarlierStage(@TempDir Path directory) throws IOException {
        Path texture = Files.writeString(directory.resolve("shared.png"), "original texture");

        assertThrows(IOException.class, () -> ExportFiles.copy(texture,
            () -> new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                @Override
                public void close() throws IOException {
                    throw new IOException("pack close failed");
                }
            }));

        assertEquals("original texture", Files.readString(texture));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }
}
