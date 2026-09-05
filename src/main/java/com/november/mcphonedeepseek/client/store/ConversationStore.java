package com.november.mcphonedeepseek.client.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 所有对话，落盘记着。
 *
 * 为什么存在客户端而不是存档里
 *
 * 这些对话是玩家和 DeepSeek 之间的事，和世界、和服务器都没有关系。存进存档
 * 意味着换个服务器它们就没了，而玩家想的是"我问过的东西还在"。MCphone 的
 * 书架也是同一个判断，理由写在它的 ShelfStore 里。
 *
 * 顺带一个后果：这些内容不会有任何一个字节经过服务器。服主看不到、其他
 * 玩家也看不到。这不是附带的好处，是这个 App 该有的样子。
 *
 * 只在客户端线程上读写
 *
 * 界面的绘制与点击都在那条线程上，所以这里不加锁。唯一从别的线程来的东西
 * 是流式回复，而那条路径【不】碰这个类：它只往自己的 Turn 里追加字符串，
 * 等回复完了再由渲染线程收进来。
 */
public final class ConversationStore {

    private ConversationStore() {}

    private static final Path FILE = Path.of("config/mcphone_deepseek/conversations.json");

    /** 最多留几段对话。超了从最旧的开始丢 */
    private static final int MAX_CONVERSATIONS = 50;

    /** 磁盘上那份的形状。字段名就是 json 里的键，改名等于作废玩家已有的记录 */
    private static final class State {
        int version = 1;
        List<Conversation> conversations;
    }

    /** null 表示还没从磁盘读过 */
    private static List<Conversation> all;

    /** 按最近更新排在前面，和网页版左边栏一个顺序 */
    public static List<Conversation> all() {
        if (all == null) load();
        all.sort(Comparator.comparingLong(Conversation::updatedMs).reversed());
        return all;
    }

    private static void load() {
        State s = JsonFile.read(FILE, State.class);
        all = (s == null || s.conversations == null)
                ? new ArrayList<>()
                : new ArrayList<>(s.conversations);

        // 读进来先剔一遍空壳：上次退出时可能正停在一段还没说话的新对话上
        all.removeIf(c -> c == null || c.id() == null || c.isEmpty());
    }

    public static Conversation create() {
        if (all == null) load();

        Conversation c = Conversation.create();
        all.add(0, c);
        return c;
    }

    public static void remove(Conversation c) {
        if (all == null) load();

        all.remove(c);
        save();
    }

    public static void clear() {
        if (all == null) load();

        all.clear();
        save();
    }

    /** 内容变了就落一次盘。发出去的问题和收回来的答案各叫一次 */
    public static void changed() {
        save();
    }

    /**
     * 写盘。空对话不写——玩家点了「新对话」又退出去，不该在列表里留一条
     * 什么都没有的记录。
     */
    public static void save() {
        if (all == null) return;

        List<Conversation> keep = new ArrayList<>();
        for (Conversation c : all) {
            if (!c.isEmpty()) keep.add(c);
        }
        keep.sort(Comparator.comparingLong(Conversation::updatedMs).reversed());
        while (keep.size() > MAX_CONVERSATIONS) keep.remove(keep.size() - 1);

        State s = new State();
        s.conversations = keep;
        JsonFile.write(FILE, s);
    }
}
