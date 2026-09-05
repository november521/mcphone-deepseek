import com.november.mcphonedeepseek.client.net.DeepSeekClient;
import com.november.mcphonedeepseek.client.net.ModelCatalog;
import com.november.mcphonedeepseek.client.net.Turn;
import com.november.mcphonedeepseek.client.store.ChatMessage;
import com.november.mcphonedeepseek.client.store.Conversation;
import com.november.mcphonedeepseek.client.store.DeepSeekConfig;

/**
 * 把网络层对着一个假服务端跑一遍：流式解析、错误分类、取消、模型列表。
 * 不碰任何界面代码。
 *
 * 跑法见 docs/run-tests.sh —— 它要 Minecraft 的类路径（Turn 的错误信息是
 * Component），所以不像本体那几个测试能光用 javac 编。
 */
public final class DeepSeekClientTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        DeepSeekConfig cfg = DeepSeekConfig.get();
        cfg.setApiKey("sk-test-abcd1234");
        cfg.setThinking(true);
        cfg.setContextMessages(20);
        cfg.setMaxTokens(2048);
        cfg.setModel("deepseek-v4-flash");

        normalStream(cfg);
        httpError(cfg);
        inlineError(cfg);
        cancel(cfg);
        models(cfg);
        noKey(cfg);

        System.out.println(failures == 0 ? "\n全部通过" : "\n有 " + failures + " 项没过");
        System.exit(failures == 0 ? 0 : 1);
    }

    //  ——— 场景 ———

    static void normalStream(DeepSeekConfig cfg) throws Exception {
        cfg.setBaseUrl("http://127.0.0.1:8777/ok");
        Turn t = run("你好");

        check("正常流：状态是 DONE", t.state() == Turn.State.DONE, t.state());
        check("正常流：思考收全了", t.reasoningText().equals("嗯，这个问题要分两步看。"),
                t.reasoningText());
        check("正常流：正文收全了（坏块被跳过）",
                t.answerText().equals("**结论**：先做 A，再做 B。\n\n```java\nint a = 1;\n```"),
                t.answerText());
        check("正常流：量到了思考用时", t.thoughtSeconds() >= 0, t.thoughtSeconds());
        check("正常流：hasReasoning", t.hasReasoning(), true);
    }

    static void httpError(DeepSeekConfig cfg) throws Exception {
        cfg.setBaseUrl("http://127.0.0.1:8777/err402");
        Turn t = run("你好");

        check("402：状态是 ERROR", t.state() == Turn.State.ERROR, t.state());
        check("402：认出了余额不足",
                key(t).equals("mcphone_deepseek.error.balance"), key(t));
    }

    static void inlineError(DeepSeekConfig cfg) throws Exception {
        cfg.setBaseUrl("http://127.0.0.1:8777/inline");
        Turn t = run("你好");

        check("流里夹错误：状态是 ERROR", t.state() == Turn.State.ERROR, t.state());
        check("流里夹错误：走的是 upstream",
                key(t).equals("mcphone_deepseek.error.upstream"), key(t));
    }

    static void cancel(DeepSeekConfig cfg) throws Exception {
        cfg.setBaseUrl("http://127.0.0.1:8777/slow");

        Conversation conv = Conversation.create();
        conv.add(ChatMessage.user("慢慢说"));
        Turn t = DeepSeekClient.send(conv);

        // 等它吐出第一块，再喊停
        long deadline = System.currentTimeMillis() + 5000;
        while (t.reasoningText().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        String before = t.reasoningText();
        t.cancel();

        await(t, 5000);
        check("取消：状态是 CANCELLED", t.state() == Turn.State.CANCELLED, t.state());
        check("取消：已经吐出来的留着", t.reasoningText().startsWith(before) && !before.isEmpty(),
                t.reasoningText());
        check("取消：isRunning 归 false", !t.isRunning(), t.isRunning());
    }

    static void models(DeepSeekConfig cfg) throws Exception {
        cfg.setBaseUrl("http://127.0.0.1:8777/ok");
        ModelCatalog.refresh();

        long deadline = System.currentTimeMillis() + 5000;
        while (ModelCatalog.state() == ModelCatalog.State.LOADING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("模型列表：拉到了", ModelCatalog.state() == ModelCatalog.State.READY,
                ModelCatalog.state());
        check("模型列表：三个都在", ModelCatalog.models().size() == 3, ModelCatalog.models());
    }

    static void noKey(DeepSeekConfig cfg) throws Exception {
        String saved = cfg.apiKey();
        cfg.setApiKey("");

        Conversation conv = Conversation.create();
        conv.add(ChatMessage.user("你好"));
        Turn t = DeepSeekClient.send(conv);

        check("没有 Key：立刻就是 ERROR，一个包都不发",
                t.state() == Turn.State.ERROR, t.state());
        check("没有 Key：报的是 no_key", key(t).equals("mcphone_deepseek.error.no_key"), key(t));

        cfg.setApiKey(saved);
    }

    //  ——— 小工具 ———

    static Turn run(String question) throws Exception {
        Conversation conv = Conversation.create();
        conv.add(ChatMessage.user(question));

        Turn t = DeepSeekClient.send(conv);
        await(t, 10000);
        return t;
    }

    static void await(Turn t, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (t.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 从 Component 里把翻译键抠出来——没有语言文件时 getString 返回的就是键本身 */
    static String key(Turn t) {
        try {
            var c = t.errorMessage().getContents();
            var f = c.getClass().getMethod("getKey");
            return String.valueOf(f.invoke(c));
        } catch (Exception e) {
            return "<" + e + ">";
        }
    }

    static void check(String what, boolean ok, Object actual) {
        if (ok) {
            System.out.println("  通过  " + what);
        } else {
            failures++;
            System.out.println("  失败  " + what + "  ← 实际是 " + actual);
        }
    }
}
