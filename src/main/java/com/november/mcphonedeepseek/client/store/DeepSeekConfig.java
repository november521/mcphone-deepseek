package com.november.mcphonedeepseek.client.store;

import java.nio.file.Path;

/**
 * 这台机器上的 DeepSeek 设置 —— API Key、服务地址、模型、几个开关。
 *
 * 为什么不用 NeoForge 的 ModConfigSpec
 *
 * 那套东西是给"启动前在文本编辑器里改"设计的：值在配置加载阶段读进来，
 * 运行期改要走 ModConfigEvent 那一圈。而这里的每一项都要能在手机屏幕上
 * 当场改完当场生效——玩家不会为了填个 API Key 退出游戏去翻 config 目录。
 *
 * 代价是模组列表里那个「配置」按钮点进去是空的。这个取舍是清醒的：设置
 * 入口在手机里，那是玩家真正会去的地方。
 *
 * 为什么和对话记录分成两个文件
 *
 * 一是 Key 和记录的性质不同：记录可以随手发给别人看、可以备份、可以整个
 * 删掉重来，Key 不行。分开之后"把对话记录拷给朋友"不会顺手把 Key 也拷过去。
 * 二是对话文件大得多、写得勤得多，它写坏的概率远高于这个文件——不该让它
 * 把 Key 一起带走。
 *
 * 关于 Key 的存放
 *
 * 它是明文。这一点没什么可含糊的：模组没有系统钥匙串可用，自己"加密"再把
 * 密钥放在同一台机器的代码里，只是让人误以为它是安全的。所以我们做的是
 * 另外三件实事——界面上只显示末四位、日志里一个字都不写、除了 DeepSeek
 * 的服务地址之外不发给任何地方。
 */
public final class DeepSeekConfig {

    private static final Path FILE = Path.of("config/mcphone_deepseek/config.json");

    /** 官方地址。填别的 OpenAI 兼容服务时，多数要自己把 /v1 带上 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /**
     * 默认模型。挑 flash 不挑 pro：这是装在手机屏幕上的对话框，回得快比回得
     * 深更要紧，价钱也只有三分之一。想要 pro 在设置里换。
     */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /**
     * 拉不到模型列表时垫底的那几个。
     *
     * 只是垫底，不是白名单：设置页的模型项允许直接手输，DeepSeek 换代过一次
     * 模型名（deepseek-chat/reasoner → deepseek-v4-*），下次还会换，而写死一份
     * 列表的模组会在那天变成"新模型用不了"。能连上网时以 /models 返回的为准。
     */
    public static final String[] FALLBACK_MODELS = {
            "deepseek-v4-flash",
            "deepseek-v4-pro",
    };

    private String apiKey = "";
    private String baseUrl = DEFAULT_BASE_URL;
    private String model = DEFAULT_MODEL;

    /** 深度思考。这是新对话的默认值，聊天页那个胶囊按钮直接改它 */
    private boolean thinking = true;

    /** 每次请求带上最近多少条消息。带得越多越懂上下文，也越贵 */
    private int contextMessages = 20;

    /**
     * 单次回复的上限。0 = 不限。
     *
     * 默认 2048 是一道花钱的闸：模型能一口气输出 38 万 token，而这块屏幕
     * 120 像素宽——真吐那么多，玩家滚不到底，账单却是真的。
     */
    private int maxTokens = 2048;

    private static DeepSeekConfig instance;

    public static DeepSeekConfig get() {
        if (instance == null) {
            DeepSeekConfig loaded = JsonFile.read(FILE, DeepSeekConfig.class);
            instance = loaded == null ? new DeepSeekConfig() : loaded.sanitized();
        }
        return instance;
    }

    /** 手改过的 json 里什么都可能有。把值拉回合法区间，别让界面去防 */
    private DeepSeekConfig sanitized() {
        if (apiKey == null) apiKey = "";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_BASE_URL;
        if (model == null || model.isBlank()) model = DEFAULT_MODEL;
        contextMessages = Math.clamp(contextMessages, 0, 100);
        maxTokens = Math.clamp(maxTokens, 0, 65536);
        return this;
    }

    public void save() { JsonFile.write(FILE, this); }

    public String apiKey() { return apiKey; }

    public boolean hasApiKey() { return !apiKey.isBlank(); }

    public void setApiKey(String s) {
        apiKey = s == null ? "" : s.strip();
        save();
    }

    /**
     * 给界面看的 Key：只留末四位。
     *
     * 不显示前缀 "sk-"，也不按真实长度补点——两者都是在告诉旁边看屏幕的人
     * 这串东西有多长、长什么样。固定六个点就够玩家确认"我填过了"。
     */
    public String maskedApiKey() {
        if (!hasApiKey()) return "";
        String k = apiKey.strip();
        return k.length() <= 4 ? "••••" : "••••••" + k.substring(k.length() - 4);
    }

    /** 末尾的斜杠去掉，拼路径时才不会出现 https://x//chat/completions */
    public String baseUrl() {
        String b = baseUrl.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public void setBaseUrl(String s) {
        baseUrl = (s == null || s.isBlank()) ? DEFAULT_BASE_URL : s.strip();
        save();
    }

    public String model() { return model; }

    public void setModel(String s) {
        if (s != null && !s.isBlank()) {
            model = s.strip();
            save();
        }
    }

    public boolean thinking() { return thinking; }

    public void setThinking(boolean b) {
        thinking = b;
        save();
    }

    public int contextMessages() { return contextMessages; }

    public void setContextMessages(int n) {
        contextMessages = Math.clamp(n, 0, 100);
        save();
    }

    public int maxTokens() { return maxTokens; }

    public void setMaxTokens(int n) {
        maxTokens = Math.clamp(n, 0, 65536);
        save();
    }
}
