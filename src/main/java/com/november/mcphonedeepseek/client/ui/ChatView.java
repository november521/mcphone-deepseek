package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphonedeepseek.client.net.DeepSeekClient;
import com.november.mcphonedeepseek.client.net.Turn;
import com.november.mcphonedeepseek.client.store.ChatMessage;
import com.november.mcphonedeepseek.client.store.Conversation;
import com.november.mcphonedeepseek.client.store.ConversationStore;
import com.november.mcphonedeepseek.client.store.DeepSeekConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 对话页 —— 这个 App 的正脸。
 *
 * 照着网页版的哪几样
 *
 *   玩家的话是右对齐的蓝气泡，DeepSeek 的回答是铺满整行的纯文字（不套气泡）。
 *   思考过程收在「已深度思考（用时 N 秒）」那一行里，点一下展开。
 *   输入框上方一个「深度思考」胶囊开关，右下角一个圆形发送键。
 *   正在生成时发送键变成停止键。
 *   一条都没有时，中间是图标 + 那句「我是 DeepSeek，很高兴见到你！」。
 *
 * 没照抄的：联网搜索（API 没有这个开关）、上传附件（手机里没有文件系统）、
 * 左边栏（120 像素塞不下，改成单独一页）。
 *
 * 滚动按像素不按条数
 *
 * 一条回复可能有几十行，按条数滚等于一下跳过一整屏。scrollPx 是"从最新一条
 * 往回翻了多少像素"，0 就是贴着底——流式输出时正是靠这一点自动跟着往下走。
 *
 * 排版结果整块缓存
 *
 * Markdown 解析加折行不便宜，而它的结果只在几样东西变了时才变：宽度、主题、
 * 消息条数、流式内容、思考展开状态。把这些揉成一个 key，没变就不重排。
 */
final class ChatView implements View {

    private static final int PAD = 4;

    /**
     * 标题栏高度。
     *
     * 14 而不是 13：这一行里最高的东西是 12 像素的按钮，底下还要一条分隔线。
     * 13 的时候按钮占到 y+12、分隔线画在 y+11，线是从按钮身上穿过去的。
     */
    private static final int HEADER_H = 14;

    private static final int TOOLBAR_H = 11;

    /** 发送键那个圆的直径 */
    private static final int SEND_SIZE = 13;

    /**
     * 输入行高度。
     *
     * 和发送键那个圆的直径相等，两者才齐平。上一版是 15（实际画 14）配一个
     * 13 的圆，圆比输入框矮一像素、又落不到整数中点上，看着就是没对齐。
     */
    private static final int INPUT_H = SEND_SIZE;

    /** 两条消息之间空多少 */
    private static final int MSG_GAP = 4;

    /** 滚轮一格滚多少像素 */
    private static final int SCROLL_STEP = 18;


    /** 头上那两个小按钮的点击区大小 */
    private static final int ICON_HIT = 12;

    /** 标题左边那个品牌蓝的点 */
    private static final int BRAND_DOT = 7;

    private static final int MAX_INPUT = 2000;

    private final DeepSeekPage page;

    /** 当前正看着的这段对话 */
    private Conversation conv;

    /** 正在跑的那次请求；null 表示没有 */
    private Turn turn;

    /**
     * 那次请求属于哪段对话。
     *
     * 和 conv 分开存，是为了让玩家在等回复时能切去看别的对话——回复照样落在
     * 它自己那一段里，不会跟着人跑。网页版就是这个行为。
     */
    private Conversation turnConv;

    private EditBox box;

    private int scrollPx;
    private int maxScroll;

    //  排版缓存
    private record Laid(MessageLayout layout, ChatMessage source, boolean live) {}

    private List<Laid> laid = List.of();
    private int contentH;
    private long laidKey = Long.MIN_VALUE;

    /** 哪几条的思考过程是展开的。按身份存，不按内容——两条一模一样的回复是两条 */
    private final Set<ChatMessage> expanded =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** 展开状态改了几次，进排版缓存的 key */
    private int expandRevision;

