package com.november.mcphonedeepseek.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphonedeepseek.MCphoneDeepSeek;
import com.november.mcphonedeepseek.client.store.ChatMessage;
import com.november.mcphonedeepseek.client.store.Conversation;
import com.november.mcphonedeepseek.client.store.DeepSeekConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 往 DeepSeek 发一次请求，把流式回复往 {@link Turn} 里灌。
 *
 * 为什么走 java.net.http 而不是引第三方库
 *
 * JDK 自带的 HttpClient 会 SSE 要用的一切：异步、流式响应体、能中途关掉。
 * 引 OkHttp 之类的意味着要么 jarJar 打包进去（几百 KB，还可能和别的模组撞
 * 版本），要么要求玩家再装一个前置。为了一个 POST 不值当。
 *
 * 线程
 *
 * 请求与读流都在这里的守护线程池上，Minecraft 的渲染线程一次都不会被挡住。
 * 线程池是【会长的】cached 池，这一点不能改成固定大小：读响应体那一步是
 * 阻塞的，占着一条线程直到回复结束，池子固定就会在同时开两三次对话时把
 * HttpClient 自己的协议任务饿死。
 *
 * 守护线程：玩家关游戏时不该因为一个没读完的回复而卡在退出界面上。
 *
 * 这个类不碰界面、不碰对话记录
 *
 * 它只认识 Turn。回复怎么显示、什么时候写进记录，都是渲染线程那边的事。
 * 理由见 Turn 的类注释。
 */
public final class DeepSeekClient {

    private DeepSeekClient() {}

    private static final Gson GSON = new Gson();

