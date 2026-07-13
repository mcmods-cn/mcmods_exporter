package cn.mcmod.wiki.exporter.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.PlainTextContents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ComponentLocalizer {
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    private ComponentLocalizer() {
    }

    public static String localize(Component component, Map<String, String> translations) {
        if (!(component.getContents() instanceof LiteralContents)
            && !(component.getContents() instanceof KeybindContents)
            && !(component.getContents() instanceof TranslatableContents)) {
            return component.getString();
        }
        StringBuilder output = new StringBuilder(localizeContents(component, translations));
        for (Component sibling : component.getSiblings()) {
            output.append(localize(sibling, translations));
        }
        return output.toString();
    }

    private static String localizeContents(Component component, Map<String, String> translations) {
        ComponentContents contents = component.getContents();
        if (contents instanceof LiteralContents literal) {
            return literal.text();
        }
        if (contents instanceof KeybindContents keybind) {
            return translations.getOrDefault(keybind.getName(), keybind.getName());
        }
        if (contents instanceof TranslatableContents translatable) {
            String template = translations.get(translatable.getKey());
            if (template == null) {
                template = translatable.getFallback() == null ? translatable.getKey() : translatable.getFallback();
            }
            return applyArguments(template, translatable.getArgs(), translations);
        }
        return "";
    }

    private static String applyArguments(String template, Object[] arguments, Map<String, String> translations) {
        Matcher matcher = FORMAT.matcher(template);
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        int implicitIndex = 0;
        while (matcher.find()) {
            output.append(template, cursor, matcher.start());
            String token = matcher.group(2);
            if ("%".equals(token) && "%%".equals(matcher.group())) {
                output.append('%');
            } else if ("s".equals(token)) {
                String explicit = matcher.group(1);
                int index = explicit == null ? implicitIndex++ : Integer.parseInt(explicit) - 1;
                output.append(argument(index, arguments, translations));
            } else {
                output.append(matcher.group());
            }
            cursor = matcher.end();
        }
        output.append(template, cursor, template.length());
        return output.toString();
    }

    private static String argument(int index, Object[] arguments, Map<String, String> translations) {
        if (index < 0 || index >= arguments.length) {
            return "%" + (index + 1) + "$s";
        }
        Object argument = arguments[index];
        if (argument instanceof Component component) {
            return localize(component, translations);
        }
        return String.valueOf(argument);
    }
}