    /** 正在流式输出的那条，思考展开着没有 */
    private boolean liveExpanded = true;

    /** 已经自动收起过一次了。收起只该发生在正文的第一个字落地时，不是每帧 */
    private boolean liveCollapsed;

    //  上一帧记下的几何，点击时用
    private int viewX, viewTop, viewBottom, viewW;
    private int sendX, sendY;
    private int thinkX, thinkY, thinkW, thinkH;
    private int newChatX, historyX, iconY;
    private int modelX, modelY, modelW, modelH;
    private int actionX, actionY, actionW, actionH;

    ChatView(DeepSeekPage page) {
        this.page = page;
    }

    //  ——— 对话的进出 ———

    Conversation conversation() {
        if (conv == null) conv = newestOrCreate();
        return conv;
    }

    void open(Conversation c) {
        conv = c;
        scrollPx = 0;
        invalidate();
    }

    /** 点「新对话」。当前这段还是空的就什么都不做——不然会攒出一串空壳 */
    void startNew() {
        if (conv != null && conv.isEmpty()) return;

        conv = ConversationStore.create();
        scrollPx = 0;
        invalidate();
    }

    private Conversation newestOrCreate() {
        List<Conversation> all = ConversationStore.all();
        return all.isEmpty() ? ConversationStore.create() : all.get(0);
    }

    private void invalidate() {
        laidKey = Long.MIN_VALUE;
    }

    /**
     * 某段对话被删了。
     *
     * 正看着它就换一段；正在为它跑的请求也得停——那段记录已经不存在了，
     * 回复回来之后无处可放，留着只是白花钱。
     */
    void forget(Conversation gone) {
        if (turnConv == gone) {
            if (turn != null) turn.cancel();
            turn = null;
            turnConv = null;
        }
        if (conv == gone) {
            conv = null;
            scrollPx = 0;
            invalidate();
        }
    }

    /** 记录被整个清空了。当前这段、正在跑的请求、展开状态一并作废 */
    void reset() {
        if (turn != null) turn.cancel();
        turn = null;
        turnConv = null;
        conv = null;
        scrollPx = 0;
        expanded.clear();
        expandRevision++;
        invalidate();
    }

    //  ——— 每帧 ———

    @Override
    public void render(PhoneCanvas c, Theme theme) {
        Font font = c.font();

        conversation();
        collectFinishedTurn();

        // 每帧先作废：空状态那个按钮只在没填 Key 时存在，不清掉的话，
        // 填完 Key 之后它的命中区还留在那儿，点空白处会莫名跳进设置
        actionW = 0;

        final int x = c.x() + PAD;
        final int w = c.width() - PAD * 2;

        final int inputTop = c.y() + c.height() - INPUT_H;
        final int toolbarTop = inputTop - TOOLBAR_H;
        final int top = c.y() + HEADER_H;
        final int bottom = toolbarTop - 2;

        viewX = x;
        viewW = w;
        viewTop = top;
        viewBottom = bottom;

        drawHeader(c, theme, x, c.y(), w);

        relayout(font, theme, w);

        if (laid.isEmpty()) {
            // 一并把滚动量清掉。留着上一段对话的值，会让滚轮在这一页上
            // 有反应却什么都不动——那种"坏了"的感觉最难查
            scrollPx = 0;
            maxScroll = 0;
            drawEmptyState(c, theme, x, top, w, bottom - top);
        } else {
            drawMessages(c, theme, x, top, w, bottom);
        }

        drawToolbar(c, theme, x, toolbarTop, w);
        drawInput(c, theme, x, inputTop, w);
    }

    //  ——— 头 ———

