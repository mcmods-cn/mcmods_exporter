package cn.mcmod.wiki.exporter.task;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExportReportTest {
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
