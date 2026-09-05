package com.november.mcphonedeepseek.client.store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次完整的对话 —— 网页版左边栏里的一条。
 *
 * 标题怎么来的
 *
 * 取第一条用户消息的开头，不单独存一个标题字段。
 *
 * 网页版会让模型给对话起个名，我们不这么做：那是一次额外的请求，玩家要为
 * 一个标题付一次钱，而第一句话本身通常就说清楚了这次要干嘛。也没有做手动
 * 重命名——列表里一行已经有"打开"和"删除"两个去处，第三个只会让 120 像素
 * 宽的一行更难点准。
 */
public final class Conversation {

    /** 一条对话最多留多少消息。超了从最旧的开始丢——手机上翻不到那么远 */
    private static final int MAX_MESSAGES = 200;

    private String id;

    private long createdMs;
    private long updatedMs;

    private List<ChatMessage> messages;

    @SuppressWarnings("unused")
    private Conversation() {}

    public static Conversation create() {
        Conversation c = new Conversation();
        c.id = UUID.randomUUID().toString();
        c.createdMs = System.currentTimeMillis();
        c.updatedMs = c.createdMs;
        c.messages = new ArrayList<>();
        return c;
    }

    public String id() { return id; }

    public long updatedMs() { return updatedMs; }

    /** 永不返回 null：手写过、或被截断过的 json 里这个字段可能不在 */
    public List<ChatMessage> messages() {
        if (messages == null) messages = new ArrayList<>();
        return messages;
    }

    public boolean isEmpty() { return messages().isEmpty(); }

    public void add(ChatMessage m) {
        messages().add(m);
        while (messages().size() > MAX_MESSAGES) messages().remove(0);
        updatedMs = System.currentTimeMillis();
    }

    /** 最后一条，没有就返回 null。流式写入时要往它身上追加 */
    public ChatMessage last() {
        List<ChatMessage> m = messages();
        return m.isEmpty() ? null : m.get(m.size() - 1);
    }

    /**
     * 列表里显示的那一行：第一条用户消息的开头。
     *
     * 换行要压成空格——标题只有一行的位置，正文里的换行画出来会是一个方框。
     */
    public String displayTitle(String fallback) {
        for (ChatMessage m : messages()) {
            if (m.isUser() && !m.content().isBlank()) {
                return m.content().replaceAll("\\s+", " ").strip();
            }
        }
        return fallback;
    }
}
