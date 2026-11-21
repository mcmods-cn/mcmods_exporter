package cn.mcmod.wiki.exporter.task;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        report.error("resources", "example:icon", new NoSuchFileException("/private-fixture/missing.png"));
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
}