    private void drawHeader(PhoneCanvas c, Theme theme, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        // 这一行上的四样东西——蓝点、标题、两个按钮——全部对到【标题那行字】上。
        // 上一版是各写各的偏移（+3、+3、+1），三样东西落在三条中线上，
        // 差 1 像素；14 像素高的一行里那是看得出来的高低不齐。
        final int titleY = Ui.textY(font, y, HEADER_H);

        Ui.circle(g, x, Ui.alignY(font, titleY, BRAND_DOT), BRAND_DOT, Ui.BRAND);

        String title = Component.translatable("mcphone_deepseek.app.deepseek").getString();
        g.drawString(font, title, x + BRAND_DOT + 2, titleY, theme.title(), true);

        iconY = Ui.alignY(font, titleY, ICON_HIT);
        historyX = x + w - ICON_HIT;
        newChatX = historyX - ICON_HIT - 1;

        drawPlus(g, newChatX, iconY, c.hovered(newChatX, iconY, ICON_HIT, ICON_HIT)
                ? theme.title() : theme.subtle());
        drawList(g, historyX, iconY, c.hovered(historyX, iconY, ICON_HIT, ICON_HIT)
                ? theme.title() : theme.subtle());

        Ui.hLine(g, x, y + HEADER_H - 1, w, theme.subtle() & 0x40FFFFFF);
    }

    /**
     * 「新对话」的加号，用两个矩形拼的。
     *
     * 不用字体里的 ＋：这块屏幕上一个字符是 9 像素高、位置由字形决定，
     * 想让它和旁边的图标对齐得靠试。两个 fill 想画在哪就在哪。
     */
    private static void drawPlus(GuiGraphics g, int x, int y, int color) {
        int cx = x + ICON_HIT / 2;
        int cy = y + ICON_HIT / 2;
        g.fill(cx - 3, cy, cx + 4, cy + 1, color);
        g.fill(cx, cy - 3, cx + 1, cy + 4, color);
    }

    /** 「对话列表」的三道横线 */
    private static void drawList(GuiGraphics g, int x, int y, int color) {
        int cx = x + ICON_HIT / 2;
        int cy = y + ICON_HIT / 2;
        for (int i = -1; i <= 1; i++) {
            g.fill(cx - 3, cy + i * 3, cx + 4, cy + i * 3 + 1, color);
        }
    }

    //  ——— 空状态 ———

    private void drawEmptyState(PhoneCanvas c, Theme theme, int x, int y, int w, int h) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        boolean ready = DeepSeekConfig.get().hasApiKey();

        List<FormattedCharSequence> title = font.split(
                Component.translatable(ready
                        ? "mcphone_deepseek.ui.greeting"
                        : "mcphone_deepseek.ui.no_key"), w);
        List<FormattedCharSequence> sub = font.split(
                Component.translatable(ready
                        ? "mcphone_deepseek.ui.greeting_sub"
                        : "mcphone_deepseek.ui.no_key_sub"), w);

        int iconSize = 20;
        int buttonH = ready ? 0 : 13;
        int blockH = iconSize + 5
                + title.size() * font.lineHeight + 3
                + sub.size() * font.lineHeight
                + (ready ? 0 : 5 + buttonH);

        // 略微偏上：正中间会显得往下坠，这是排版上的老规矩
        int ty = y + Math.max(0, (h - blockH) * 2 / 5);

        Ui.texture(g, DeepSeekIcon.TEXTURE, x + (w - iconSize) / 2, ty, iconSize, iconSize);
        ty += iconSize + 5;

        for (FormattedCharSequence line : title) {
            g.drawString(font, line, x + (w - font.width(line)) / 2, ty, theme.title(), false);
            ty += font.lineHeight;
        }
        ty += 3;
        for (FormattedCharSequence line : sub) {
            g.drawString(font, line, x + (w - font.width(line)) / 2, ty, theme.subtle(), false);
            ty += font.lineHeight;
        }

        if (ready) {
            actionW = 0;
            return;
        }

        // 没有 Key 时给一条明路。这个按钮是这一页唯一能点的东西
        String label = Component.translatable("mcphone_deepseek.ui.to_settings").getString();
        actionW = font.width(label) + 14;
        actionH = buttonH;
        actionX = x + (w - actionW) / 2;
        actionY = ty + 5;

