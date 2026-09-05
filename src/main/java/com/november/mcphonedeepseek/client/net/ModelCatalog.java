package com.november.mcphonedeepseek.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphonedeepseek.client.store.DeepSeekConfig;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 有哪些模型可以选 —— 现问服务端，别在代码里写死一份。
 *
 * 为什么非要联网问
 *
 * DeepSeek 已经换过一次模型名（deepseek-chat / deepseek-reasoner 换成了
 * deepseek-v4-*），以后还会换。任何把模型名写死在代码里的客户端，都会在
 * 换代那天变成"新模型用不了，得等作者更新"。而这件事 API 自己就答得出来：
 * GET /models 返回的就是此刻真正能用的那几个。
 *
 * 拉不到也不挡路：设置页允许直接手输模型名，
 * {@link DeepSeekConfig#FALLBACK_MODELS} 只是没网时的垫底选项。
 *
 * 线程
 *
 * 和 {@link DeepSeekClient} 同一条规矩：请求在守护线程上跑，结果写进
 * volatile 字段，界面每帧读。这里没有 Turn 那样的增量，一次性写完即可。
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    public enum State { IDLE, LOADING, READY, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile List<String> models = List.of();

    /** 这份列表是照着哪个地址加 Key 拉的。换了就得重拉，否则会拿着旧服务的列表 */
    private static volatile String fetchedFor = "";

    public static State state() { return state; }

    /** 拉到了就是服务端给的，否则是垫底那几个 */
    public static List<String> models() {
        List<String> m = models;
        return m.isEmpty() ? List.of(DeepSeekConfig.FALLBACK_MODELS) : m;
    }

    /** 设置页每次打开叫一次。地址和 Key 没变就不重复拉 */
    public static void refreshIfStale() {
        DeepSeekConfig cfg = DeepSeekConfig.get();
        if (!cfg.hasApiKey()) return;

        String signature = cfg.baseUrl() + " " + cfg.apiKey();
        if (state == State.LOADING) return;
        if (state == State.READY && signature.equals(fetchedFor)) return;

        refresh();
    }

    public static void refresh() {
        DeepSeekConfig cfg = DeepSeekConfig.get();
        if (!cfg.hasApiKey()) {
            state = State.FAILED;
            return;
        }

        final String signature = cfg.baseUrl() + " " + cfg.apiKey();
        state = State.LOADING;

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/models"))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .GET()
                    .build();
        } catch (Exception badUrl) {
            state = State.FAILED;
            return;
        }

        DeepSeekClient.http()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAcceptAsync(response -> {
                    if (response.statusCode() / 100 != 2) {
                        state = State.FAILED;
                        return;
                    }
                    List<String> parsed = parse(response.body());
                    if (parsed.isEmpty()) {
                        state = State.FAILED;
                        return;
                    }
                    models = List.copyOf(parsed);
                    fetchedFor = signature;
                    state = State.READY;
                }, DeepSeekClient.pool())
                .exceptionally(err -> {
                    state = State.FAILED;
                    return null;
                });
    }

    private static List<String> parse(String body) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = o.getAsJsonArray("data");
            if (data == null) return out;

            for (JsonElement e : data) {
                if (!e.isJsonObject()) continue;

                JsonElement id = e.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive() && !id.getAsString().isBlank()) {
                    out.add(id.getAsString());
                }
            }
        } catch (Exception ignored) {
            // 对面回了别的形状。当成拉不到，垫底列表接手
        }
        return out;
    }
}
