package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.util.ExportArchives;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportReportTest {
    @Test
    void packageFailureReplacesProvisionalCompletionAndKeepsDiagnosticsPrivate() {
        ExportReport report = new ExportReport();
        report.finish(false);
        report.packageFailed(new NoSuchFileException("/private-fixture/export.zip"));
        JsonObject result = new JsonObject();
        report.writeStageResults(result);
        assertEquals("failed", result.get("status").getAsString());
        assertEquals(1, result.get("error_count").getAsInt());
        assertEquals("zip", result.getAsJsonArray("errors").get(0).getAsJsonObject().get("subject").getAsString());
        assertFalse(result.toString().contains("private-fixture"));
    }

    @Test
    void fatalVmFailuresEscapeObjectRecovery() {
        ExportReport report = new ExportReport();
        assertThrows(InternalError.class, () -> report.error("resources", "example:icon", new InternalError("test")));
    }

    @Test
    void reportsObjectAndFailureKindWithoutEmbeddingPrivateExceptionData() {
        ExportReport report = new ExportReport();
        report.stageStarted("resources");
        report.error("resources", new ResourceLocation("example", "icon"), new NoSuchFileException("/private-fixture/missing.png"));
        report.stageFinished("resources");
        JsonObject result = new JsonObject();
        report.writeStageResults(result);

        assertEquals(1, result.get("error_count").getAsInt());
        assertEquals("complete", result.getAsJsonArray("stages").get(0).getAsJsonObject().get("status").getAsString());
        JsonObject error = result.getAsJsonArray("errors").get(0).getAsJsonObject();
        assertEquals("resources", error.get("stage").getAsString());
        assertEquals("example:icon", error.get("subject").getAsString());
        assertEquals("NoSuchFileException", error.get("message").getAsString());
        assertFalse(result.toString().contains("private-fixture"));
    }

    @Test
    void privateStackIsSampledOnceAndExcludedFromTheUpload(@TempDir Path game) throws Exception {
        Path logs = game.resolve("logs");
        ExportReport report = new ExportReport(logs, game.resolve("staging"), "example.zip");
        IOException failure = new IOException("private exception", new NoSuchFileException("/private-fixture/file"));
        failure.addSuppressed(new IOException("private suppressed detail"));
        for (int i = 0; i < 100; i++) {
            report.error("resources", "https://example.invalid/?signature=private-fixture", failure);
        }
        report.closeDiagnostics();
        Path log;
        try (var files = Files.list(logs)) {
            log = files.findFirst().orElseThrow();
        }
        if (Files.getFileAttributeView(log, PosixFileAttributeView.class) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
        }
        String text = Files.readString(log);
        assertTrue(text.contains("\"job\":\"example.zip\""));
        assertTrue(text.contains("\"stage\":\"resources\""));
        assertTrue(text.contains("signature=private-fixture"));
        assertTrue(text.contains("Caused by: java.nio.file.NoSuchFileException: /private-fixture/file"));
        assertTrue(text.contains("Suppressed: java.io.IOException: private suppressed detail"));
        assertEquals(2, text.split("java.io.IOException: private exception", -1).length);
        assertTrue(text.contains("without a stack sample: 99"));

        JsonObject result = new JsonObject();
        report.writeStageResults(result);
        assertEquals(100, result.get("error_count").getAsInt());
        assertEquals(99, result.get("errors_omitted").getAsInt());
        assertEquals("object", result.getAsJsonArray("errors").get(0).getAsJsonObject().get("subject").getAsString());
        Path staging = Files.createDirectory(game.resolve("staging"));
        Path zip = game.resolve("example.zip");
        try (var prepared = ExportArchives.prepare(staging, zip, result.toString(), () -> false)) {
            prepared.publish();
        }
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            assertEquals(1, archive.size());
            try (var manifest = archive.getInputStream(archive.getEntry("manifest.json"))) {
                String contents = new String(manifest.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(contents.contains("private"));
                assertFalse(contents.contains("signature"));
            }
        }
    }

    @Test
    void errorStormKeepsTotalsAndAReservedPackageFailure(@TempDir Path game) throws IOException {
        ExportReport report = new ExportReport(game, game.resolve("staging"), "busy.zip");
        String longMessage = "x".repeat(LocalExportDiagnostics.MAX_DETAIL_CHARS + 100);
        for (int index = 0; index < 180; index++) {
            report.error("resources", new ResourceLocation("example", "id_" + index), new IOException(longMessage));
        }
        report.packageFailed(new IOException("private terminal cause"));
        report.closeDiagnostics();
        JsonObject result = new JsonObject();
        report.writeStageResults(result);
        assertEquals(181, result.get("error_count").getAsInt());
        assertEquals(ExportReport.MAX_ERROR_SAMPLES, result.getAsJsonArray("errors").size());
        assertEquals(181 - ExportReport.MAX_ERROR_SAMPLES, result.get("errors_omitted").getAsInt());
        assertEquals("zip", result.getAsJsonArray("errors").get(ExportReport.MAX_ERROR_SAMPLES - 1)
            .getAsJsonObject().get("subject").getAsString());
        try (var files = Files.list(game)) {
            String text = Files.readString(files.findFirst().orElseThrow());
            assertTrue(text.contains("[exception exceeded local detail limit]"));
            assertTrue(text.contains("private terminal cause"));
            assertTrue(text.contains("without a stack sample: " + (181 - LocalExportDiagnostics.MAX_SAMPLES)));
        }
    }

    @Test
    void cleanJobsCreateNoLogAndUnwritableLogsStillAllowReporting(@TempDir Path game) throws IOException {
        Path logs = game.resolve("logs");
        ExportReport report = new ExportReport(logs, game.resolve("staging"), "example.zip");
        report.closeDiagnostics();
        assertFalse(Files.exists(logs));
        Files.writeString(logs, "test-only file blocking directory creation");
        report.error("resources", new ResourceLocation("example", "../hidden"), new IOException("private path"));
        report.closeDiagnostics();
        JsonObject result = new JsonObject();
        report.writeStageResults(result);
        assertEquals(1, result.get("error_count").getAsInt());
        assertFalse(result.toString().contains("hidden"));
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void aDiagnosticDirectoryInsideTheStagingTreeDoesNotReceiveStacks(@TempDir Path staging) throws IOException {
        Path logs = Files.createDirectory(staging.resolve("logs"));
        ExportReport report = new ExportReport(logs, staging, "example.zip");
        report.error("resources", "local text", new IOException("private detail"));
        report.closeDiagnostics();
        try (var files = Files.list(logs)) {
            assertEquals(0, files.count());
        }
    }
}
