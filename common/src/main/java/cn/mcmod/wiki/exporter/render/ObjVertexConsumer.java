package cn.mcmod.wiki.exporter.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ObjVertexConsumer implements VertexConsumer {
    private final List<Vertex> vertices = new ArrayList<>();
    private double x;
    private double y;
    private double z;
    private float u;
    private float v;
    private float nx;
    private float ny;
    private float nz;

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.u = u;
        this.v = 1.0F - v;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
        return this;
    }

    @Override
    public void endVertex() {
        vertices.add(new Vertex(x, y, z, u, v, nx, ny, nz));
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
    }

    @Override
    public void unsetDefaultColor() {
    }

    public int vertexCount() {
        return vertices.size();
    }

    public int faceCount() {
        return vertices.size() / 4;
    }

    public void write(Path output, String objectName) throws Exception {
        Files.createDirectories(output.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("# mcmods_exporter static base model snapshot\n");
            writer.write("o " + objectName.replace(':', '_').replace('/', '_') + "\n");
            for (Vertex vertex : vertices) {
                writer.write("v " + vertex.x + " " + vertex.y + " " + vertex.z + "\n");
            }
            for (Vertex vertex : vertices) {
                writer.write("vt " + vertex.u + " " + vertex.v + "\n");
            }
            for (Vertex vertex : vertices) {
                writer.write("vn " + vertex.nx + " " + vertex.ny + " " + vertex.nz + "\n");
            }
            int completeVertices = faceCount() * 4;
            for (int base = 0; base < completeVertices; base += 4) {
                writer.write("f");
                for (int offset = 0; offset < 4; offset++) {
                    int index = base + offset + 1;
                    writer.write(" " + index + "/" + index + "/" + index);
                }
                writer.write("\n");
            }
        }
    }

    private record Vertex(double x, double y, double z, float u, float v, float nx, float ny, float nz) {
    }
}
