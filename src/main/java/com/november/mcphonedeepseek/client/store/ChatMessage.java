package com.november.mcphonedeepseek.client.store;

/**
 * 对话里的一条消息。
 *
 * 为什么 reasoning 存下来却不回传
 *
 * 深度思考的内容对玩家有用——他想看模型是怎么想的，翻回旧对话时也该还在，
 * 所以它落盘。但它【不能】跟着下一轮请求发回去：OpenAI 兼容的推理模型对
 * 历史消息里的 reasoning_content 要么报错、要么直接丢弃，回传只是白花钱。
 * 见 {@link com.november.mcphonedeepseek.client.net.DeepSeekClient} 里拼
 * messages 的那一段，它只取 role 与 content。
 *
 * 字段是私有的，但 Gson 照样读得到——它走反射，不看可见性。之所以不用
 * record，是因为流式输出期间这条消息要被一点点追加，record 做不到。
 */
public final class ChatMessage {

    /** "user" 或 "assistant"。就是要发给 API 的那个值，别在这里用中文 */
    private String role;

    private String content;

    /** 深度思考过程，可空。只在本地显示 */
    private String reasoning;

    /** 出错时这里是给玩家看的那句话 */
    private String error;

    /**
     * 这次深度思考花了几秒。0 表示没思考过，或者是旧文件里没这个字段的记录。
     *
     * 存它是为了让历史里的那一行也写得出「已深度思考（用时 12 秒）」。不存的话
     * 翻回旧对话只剩一句干巴巴的「已深度思考」，而那个秒数正是这一行的信息量
     * 所在——它告诉玩家这个答案是想了多久才出来的。
     */
    private int thoughtSeconds;

    /**
     * 这条是什么时候的。
     *
     * 界面上暂时没显示它——网页版也不给每条消息标时间。存着是因为时间戳
     * 这种东西只能在当下记，事后补不回来：哪天想在长对话里插一行"昨天"，
     * 有它就是加几行排版，没它就是所有旧记录都没这个信息。
     */
    private long time;

    /** Gson 反序列化用。别自己调 */
    @SuppressWarnings("unused")
    private ChatMessage() {}

    private ChatMessage(String role, String content, String reasoning, String error,
                        int thoughtSeconds) {
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.error = error;
        this.thoughtSeconds = Math.max(0, thoughtSeconds);
        this.time = System.currentTimeMillis();
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text, null, null, 0);
    }

    public static ChatMessage assistant(String text, String reasoning) {
        return assistant(text, reasoning, null, 0);
    }

    /**
     * 一条回复，可能是断在半路的那种。
     *
     * 正文和错误【同时】留着，不是二选一：流式输出断线时，前面已经吐出来的
     * 那半段仍然有用，把它连同"这里断了"一起记下来，比只留一句报错诚实得多。
     */
    public static ChatMessage assistant(String text, String reasoning, String error,
                                        int thoughtSeconds) {
        return new ChatMessage("assistant", text, reasoning, error, thoughtSeconds);
    }

    public boolean isUser() { return "user".equals(role); }

    /** 读旧文件时字段可能缺，一律兜成空串，界面上不必再判 null */
    public String role() { return role == null ? "assistant" : role; }
    public String content() { return content == null ? "" : content; }
    public String reasoning() { return reasoning == null ? "" : reasoning; }
    public String error() { return error == null ? "" : error; }
    public long time() { return time; }

    /** 思考用时（秒）；0 表示没有，界面据此决定那一行写不写秒数 */
    public int thoughtSeconds() { return Math.max(0, thoughtSeconds); }

    public boolean isError() { return !error().isEmpty(); }
}
