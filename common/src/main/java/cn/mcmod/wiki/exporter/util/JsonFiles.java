package cn.mcmod.wiki.exporter.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();

    private JsonFiles() {
    }

    public static void write(Path file, JsonElement json) throws IOException {
        writeAtomically(file, writer -> GSON.toJson(json, writer));
    }

    public static void writeSerialized(Path file, String json) throws IOException {
        writeAtomically(file, writer -> writer.write(json));
    }

    private static void writeAtomically(Path file, WriteContent content) throws IOException {
        ExportFiles.replace(file, temporary -> {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                content.write(writer);
            }
        });
    }

    @FunctionalInterface
    private interface WriteContent {
        void write(BufferedWriter writer) throws IOException;
    }
}
