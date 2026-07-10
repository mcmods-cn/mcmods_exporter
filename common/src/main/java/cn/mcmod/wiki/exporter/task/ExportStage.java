package cn.mcmod.wiki.exporter.task;

public interface ExportStage {
    String id();

    default void start(ExportContext context) throws Exception {
    }

    StageResult step(ExportContext context) throws Exception;

    default String progress() {
        return "";
    }
}
