package cn.mcmod.wiki.exporter.forge;

import cn.mcmod.wiki.exporter.ExportBootstrap;
import cn.mcmod.wiki.exporter.McWikiExporter;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportCoordinator;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

import java.util.function.Consumer;

@Mod(McWikiExporter.MOD_ID)
public final class McWikiExporterForge {
    public McWikiExporterForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener(McWikiExporterForge::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(McWikiExporterForge::onRegisterCommands);
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ExportBootstrap.tick(net.minecraft.client.Minecraft.getInstance());
        }
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mcwiki_export")
                .executes(context -> start(context.getSource()::sendSystemMessage, "all", ExportProfile.ALL))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSystemMessage(Component.literal("[mcmods_exporter] " + ExportCoordinator.get().status()));
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(context -> {
                    boolean cancelled = ExportCoordinator.get().cancel();
                    context.getSource().sendSystemMessage(Component.literal(cancelled ? "[mcmods_exporter] 已请求取消。" : "[mcmods_exporter] 当前没有任务。"));
                    return cancelled ? 1 : 0;
                }))
                .then(Commands.argument("namespace", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("all");
                        ExportBootstrap.availableNamespaces().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(context -> start(
                        context.getSource()::sendSystemMessage,
                        StringArgumentType.getString(context, "namespace"),
                        ExportProfile.ALL
                    ))
                    .then(Commands.argument("profile", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("all");
                            builder.suggest("data");
                            builder.suggest("images");
                            return builder.buildFuture();
                        })
                        .executes(context -> start(
                            context.getSource()::sendSystemMessage,
                            StringArgumentType.getString(context, "namespace"),
                            ExportProfile.parse(StringArgumentType.getString(context, "profile"))
                        ))))
        );
    }

    private static int start(Consumer<Component> notifier, String namespace, ExportProfile profile) {
        return ExportBootstrap.startManual(namespace, profile, notifier) ? 1 : 0;
    }
}
