package cn.mcmod.wiki.exporter.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    /** A website-facing quad mesh with explicit per-face positions, normals and UVs. */
    public JsonObject meshJson(String coordinateSpace, String uvSpace) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-uv-quad-mesh/v1");
        root.addProperty("coordinate_space", coordinateSpace);
        root.addProperty("uv_space", uvSpace);
        root.addProperty("uv_origin", "bottom_left");
        root.addProperty("vertex_count", vertexCount());
        root.addProperty("quad_count", faceCount());
        root.addProperty("trailing_vertex_count", vertices.size() % 4);
        JsonArray faces = new JsonArray();
        int completeVertices = faceCount() * 4;
        for (int base = 0; base < completeVertices; base += 4) {
            JsonObject face = new JsonObject();
            face.addProperty("index", base / 4);
            face.addProperty("direction", direction(base));
            JsonArray faceVertices = new JsonArray();
            float minU = Float.POSITIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            for (int offset = 0; offset < 4; offset++) {
                Vertex vertex = vertices.get(base + offset);
                JsonObject json = new JsonObject();
                json.add("position", vector(vertex.x, vertex.y, vertex.z));
                json.add("uv", vector(vertex.u, vertex.v));
                json.add("normal", vector(vertex.nx, vertex.ny, vertex.nz));
                faceVertices.add(json);
                minU = Math.min(minU, vertex.u);
                minV = Math.min(minV, vertex.v);
                maxU = Math.max(maxU, vertex.u);
                maxV = Math.max(maxV, vertex.v);
            }
            face.add("vertices", faceVertices);
            JsonObject uvBounds = new JsonObject();
            uvBounds.addProperty("u_min", minU);
            uvBounds.addProperty("v_min", minV);
            uvBounds.addProperty("u_max", maxU);
            uvBounds.addProperty("v_max", maxV);
            face.add("uv_bounds", uvBounds);
            faces.add(face);
        }
        root.add("faces", faces);
        return root;
    }

    private String direction(int base) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (int offset = 0; offset < 4; offset++) {
            Vertex vertex = vertices.get(base + offset);
            x += vertex.nx;
            y += vertex.ny;
            z += vertex.nz;
        }
        double absoluteX = Math.abs(x);
        double absoluteY = Math.abs(y);
        double absoluteZ = Math.abs(z);
        if (absoluteY >= absoluteX && absoluteY >= absoluteZ) {
            return y >= 0.0D ? "up" : "down";
        }
        if (absoluteX >= absoluteZ) {
            return x >= 0.0D ? "east" : "west";
        }
        return z >= 0.0D ? "south" : "north";
    }

    private static JsonArray vector(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    private record Vertex(double x, double y, double z, float u, float v, float nx, float ny, float nz) {
    }
}
