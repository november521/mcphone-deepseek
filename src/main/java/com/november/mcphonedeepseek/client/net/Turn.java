package com.november.mcphonedeepseek.client.net;

import net.minecraft.network.chat.Component;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次请求从发出到结束的全部实时状态 —— 界面每帧读它，HTTP 线程往里写。
 *
 * 这个类是两条线程之间唯一的接触面
 *
 * 写的一侧是 java.net.http 的工作线程，读的一侧是 Minecraft 的渲染线程。
 * 除了这里，两边不共享任何东西：HTTP 线程【不】碰 ConversationStore、不碰
 * 任何界面对象、也不碰 Minecraft 实例。回复写完之后，由渲染线程在下一帧
 * 把它收进对话记录里。
 *
 * 反过来做——在 HTTP 线程上直接改对话、直接刷新界面——是这类功能最常见的
 * 崩法，而且症状极难认：ConcurrentModificationException 出现在某个渲染方法
 * 里，堆栈上一个字都不会提到网络。
 *
 * 可见性靠 volatile 与 StringBuffer
 *
 * 两个正文用 StringBuffer 而不是 StringBuilder：它的方法是同步的，一边追加
 * 一边 toString 不会读到半个字符。其余的状态字段都是 volatile，写的一侧只有
 * 一条线程，不存在两个写者打架。
 *
 * 错误信息存的是翻译键不是句子
 *
 * 因为它是在 HTTP 线程上产生的，而 I18n 属于客户端语言管理器 —— 在别的线程
 * 上问它，轻则拿到还没加载完的表，重则撞上正在重载资源包。所以这里只记
 * "哪个键、什么参数"，等渲染线程要显示时再翻译。
 */
public final class Turn {

    public enum State {
        /** 请求发出去了，还没收到第一个字 */
        CONNECTING,
        /** 正在吐思考过程 */
        THINKING,
        /** 正在吐正文 */
        ANSWERING,
        DONE,
        ERROR,
        /** 玩家自己按的停止 */
        CANCELLED
    }

    private final StringBuffer reasoning = new StringBuffer();
    private final StringBuffer answer = new StringBuffer();

    private volatile State state = State.CONNECTING;

    private volatile String errorKey = "";
    private volatile Object[] errorArgs = new Object[0];

    /** 内容变了就 +1。界面拿它当重排版的凭据，不必每帧比字符串 */
    private final AtomicInteger revision = new AtomicInteger();

    private final long startedMs = System.currentTimeMillis();

    /** 思考花了多久（毫秒）。-1 表示还没思考完，或者压根没思考 */
    private volatile long thoughtMs = -1;

    /** 玩家按了停止。读的一侧看它决定是报错还是报"已停止" */
    private volatile boolean cancelled;

    /** 正在读的那条流。取消时从别的线程关掉它，读循环会因此抛异常退出 */
    private volatile Closeable stream;

    //  ——— 写的一侧：只有 HTTP 线程调 ———

    void appendReasoning(String s) {
        if (s == null || s.isEmpty()) return;

        reasoning.append(s);
        if (state == State.CONNECTING) state = State.THINKING;
        bump();
    }

    void appendAnswer(String s) {
        if (s == null || s.isEmpty()) return;

        // 第一个正文字符落地的那一刻，思考就算结束了。网页版显示的
        // 「已深度思考（用时 N 秒）」量的就是这一段。
        if (thoughtMs < 0 && reasoning.length() > 0) {
            thoughtMs = System.currentTimeMillis() - startedMs;
        }
        answer.append(s);
        state = State.ANSWERING;
        bump();
    }

    void finish() {
        if (state == State.ERROR || state == State.CANCELLED) return;

        if (thoughtMs < 0 && reasoning.length() > 0) {
            thoughtMs = System.currentTimeMillis() - startedMs;
        }
        state = State.DONE;
        bump();
    }

    void fail(String key, Object... args) {
        // 玩家自己按的停止不该显示成报错：关流一定会让读循环抛异常，
        // 那个异常是我们自己造成的
        if (cancelled) {
            state = State.CANCELLED;
            bump();
            return;
        }

        // 已经有结局了就不再改写。读循环正常收尾之后若还有异常冒上来
        // （比如关流本身抛的），那是收尾动作的噪声，不是这次请求的结果。
        if (state == State.DONE || state == State.ERROR) return;

        errorKey = key;
        errorArgs = args == null ? new Object[0] : args;
        state = State.ERROR;
        bump();
    }

    /**
     * 记下这次的流，供 {@link #cancel()} 关。
     *
     * 末尾那一句不是多余的：玩家可能在连接建立【之前】就按了停止，那时
     * stream 还是 null，cancel() 关了个寂寞，等流真的来了没人管它，请求
     * 会一直跑到天亮。
     */
    void attach(Closeable c) {
        this.stream = c;
        if (cancelled) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void bump() {
        revision.incrementAndGet();
    }

    //  ——— 读的一侧：只有渲染线程调 ———

    public State state() { return state; }

    public boolean isRunning() {
        State s = state;
        return s == State.CONNECTING || s == State.THINKING || s == State.ANSWERING;
    }

    /** 快照。每帧调没问题，但配合 {@link #revision()} 只在变了时取更省 */
    public String answerText() { return answer.toString(); }

    public String reasoningText() { return reasoning.toString(); }

    public boolean hasReasoning() { return reasoning.length() > 0; }

    public int revision() { return revision.get(); }

    /** 思考用时（秒），还没思考完或没思考过返回 -1 */
    public int thoughtSeconds() {
        long ms = thoughtMs;
        return ms < 0 ? -1 : (int) Math.max(1, Math.round(ms / 1000.0));
    }

    /** 思考进行中时用它显示实时秒数 */
    public int elapsedSeconds() {
        return (int) ((System.currentTimeMillis() - startedMs) / 1000);
    }

    public Component errorMessage() {
        return errorKey.isEmpty()
                ? Component.translatable("mcphone_deepseek.error.unknown")
                : Component.translatable(errorKey, errorArgs);
    }

    /**
     * 停止生成。
     *
     * 做法是把流关掉，而不是 future.cancel()：请求体已经在路上了，取消那个
     * Future 只是让我们不再等结果，服务端照样在生成、钱照样在算。关掉连接
     * 才会真的让对面停手。
     *
     * 已经吐出来的内容留着不擦——网页版的停止键也是这个行为，玩家按停止是
     * 因为"够了"，不是"我不想要前面那段"。
     */
    public void cancel() {
        if (!isRunning()) return;

        cancelled = true;
        state = State.CANCELLED;

        Closeable c = stream;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关一条已经断了的流会抛，这不是错误
            }
        }
        bump();
    }
}
