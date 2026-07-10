package cn.mcmod.wiki.exporter.task;

public abstract class OnceStage implements ExportStage {
    private boolean complete;

    @Override
    public final StageResult step(ExportContext context) throws Exception {
        if (!complete) {
            run(context);
            complete = true;
        }
        return StageResult.DONE;
    }

    protected abstract void run(ExportContext context) throws Exception;
}
