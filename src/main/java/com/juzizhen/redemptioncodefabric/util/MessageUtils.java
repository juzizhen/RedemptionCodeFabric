package com.juzizhen.redemptioncodefabric.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.redemptioncodefabric.RedemptionCodeFabric;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MessageUtils {

    private static final Map<String, String> LANG_MAP;
    private static final Map<String, String> FALLBACK_MAP;

    static {
        String langFile = resolveLangFile(Locale.getDefault());
        LANG_MAP = loadLang(langFile);
        FALLBACK_MAP = langFile.equals("en_us.json") ? LANG_MAP : loadLang("en_us.json");
        RedemptionCodeFabric.LOGGER.info("Server locale: {}, loaded lang: {}", Locale.getDefault(), langFile);
    }

    private static String resolveLangFile(Locale locale) {
        String lang = locale.getLanguage();
        if ("zh".equals(lang)) return "zh_cn.json";
        if ("en".equals(lang)) return "en_us.json";
        // 其他语言环境尝试 语言_国家 格式
        String country = locale.getCountry().toLowerCase();
        if (!country.isEmpty()) {
            return lang + "_" + country + ".json";
        }
        return lang + ".json";
    }

    private static Map<String, String> loadLang(String fileName) {
        String resourcePath = "/assets/redemptioncodefabric/lang/" + fileName;
        try (InputStream is = MessageUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                if (!fileName.equals("en_us.json")) {
                    RedemptionCodeFabric.LOGGER.warn("Lang file not found: {}, falling back to en_us.json", fileName);
                    return loadLang("en_us.json");
                }
                RedemptionCodeFabric.LOGGER.error("en_us.json not found! Translations will be missing.");
                return Collections.emptyMap();
            }
            return new Gson().fromJson(
                    new InputStreamReader(is, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType()
            );
        } catch (Exception e) {
            RedemptionCodeFabric.LOGGER.error("Failed to load lang file {}: {}", fileName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static String getTranslation(String key) {
        String value = LANG_MAP.get(key);
        if (value != null) return value;
        if (FALLBACK_MAP != LANG_MAP) {
            value = FALLBACK_MAP.get(key);
            if (value != null) return value;
        }
        return key;
    }

    /**
     * 向命令来源发送反馈消息，并可选择广播给 OP。
     */
    public static void sendFeedback(ServerCommandSource source, String key, boolean broadcastToOps, Object... args) {
        source.sendFeedback(() -> createText(source, key, args), broadcastToOps);
    }

    /**
     * 向命令来源发送错误消息，并根据客户端是否安装 mod 自适应。
     */
    public static void sendError(ServerCommandSource source, String key, Object... args) {
        source.sendError(createText(source, key, args));
    }

    /**
     * 根据玩家是否安装 mod，创建可翻译或字面量 Text。
     * 持有 ServerCommandSource 时使用此重载（命令、CodeManager 等）。
     */
    public static Text createText(ServerCommandSource source, String key, Object... args) {
        ServerPlayerEntity player = source.getPlayer();
        UUID playerUuid = (player != null) ? player.getUuid() : null;
        return createText(playerUuid, key, args);
    }

    /**
     * 根据玩家是否安装 mod，创建可翻译或字面量 Text。
     * 仅持有玩家 UUID 时使用此重载（事件处理器、直接 sendMessage 等）。
     */
    public static Text createText(UUID playerUuid, String key, Object... args) {
        if (playerUuid != null && RedemptionCodeFabric.hasMod(playerUuid)) {
            return Text.translatable(key, args);
        }
        String template = getTranslation(key);
        if (args.length == 0) {
            return Text.literal(template);
        }
        return formatLiteral(template, args);
    }

    /**
     * 服务端预翻译的字面量格式化，占位符语义与客户端 {@link Text#translatable} 对齐。
     * <p>
     * 模板中的 {@code %s} 按顺序消费参数。若参数本身是 {@link Text}（如可点击的玩家名组件），
     * 则作为子组件嵌入，而非经 {@link String#format} 强转 {@code toString()}——否则非 mod 客户端
     * 会看到 {@code literal{...}[style={...}]} 这类原始对象描述。无 Text 参数时走 String.format
     * 快路径，行为与历史实现完全一致。
     */
    private static Text formatLiteral(String template, Object... args) {
        boolean hasTextArg = false;
        for (Object arg : args) {
            if (arg instanceof Text) {
                hasTextArg = true;
                break;
            }
        }

        if (!hasTextArg) {
            try {
                return Text.literal(String.format(template, args));
            } catch (Exception e) {
                RedemptionCodeFabric.LOGGER.warn("Failed to format message for template '{}': {}", template, e.getMessage());
                return Text.literal(template);
            }
        }

        MutableText result = Text.literal("");
        int argIndex = 0;
        int start = 0;
        int placeholder;
        while ((placeholder = template.indexOf("%s", start)) != -1) {
            if (placeholder > start) {
                result.append(template.substring(start, placeholder));
            }
            if (argIndex < args.length) {
                Object arg = args[argIndex++];
                if (arg instanceof Text) {
                    result.append((Text) arg);
                } else {
                    result.append(String.valueOf(arg));
                }
            } else {
                result.append("%s");
            }
            start = placeholder + 2;
        }
        if (start < template.length()) {
            result.append(template.substring(start));
        }
        return result;
    }
}