    /** 建连超时。整次请求【不】设超时——回复本来就可能吐上几分钟 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /** 出错时最多读多少字节的响应体。够拼出一句给玩家看的话就行 */
    private static final int MAX_ERROR_BODY = 8 * 1024;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcphone-deepseek-http");
        t.setDaemon(true);
        return t;
    });

    /** 懒建：建一个 HttpClient 会起选择器线程，玩家没点开这个 App 就不该有 */
    private static volatile HttpClient http;

    /** 模型列表那边也用这个池子，理由同 send 里那段 */
    static java.util.concurrent.Executor pool() {
        return POOL;
    }

    static HttpClient http() {
        HttpClient c = http;
        if (c != null) return c;

        synchronized (DeepSeekClient.class) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .executor(POOL)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return http;
        }
    }

    /**
     * 拿这段对话去问 DeepSeek。立刻返回，内容随后往 Turn 里长。
     *
     * 调用之前，对话的最后一条必须已经是玩家那句话——请求体就是照着
     * {@code conv.messages()} 的尾巴截出来的。
     */
    public static Turn send(Conversation conv) {
        Turn turn = new Turn();
        DeepSeekConfig cfg = DeepSeekConfig.get();

        if (!cfg.hasApiKey()) {
            turn.fail("mcphone_deepseek.error.no_key");
            return turn;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body(conv, cfg), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception badUrl) {
            // 玩家在设置里填了个不成形的地址。这里必须自己兜住：URI.create
            // 抛的是 IllegalArgumentException，它跑不进下面的 exceptionally
            turn.fail("mcphone_deepseek.error.bad_url", cfg.baseUrl());
            return turn;
        }

        // thenAcceptAsync(..., POOL) 而不是 thenAccept：后者在【完成这个 future
        // 的那条线程】上跑，实测会落到 ForkJoinPool.commonPool 上。而 consume
        // 里那一步是阻塞的——它要一直读到回复结束，可能是好几分钟。占着一条
        // 公共池的线程那么久，会拖累同一个池子上的所有并行流，而症状会出现在
        // 完全不相干的地方。指名交给自己的池子，那个池子是会长的 cached 池，
        // 阻塞几条都不要紧。
        http().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> consume(turn, response), POOL)
                .exceptionally(err -> {
                    turn.fail(networkKey(err), shortMessage(err));
                    return null;
                });

        return turn;
    }

    //  ——— 请求体 ———

    private static String body(Conversation conv, DeepSeekConfig cfg) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.addProperty("stream", true);
        body.add("messages", messages(conv, cfg));

        // 显式写 type 而不是靠默认值：官方默认是 enabled，但这个 App 也允许
        // 指向别的 OpenAI 兼容服务，那边的默认是什么不由我们说了算。
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", cfg.thinking() ? "enabled" : "disabled");
        body.add("thinking", thinking);

        if (cfg.maxTokens() > 0) body.addProperty("max_tokens", cfg.maxTokens());

        return GSON.toJson(body);
    }

    /**
     * 带上最近的几条。三样东西刻意不发：
     *
     *   思考过程  推理模型对历史里的 reasoning_content 要么报错要么丢弃，
     *             发过去只是白付输入的钱。
     *   失败的回复 那条消息的正文是空的，发过去等于让模型接一句空话。
     *   系统提示   网页版没有，我们也不加。加了就等于替玩家决定 DeepSeek
     *             该是什么脾气，而那不是一个"照着网页版做"的 App 该干的事。
     */
    private static JsonArray messages(Conversation conv, DeepSeekConfig cfg) {
        List<ChatMessage> src = conv.messages();

        // 0 条的意思是"只发这一句，不要上下文"，所以下限是 1 而不是 0
        int limit = Math.max(1, cfg.contextMessages());
        int from = Math.max(0, src.size() - limit);

        JsonArray out = new JsonArray();
        for (int i = from; i < src.size(); i++) {
            ChatMessage m = src.get(i);
            if (m.isError() || m.content().isBlank()) continue;

            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            out.add(o);
        }
        return out;
    }

    //  ——— 响应 ———

    private static void consume(Turn turn, HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            // 先交给 Turn 再开始读：玩家按停止时靠关这条流让读循环退出
            turn.attach(in);

            int status = response.statusCode();
            if (status / 100 != 2) {
                failByStatus(turn, status, readLimited(in));
                return;
            }

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (!handleLine(turn, line)) break;
            }
            turn.finish();

        } catch (Exception e) {
            turn.fail(networkKey(e), shortMessage(e));
        }
    }

    /**
     * 处理 SSE 的一行。
     *
     * @return false 表示这次回复到此为止（收到 [DONE]，或者流里夹了个错误）
     */
    private static boolean handleLine(Turn turn, String line) {
        if (line.isEmpty() || line.startsWith(":")) return true;      // 空行分隔与心跳注释
        if (!line.startsWith("data:")) return true;                   // event:/id: 之类，用不上

        String payload = line.substring("data:".length()).strip();
        if (payload.isEmpty()) return true;
        if ("[DONE]".equals(payload)) return false;

        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) return true;
            JsonObject chunk = parsed.getAsJsonObject();

            // 有的兼容服务不改状态码，把错误当成一个块塞进流里
            if (chunk.has("error")) {
                turn.fail("mcphone_deepseek.error.upstream",
                        messageOf(chunk.get("error")));
                return false;
            }

            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return true;

            JsonElement first = choices.get(0);
            if (!first.isJsonObject()) return true;

            JsonObject delta = first.getAsJsonObject().getAsJsonObject("delta");
            if (delta == null) return true;

            // reasoning 是给别的 OpenAI 兼容服务留的别名：同一件事，
            // 不同家用了不同的键，认两个的代价只是多一行
            turn.appendReasoning(string(delta, "reasoning_content"));
            turn.appendReasoning(string(delta, "reasoning"));
            turn.appendAnswer(string(delta, "content"));

        } catch (Exception malformed) {
            // 单个坏块不该毁掉整次回复。也不记日志：真出问题时它会每秒刷几十条
        }
        return true;
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";

        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? "" : e.getAsString();
    }

    //  ——— 出错时说人话 ———

    private static void failByStatus(Turn turn, int status, String body) {
        String detail = messageOf(errorNode(body));

        MCphoneDeepSeek.LOGGER.warn("[MCphone-DeepSeek] 请求失败：HTTP {}", status);

        switch (status) {
            case 400, 422 -> turn.fail("mcphone_deepseek.error.bad_request", detail);
            case 401 -> turn.fail("mcphone_deepseek.error.auth");
            case 402 -> turn.fail("mcphone_deepseek.error.balance");
            case 404 -> turn.fail("mcphone_deepseek.error.not_found");
            case 429 -> turn.fail("mcphone_deepseek.error.rate_limit");
            case 500, 502, 503, 504 -> turn.fail("mcphone_deepseek.error.server_busy");
            default -> turn.fail("mcphone_deepseek.error.http", status, detail);
        }
    }

    /** 响应体里的 {"error":{"message":...}}，取不出来就返回原文（截短） */
    private static JsonElement errorNode(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                return e.getAsJsonObject().get("error");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String messageOf(JsonElement error) {
        if (error != null && error.isJsonObject()) {
            String m = string(error.getAsJsonObject(), "message");
            if (!m.isBlank()) return truncate(m);
        }
        if (error != null && error.isJsonPrimitive()) return truncate(error.getAsString());
        return "";
    }

    /**
     * 这句话要画进 120 像素宽的屏幕里。上游偶尔会回一整段带堆栈的英文，
     * 原样画出来能把整页顶满，而玩家需要的信息在头一句里。
     */
    private static String truncate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static String readLimited(InputStream in) {
        try {
            return new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * 把异常归成玩家看得懂的几类。
     *
     * 分这么细是因为这几种的解法完全不同：连不上要看网络和地址，超时可以
     * 重试，证书问题多半是代理或者系统时间。都写成"网络错误"等于什么都没说。
     */
    private static String networkKey(Throwable t) {
        Throwable e = unwrap(t);

        if (e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.SocketTimeoutException) {
            return "mcphone_deepseek.error.timeout";
        }
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.nio.channels.UnresolvedAddressException) {
            return "mcphone_deepseek.error.dns";
        }
        if (e instanceof java.net.ConnectException) {
            return "mcphone_deepseek.error.connect";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "mcphone_deepseek.error.tls";
        }
        return "mcphone_deepseek.error.network";
    }

    private static String shortMessage(Throwable t) {
        Throwable e = unwrap(t);
        String m = e.getMessage();
        return truncate(m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
    }
}
