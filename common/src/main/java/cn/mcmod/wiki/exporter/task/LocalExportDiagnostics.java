package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.McModsExporter;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Writes sampled stacks to a user-only file outside the exported directory. */
final class LocalExportDiagnostics {
    static final int MAX_SAMPLES = 16;
    static final int MAX_DETAIL_CHARS = 32 * 1024;

    private final Path directory;
    private final Path staging;
    private final String job;
    private BufferedWriter output;
    private boolean unavailable;
    private int samples;

    LocalExportDiagnostics(Path directory, Path staging, String job) {
        this.directory = directory;
        this.staging = staging;
        this.job = job;
    }

    void sample(String stage, String subject, Throwable failure, boolean packageFailure) {
        if (unavailable || samples >= MAX_SAMPLES - (packageFailure ? 0 : 1)) {
            return;
        }
        try {
            if (output == null) {
                Files.createDirectories(directory);
                if (Files.exists(staging) && directory.toRealPath().startsWith(staging.toRealPath())) {
                    throw new IOException("Diagnostic directory is inside the export");
                }
                Path path = directory.resolve("mcmods-export-" + UUID.randomUUID() + ".log");
                var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    privatePermissions(directory));
                output = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
                McModsExporter.LOGGER.warn("Export {} has private diagnostics in logs/{}", job, path.getFileName());
            }
            JsonObject header = new JsonObject();
            header.addProperty("job", job);
            header.addProperty("stage", stage);
            header.addProperty("object", subject.length() <= 512 ? subject : subject.substring(0, 512) + "…");
            output.write(header.toString());
            output.newLine();
            DetailWriter detail = new DetailWriter(output);
            PrintWriter trace = new PrintWriter(detail);
            failure.printStackTrace(trace);
            if (trace.checkError()) {
                throw new IOException("Could not write exception");
            }
            if (detail.truncated) {
                output.write("[exception exceeded local detail limit]");
                output.newLine();
            }
            output.flush();
            samples++;
        } catch (IOException | RuntimeException diagnosticFailure) {
            unavailable = true;
            McModsExporter.LOGGER.warn("Export {} could not write its private diagnostic log ({})",
                job, diagnosticFailure.getClass().getSimpleName());
        }
    }

    void close(long total) {
        if (output != null) {
            try (BufferedWriter writer = output) {
                if (total > samples) {
                    writer.write("Additional or repeated errors without a stack sample: " + (total - samples));
                    writer.newLine();
                }
            } catch (IOException failure) {
                McModsExporter.LOGGER.warn("Export {} could not finish its private diagnostic log", job);
            } finally {
                output = null;
            }
        }
    }

    private static FileAttribute<?> privatePermissions(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        if (Files.getFileAttributeView(directory, AclFileAttributeView.class) != null) {
            var user = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() {
                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return List.of(entry);
                }
            };
        }
        throw new IOException("File system does not support user-only permissions");
    }

    private static final class DetailWriter extends Writer {
        private final Writer output;
        private int remaining = MAX_DETAIL_CHARS;
        private boolean truncated;

        private DetailWriter(Writer output) {
            this.output = output;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            output.write(chars, offset, allowance(length));
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            output.write(text, offset, allowance(length));
        }

        private int allowance(int length) {
            int count = Math.min(length, remaining);
            remaining -= count;
            truncated |= count < length;
            return count;
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
