package cn.mcmod.wiki.exporter.fabric;

import cn.mcmod.wiki.exporter.ExportBootstrap;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportCoordinator;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;

public final class McWikiExporterFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ExportBootstrap::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("mcwiki_export")
                .executes(context -> start(context.getSource()::sendFeedback, "all", ExportProfile.ALL))
                .then(ClientCommandManager.literal("status").executes(context -> {
                    context.getSource().sendFeedback(Component.literal("[mcmods_exporter] " + ExportCoordinator.get().status()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("cancel").executes(context -> {
                    boolean cancelled = ExportCoordinator.get().cancel();
                    context.getSource().sendFeedback(Component.literal(cancelled ? "[mcmods_exporter] 已请求取消。" : "[mcmods_exporter] 当前没有任务。"));
                    return cancelled ? 1 : 0;
                }))
                .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("all");
                        ExportBootstrap.availableNamespaces().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(context -> start(
                        context.getSource()::sendFeedback,
                        StringArgumentType.getString(context, "namespace"),
                        ExportProfile.ALL
                    ))
                    .then(ClientCommandManager.argument("profile", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("all");
                            builder.suggest("data");
                            builder.suggest("images");
                            return builder.buildFuture();
                        })
                        .executes(context -> start(
                            context.getSource()::sendFeedback,
                            StringArgumentType.getString(context, "namespace"),
                            ExportProfile.parse(StringArgumentType.getString(context, "profile"))
                        ))))
        ));
    }

    private static int start(java.util.function.Consumer<Component> notifier, String namespace, ExportProfile profile) {
        return ExportBootstrap.startManual(namespace, profile, notifier) ? 1 : 0;
    }
}