        boolean hover = c.hovered(actionX, actionY, actionW, actionH);
        Ui.roundRect(g, actionX, actionY, actionW, actionH, 3,
                hover ? Ui.BRAND_PRESSED : Ui.BRAND);
        Ui.centered(g, font, label, actionX,
                Ui.controlTextY(font, actionY, actionH), actionW, Ui.ON_BRAND);
    }

    //  ——— 消息 ———

    private void drawMessages(PhoneCanvas c, Theme theme, int x, int top, int w, int bottom) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        int viewH = bottom - top;
        maxScroll = Math.max(0, contentH - viewH);
        scrollPx = Math.clamp(scrollPx, 0, maxScroll);

        // 不足一屏就从顶往下排；超出时贴底，scrollPx 把内容往下推露出更早的
        final int startY = contentH <= viewH ? top : bottom - contentH + scrollPx;

        // 走 canvas.clipped 而不是 g.enableScissor：原版那句收窗口坐标、且不看 PoseStack，
        // 而玩家可以在「设置 → 界面大小」里把整个手机放大（本体 1.9.3）。直接把这里的
        // 手机坐标交给原版，裁剪框就停在 100% 时的位置和大小上，消息被切掉一块——
        // 而且只在倍数不是 100% 时出现。clipped 会把矩形过一遍当前的变换矩阵
        c.clipped(x, top, w, bottom - top, () -> {
            int y = startY;
            for (Laid l : laid) {
                int h = l.layout().height();
                if (y + h > top && y < bottom) {
                    l.layout().draw(g, font, x, y, w);
                    if (l.live()) drawCaret(g, font, l.layout(), x, y, theme);
                }
                y += h + MSG_GAP;
            }
        });

        // scrollPx 在这一页是"从最新一条往回翻了多远"，而滚动条要的是
        // "距内容顶端多远"——两者正好相反。上一版直接把 scrollPx 传了进去，
        // 于是滑块和内容反着走：贴着最新消息时滑块顶在最上面，往回翻历史
        // 滑块反而往下掉。看上去就是滚轮方向反了
        Ui.scrollbar(g, x + w - 1, top, viewH, maxScroll - scrollPx, contentH, viewH,
                theme.subtle() & 0x60FFFFFF);
    }

    /**
     * 正在生成时那个一闪一闪的光标。
     *
     * 它不只是好看：流式输出有时会卡上几秒（模型在想、或者网络在抖），没有
     * 这个光标的话，玩家分不清"还在生成"和"已经断了"。
     */
    private void drawCaret(GuiGraphics g, Font font, MessageLayout layout,
                           int x, int y, Theme theme) {
        if (turn == null || !turn.isRunning()) return;
        if ((System.currentTimeMillis() / 500) % 2 != 0) return;

        int cx = layout.caretX(x);
        int cy = layout.caretY(y);
        g.fill(cx + 1, cy, cx + 2, cy + font.lineHeight - 1, theme.body());
    }

    //  ——— 工具条与输入 ———

    private void drawToolbar(PhoneCanvas c, Theme theme, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();
        DeepSeekConfig cfg = DeepSeekConfig.get();

        String label = Component.translatable("mcphone_deepseek.ui.thinking").getString();
        thinkW = font.width(label) + 8;
        thinkH = 9;
        thinkX = x;
        thinkY = y + (TOOLBAR_H - thinkH) / 2;

        boolean on = cfg.thinking();
        boolean hover = c.hovered(thinkX, thinkY, thinkW, thinkH);

        Ui.roundRect(g, thinkX, thinkY, thinkW, thinkH, 3,
                on ? Ui.BRAND_SOFT : (hover ? theme.pressedOverlay() : Ui.SUNKEN));
        Ui.centered(g, font, label, thinkX, Ui.controlTextY(font, thinkY, thinkH), thinkW,
                on ? Ui.BRAND : theme.subtle());

        // 右边显示当前模型，点一下进设置。既是信息也是入口：玩家最常想改的
        // 就是"换个模型"，而它本来藏在两层之外
        String model = shortModel(cfg.model());
        modelW = Math.min(w - thinkW - 4, font.width(model) + 4);
        modelH = thinkH;
        modelX = x + w - modelW;
        modelY = thinkY;

        boolean modelHover = c.hovered(modelX, modelY, modelW, modelH);
        // 和左边那个胶囊用同一条基线：两者并排，对不齐一眼就看得出来
        g.drawString(font, Ui.truncate(font, model, modelW - 2),
                modelX + 2, Ui.controlTextY(font, modelY, modelH),
                modelHover ? theme.title() : theme.subtle(), false);
    }

    /** deepseek-v4-flash 在 40 像素里显示不下，砍掉谁都知道的那个前缀 */
    private static String shortModel(String model) {
        return model.startsWith("deepseek-") ? model.substring("deepseek-".length()) : model;
    }

    private void drawInput(PhoneCanvas c, Theme theme, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        int boxW = w - SEND_SIZE - 3;
        Ui.roundRect(g, x, y, boxW, INPUT_H, 4, Ui.SUNKEN);

        int textY = Ui.controlTextY(font, y, INPUT_H);
        // 光标不受 EditBox 的裁剪，右端要多留一个字符的位置，否则会戳出框外
        int textW = boxW - 6 - font.width("_");

        if (box == null) {
            // 无边框的 EditBox 直接把 y 当文字顶端用（原版源码里 bordered
            // 为假时不做那次居中），所以这里给的就是算好的 textY
            box = new EditBox(font, x + 3, textY, textW, font.lineHeight,
                    Component.translatable("mcphone_deepseek.ui.hint"));
            box.setMaxLength(MAX_INPUT);
            box.setBordered(false);
            box.setHint(Component.translatable("mcphone_deepseek.ui.hint"));
            box.setFocused(true);
        } else {
            box.setX(x + 3);
            box.setY(textY);
            box.setWidth(textW);
        }
        box.setTextColor(theme.body());
        box.render(g, c.mouseX(), c.mouseY(), c.partialTick());

        sendX = x + w - SEND_SIZE;
        sendY = y;                       // 和输入框同高，直接齐平

        boolean running = turn != null && turn.isRunning();
        boolean empty = box.getValue().isBlank();
        boolean hover = c.hovered(sendX, sendY, SEND_SIZE, SEND_SIZE);

        int color = running || !empty
                ? (hover ? Ui.BRAND_PRESSED : Ui.BRAND)
                : theme.buttonDisabled();
        Ui.circle(g, sendX, sendY, SEND_SIZE, color);

        int cx = sendX + SEND_SIZE / 2;
        int cy = sendY + SEND_SIZE / 2;
        int glyph = running || !empty ? Ui.ON_BRAND : theme.buttonDisabledText();

        if (running) {
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, glyph);
        } else {
            drawSendArrow(g, cx, cy, glyph);
        }
    }

    /**
     * 发送键里那个向上的箭头。自己拼像素，不指望字体里有合适的箭头字形。
     *
     * 形状：箭头 4 行（1、3、5、7 像素宽），杆 3 行（3 像素宽），一共 7 行，
     * 在 13 像素的圆里上下各留 3 像素，正居中。
     *
     * 上一版是头 3 行配一根 1 像素、5 行长的杆——头太小、杆太细太长，画出来
     * 像个十字架不像箭头。1 像素的杆配 7 像素的头更糟，看着是个 T。
     *
     * 每一行都写成 cx-i 到 cx+i+1，左右对称是【算出来的】：这一行占 2i+1 个
     * 像素，正中那个永远是 cx。
     */
    private static void drawSendArrow(GuiGraphics g, int cx, int cy, int color) {
        for (int i = 0; i < 4; i++) {
            g.fill(cx - i, cy - 3 + i, cx + i + 1, cy - 2 + i, color);
        }
        g.fill(cx - 1, cy + 1, cx + 2, cy + 4, color);
    }

    //  ——— 排版 ———

    private void relayout(Font font, Theme theme, int w) {
        long key = 1L;
        key = key * 31 + w;
        key = key * 31 + theme.signature();
        key = key * 31 + System.identityHashCode(conv);
        key = key * 31 + conv.messages().size();
        key = key * 31 + expandRevision;
        key = key * 31 + (liveExpanded ? 1 : 0);
        key = key * 31 + (turn == null ? 0 : turn.revision() + 1);
        // 思考进行中时秒数每秒变一次，而它不带新内容、revision 不动
        key = key * 31 + (turn == null ? 0 : turn.elapsedSeconds());

        if (key == laidKey) return;

        List<Laid> out = new ArrayList<>();
        for (ChatMessage m : conv.messages()) {
            out.add(new Laid(layoutOf(font, w, theme, m), m, false));
        }
        if (turn != null && turnConv == conv) {
            out.add(new Laid(layoutOfLive(font, w, theme), null, true));
        }

        int total = 0;
        for (Laid l : out) total += l.layout().height() + MSG_GAP;

        // 翻着旧内容时新的字冒出来，把新增的高度补进滚动量，视图才不会跳。
        // 贴底时（scrollPx == 0）不补——那正是"跟着最新内容走"想要的效果
        if (scrollPx > 0 && total > contentH) scrollPx += total - contentH;

        laid = List.copyOf(out);
        contentH = total;
        laidKey = key;
    }

    private MessageLayout layoutOf(Font font, int w, Theme theme, ChatMessage m) {
        if (m.isUser()) return MessageLayout.user(font, w, m.content(), theme);

        String header = "";
        if (!m.reasoning().isBlank()) {
            header = m.thoughtSeconds() > 0
                    ? Component.translatable("mcphone_deepseek.ui.thought_secs",
                            m.thoughtSeconds()).getString()
                    : Component.translatable("mcphone_deepseek.ui.thought_done").getString();
        }

        return MessageLayout.assistant(font, w, m.content(), m.reasoning(),
                header, expanded.contains(m),
                m.isError() ? Component.literal(m.error()) : null,
                theme);
    }

    private MessageLayout layoutOfLive(Font font, int w, Theme theme) {
        String header = "";
        if (turn.hasReasoning()) {
            int done = turn.thoughtSeconds();
            header = done >= 0
                    ? Component.translatable("mcphone_deepseek.ui.thought_secs", done).getString()
                    : Component.translatable("mcphone_deepseek.ui.thinking_now",
                            turn.elapsedSeconds()).getString();
        }

        Component error = turn.state() == Turn.State.ERROR ? turn.errorMessage() : null;

        String answer = turn.answerText();

        // 什么都还没来时占一行「正在连接…」，不然发出去之后屏幕上没有任何反应
        if (answer.isEmpty() && header.isEmpty() && error == null) {
            answer = Component.translatable("mcphone_deepseek.ui.connecting").getString();
        }

        return MessageLayout.assistant(font, w, answer, turn.reasoningText(),
                header, liveExpanded, error, theme);
    }

    //  ——— 发与收 ———

    private void send() {
        if (box == null) return;
        if (turn != null && turn.isRunning()) return;

        String text = box.getValue().strip();
        if (text.isEmpty()) return;

        // 没填 Key 就别发出去。把玩家送到设置页，输入框里的字留着——
        // 让他填完 Key 回来还得重打一遍，是最没道理的一种设计
        if (!DeepSeekConfig.get().hasApiKey()) {
            page.showSettings();
            return;
        }

        conv.add(ChatMessage.user(text));
        box.setValue("");

        turnConv = conv;
        turn = DeepSeekClient.send(conv);

        liveExpanded = true;
        liveCollapsed = false;

        scrollPx = 0;
        ConversationStore.changed();
    }

    /**
     * 把跑完的那次请求收进对话记录。每帧问一次。
     *
     * 为什么由渲染线程来做这件事：写记录要动 ConversationStore，而那个类
     * 说好了只在客户端线程上读写。HTTP 线程只往 Turn 里灌字符串，别的一概
     * 不碰——这条界线是 Turn 的类注释里那段话的另一半。
     */
    private void collectFinishedTurn() {
        if (turn == null) return;

        // 正文开始吐了就把思考收起来，和网页版一致：思考是过程，答案才是要看的
        if (!liveCollapsed && turn.state() == Turn.State.ANSWERING) {
            liveExpanded = false;
            liveCollapsed = true;
        }

        if (turn.isRunning()) return;

        String answer = turn.answerText();
        String reasoning = turn.reasoningText();
        String error = turn.state() == Turn.State.ERROR
                ? turn.errorMessage().getString()
                : null;

        // 一个字都没来又没报错（玩家刚发出去就按了停止），那就当无事发生，
        // 记录里不该留一条空回复
        if (!answer.isBlank() || !reasoning.isBlank() || error != null) {
            Conversation target = turnConv == null ? conv : turnConv;
            target.add(ChatMessage.assistant(answer, reasoning, error,
                    Math.max(0, turn.thoughtSeconds())));
            ConversationStore.changed();
        }

        turn = null;
        turnConv = null;
        liveExpanded = true;
        liveCollapsed = false;
        invalidate();
    }

    //  ——— 输入 ———

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;

        if (Ui.hit(mx, my, newChatX, iconY, ICON_HIT, ICON_HIT)) {
            startNew();
            return true;
        }
        if (Ui.hit(mx, my, historyX, iconY, ICON_HIT, ICON_HIT)) {
            page.showHistory();
            return true;
        }
        if (Ui.hit(mx, my, thinkX, thinkY, thinkW, thinkH)) {
            DeepSeekConfig cfg = DeepSeekConfig.get();
            cfg.setThinking(!cfg.thinking());
            return true;
        }
        if (Ui.hit(mx, my, modelX, modelY, modelW, modelH)) {
            page.showSettings();
            return true;
        }
        if (actionW > 0 && Ui.hit(mx, my, actionX, actionY, actionW, actionH)) {
            page.showSettings();
            return true;
        }
        if (Ui.hit(mx, my, sendX, sendY, SEND_SIZE, SEND_SIZE)) {
            if (turn != null && turn.isRunning()) {
                turn.cancel();
            } else {
                send();
            }
            return true;
        }

        if (clickedInMessages(mx, my)) return true;

        if (box != null) box.mouseClicked(mx, my, button);

        // 一律吃掉：落到 MCphone 的默认处理里，"点手机外面等于关机"会把
        // 页面内的空点击也算进去
        return true;
    }

    /** 点在某条消息的「已深度思考」上了吗。位置按渲染时那套公式现算 */
    private boolean clickedInMessages(double mx, double my) {
        if (laid.isEmpty()) return false;
        if (my < viewTop || my >= viewBottom) return false;

        int viewH = viewBottom - viewTop;
        int y = contentH <= viewH ? viewTop : viewBottom - contentH + scrollPx;

        for (Laid l : laid) {
            if (l.layout().hitsToggle(mx, my, viewX, y, viewW)) {
                if (l.live()) {
                    liveExpanded = !liveExpanded;
                    // 玩家自己动过手，就别再自动收起来了
                    liveCollapsed = true;
                } else if (l.source() != null) {
                    if (!expanded.remove(l.source())) expanded.add(l.source());
                    expandRevision++;
                }
                return true;
            }
            y += l.layout().height() + MSG_GAP;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (maxScroll <= 0) return false;

        scrollPx = Math.clamp(scrollPx + (int) (amount * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {      // Enter / 小键盘 Enter
            send();
            return true;
        }
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return box != null && box.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    @Override
    public void onEnter() {
        if (box != null) box.setFocused(true);
        invalidate();
    }

    @Override
    public void onLeave() {
        // 草稿留着，输入框只是失焦。玩家去设置页填个 Key 再回来，
        // 刚打了一半的问题还该在
        if (box != null) box.setFocused(false);
        ConversationStore.save();
    }
}
