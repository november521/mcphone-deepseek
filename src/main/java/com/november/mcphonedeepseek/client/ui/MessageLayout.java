package com.november.mcphonedeepseek.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 排好版的一条消息 —— 高度算得出来、画得出去、点得中。
 *
 * 一条消息由哪几块拼成
 *
 *   玩家的话     一个右对齐的蓝气泡，就这一块。
 *   DeepSeek 的  可选的「已深度思考」那一行（可点开），底下是思考正文（收起时
 *                不占地方），再往下是 Markdown 排出来的正文，出错时末尾多一块
 *                红色提示。
 *
 * 为什么这些块都是 {@link Blocks.Piece}
 *
 * 因为滚动是按像素算的，而"这一条有多高"必须只有一个来源。把气泡、思考、
 * 正文、报错都做成同一种东西之后，高度就是把它们的高度加起来，绘制就是按
 * 同样的顺序走一遍——两者不可能对不上。
 */
final class MessageLayout {

    /** 气泡最宽占内容区的多少。留出的空白让人一眼看出这是"靠右的一方" */
    private static final float BUBBLE_MAX_RATIO = 0.80f;

    /** 思考正文相对思考标题的缩进 */
    private static final int REASON_INDENT = 4;

    private final List<Blocks.Piece> pieces;
    private final int height;

    /** 思考标题在这一条里的纵向偏移；-1 表示这一条没有可点的东西 */
    private final int toggleDy;
    private final int toggleH;

    /** 流式光标该画在哪（相对这一条的左上角） */
    private final int caretDx;
    private final int caretDy;

    private MessageLayout(Font font, List<Blocks.Piece> pieces, int toggleDy, int toggleH) {
        this.pieces = pieces;
        this.height = Blocks.totalHeight(font, pieces);
        this.toggleDy = toggleDy;
        this.toggleH = toggleH;

        // 光标跟在最后一块有内容的东西后面。倒着找是因为末尾可能是个 Gap
        int dx = 0;
        int dy = 0;
        int y = 0;
        for (Blocks.Piece p : pieces) {
            int h = p.height(font);
            if (p.endX(font) > 0) {
                dx = p.endX(font);
                dy = y + p.endY(font);
            }
            y += h;
        }
        this.caretDx = dx;
        this.caretDy = dy;
    }

    //  ——— 造 ———

    /** 玩家说的话。不走 Markdown：网页版也是原样显示玩家输入的 */
    static MessageLayout user(Font font, int width, String text, Theme theme) {
        int maxBubble = Math.max(24, (int) (width * BUBBLE_MAX_RATIO));
        int textMax = Math.max(8, maxBubble - Blocks.Bubble.PAD_X * 2);

        List<FormattedCharSequence> lines =
                font.split(Component.literal(plain(text)), textMax);

        int textW = 0;
        for (FormattedCharSequence line : lines) textW = Math.max(textW, font.width(line));

        return new MessageLayout(font,
                List.of(new Blocks.Bubble(lines, textW + Blocks.Bubble.PAD_X * 2,
                        Ui.BRAND, Ui.ON_BRAND)),
                -1, 0);
    }

    /**
     * DeepSeek 说的话。
     *
     * @param reasonHeader 「已深度思考（用时 N 秒）」那一行，空串表示这条没有思考过程。
     *                     文案由调用方拼好——翻译和秒数的事不该由排版层管
     * @param expanded     思考正文是不是展开着
     * @param error        出错时给玩家看的那句话，null 表示没出错
     */
    static MessageLayout assistant(Font font, int width,
                                   String answer, String reasoning,
                                   String reasonHeader, boolean expanded,
                                   Component error, Theme theme) {

        List<Blocks.Piece> pieces = new ArrayList<>();
        int toggleDy = -1;
        int toggleH = 0;

        if (!reasonHeader.isEmpty()) {
            toggleDy = 0;
            Blocks.ReasonHeader header =
                    new Blocks.ReasonHeader(reasonHeader, theme.subtle(), expanded);
            toggleH = header.height(font);
            pieces.add(header);

            if (expanded && !reasoning.isBlank()) {
                // 思考正文不排 Markdown：它是模型的草稿，里头的 # 和 * 多半
                // 不是格式而是它正在琢磨的内容，当成格式解析只会画错
                pieces.add(new Blocks.QuoteRun(
                        font.split(Component.literal(plain(reasoning))
                                        .withStyle(s -> s.withColor(theme.subtle() & 0xFFFFFF)),
                                Math.max(8, width - REASON_INDENT)),
                        theme.subtle()));
            }
            if (!answer.isBlank() || error != null) pieces.add(new Blocks.Gap(3));
        }

        if (!answer.isBlank()) {
            pieces.addAll(Blocks.fromMarkdown(font, width, plain(answer),
                    theme.md(), Ui.SUNKEN));
        }

        if (error != null) {
            if (!pieces.isEmpty()) pieces.add(new Blocks.Gap(2));
            pieces.add(new Blocks.Notice(
                    font.split(error, Math.max(8, width - 6)), Ui.ERROR_BG, Ui.ERROR_FG));
        }

        // 一条什么都没有的回复也得占一行，否则等第一个字的那段时间里
        // 整条消息高度为 0，看着像"发出去就没了"
        if (pieces.isEmpty()) {
            pieces.add(new Blocks.Gap(font.lineHeight));
        }

        return new MessageLayout(font, List.copyOf(pieces), toggleDy, toggleH);
    }

    //  ——— 用 ———

    int height() { return height; }

    void draw(GuiGraphics g, Font font, int x, int y, int width) {
        int ly = y;
        for (Blocks.Piece p : pieces) {
            p.draw(g, font, x, ly, width);
            ly += p.height(font);
        }
    }

    /** 点在「已深度思考」那一行上了吗 */
    boolean hitsToggle(double mx, double my, int x, int y, int width) {
        return toggleDy >= 0 && Ui.hit(mx, my, x, y + toggleDy, width, toggleH);
    }

    int caretX(int x) { return x + caretDx; }

    int caretY(int y) { return y + caretDy; }

    /**
     * 把 § 换掉。
     *
     * 原版的文字管线会把 § 当成颜色码处理，于是模型回答里出现一个 §k，屏幕上
     * 就是一段乱跳的鬼画符；玩家自己打一个进去也一样。这不是安全问题，是显示
     * 问题——但它会让人以为模组坏了。
     */
    private static String plain(String s) {
        return s == null ? "" : s.replace('§', '?');
    }
}
