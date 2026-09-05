package com.november.mcphonedeepseek.client.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.mcphonedeepseek.MCphoneDeepSeek;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 落盘那点事 —— 读一个 json、写一个 json，出错不炸。
 *
 * 为什么写之前先写到 .tmp
 *
 * 玩家的对话记录是攒出来的，覆盖到一半断电（或者游戏崩了）就全没了。先写
 * 临时文件再整体挪过去，最坏情况是丢掉最后一次改动，而不是把已有的记录
 * 变成半个文件。
 *
 * 为什么所有异常都只记日志
 *
 * 这些方法的调用方是界面。存盘失败不该让玩家的手机黑屏——MCphone 会把抛
 * 异常的页面直接关掉（见 IPhonePage 的类注释），而"点开 App 自己弹回去了"
 * 比"这次没存上"严重得多。
 */
final class JsonFile {

    private JsonFile() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 读不到、读坏了、类型对不上，一律返回 null，由调用方决定拿什么当默认值 */
    static <T> T read(Path path, Class<T> type) {
        if (!Files.isRegularFile(path)) return null;

        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (Exception e) {
            MCphoneDeepSeek.LOGGER.warn("[MCphone-DeepSeek] 读不了 {}，当作没有", path, e);
            return null;
        }
    }

    static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());

            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, w);
            }

            try {
                Files.move(tmp, path,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                // 某些文件系统（网络盘、部分 Windows 配置）不支持原子移动。
                // 退回普通覆盖：保护弱了一档，但总好过存不上。
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            MCphoneDeepSeek.LOGGER.error("[MCphone-DeepSeek] 写不了 {}", path, e);
        }
    }
}
