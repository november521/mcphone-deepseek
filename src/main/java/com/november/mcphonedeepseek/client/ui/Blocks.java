package com.november.mcphonedeepseek.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 排好版、可以直接画的一块东西。
 *
 * 为什么排版和绘制要分开
 *
 * 滚动是按像素算的，所以在画之前就得知道"这一整段有多高"。而算高度和画
 * 内容如果各写一遍，两边迟早对不上——症状是滚到底还差半行，或者滚动条的
 * 长度和实际内容不成比例。分开之后高度只有一个来源：{@link Piece#height}。
 *
 * 颜色在排版时就烤进去了
 *
 * 不是画的时候现取。因为一段文字的颜色可能来自 Markdown 的行内样式（代码
 * 是一个色、链接是另一个），那些在解析时就定了。既然一部分躲不掉，索性
 * 全部统一在这一层定死，画的时候不做任何判断。
 *
 * 代价是换手机主题要重排一次。这由调用方的缓存键负责（见 ChatView 里那个
 * styleSignature），主题一年换不了几次，比每帧多判断几十次划算。
 */
final class Blocks {

    private Blocks() {}

    /** 代码块上下各留这么多像素的内边距 */
    private static final int CODE_PAD_Y = 2;

    /** 代码块左右各留这么多 */
    private static final int CODE_PAD_X = 3;

    /** 引用块那道竖杠和文字之间的距离 */
    private static final int QUOTE_GAP = 4;

    /** 列表每缩进一级多让出多少像素 */
    static final int INDENT_STEP = 6;

    /** 空行占多高。不给整行——那样列表之间会散得很难看 */
    private static final int BLANK_HEIGHT = 4;

    interface Piece {
        int height(Font font);

        void draw(GuiGraphics g, Font font, int x, int y, int width);

        /** 这一块最后一行的文字右端在哪（相对 x）。流式光标要跟在它后面 */
        default int endX(Font font) { return 0; }

        /** 最后一行的顶在哪（相对 y） */
        default int endY(Font font) { return 0; }
    }

    /**
     * 一段文字，可能带个记号（列表的圆点或序号）。
     *
     * @param textDx 文字从这里开始画。记号占的宽度已经算进去了，所以 lines
     *               也是按 {@code width - textDx} 折过行的
     */
    record TextRun(List<FormattedCharSequence> lines, int textDx,
                   String marker, int markerColor) implements Piece {

        @Override
        public int height(Font font) {
            return Math.max(1, lines.size()) * font.lineHeight;
        }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            if (!marker.isEmpty()) {
                g.drawString(font, marker, x + textDx - font.width(marker) - 2, y, markerColor, false);
            }
            int ly = y;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + textDx, ly, 0xFFFFFFFF, false);
                ly += font.lineHeight;
            }
        }

        @Override
        public int endX(Font font) {
            return lines.isEmpty() ? textDx : textDx + font.width(lines.get(lines.size() - 1));
        }

        @Override
        public int endY(Font font) {
            return Math.max(0, lines.size() - 1) * font.lineHeight;
        }
    }

    /** 引用块：左边一道竖杠，文字整体右移 */
    record QuoteRun(List<FormattedCharSequence> lines, int ruleColor) implements Piece {

        @Override
        public int height(Font font) {
            return Math.max(1, lines.size()) * font.lineHeight;
        }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            Ui.vLine(g, x, y, height(font), ruleColor);

            int ly = y;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + QUOTE_GAP, ly, 0xFFFFFFFF, false);
                ly += font.lineHeight;
            }
        }
    }

    /**
     * 围栏代码块：一块底 + 等宽排的几行。
     *
     * 语言名不单独画一行。网页版有地方摆它，这里没有——一行 9 像素，用来
     * 写 "python" 不如用来多显示一行代码。
     */
    record CodeBox(List<FormattedCharSequence> lines, int background) implements Piece {

        @Override
        public int height(Font font) {
            return Math.max(1, lines.size()) * font.lineHeight + CODE_PAD_Y * 2;
        }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            Ui.roundRect(g, x, y, width, height(font), 2, background);

            int ly = y + CODE_PAD_Y;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + CODE_PAD_X, ly, 0xFFFFFFFF, false);
                ly += font.lineHeight;
            }
        }

        // 回复正好停在代码块里时，流式光标要跟在最后一行代码后面而不是
        // 跳回块的左上角
        @Override
        public int endX(Font font) {
            return lines.isEmpty()
                    ? CODE_PAD_X
                    : CODE_PAD_X + font.width(lines.get(lines.size() - 1));
        }

        @Override
        public int endY(Font font) {
            return CODE_PAD_Y + Math.max(0, lines.size() - 1) * font.lineHeight;
        }
    }

    /**
     * 用户那一条：右对齐的圆角气泡。
     *
     * 网页版里用户的话是唯一有气泡的东西，助手的回答是直接铺在页面上的纯
     * 文字。照抄这一点很要紧——两边都套气泡会让整页看着像两个人在吵架，而
     * 且在 120 像素宽里，助手那条本来就该用满整行。
     */
    record Bubble(List<FormattedCharSequence> lines, int bubbleWidth,
                  int background, int textColor) implements Piece {

        static final int PAD_X = 3;
        static final int PAD_Y = 2;

        @Override
        public int height(Font font) {
            return Math.max(1, lines.size()) * font.lineHeight + PAD_Y * 2;
        }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            int bx = x + width - bubbleWidth;
            Ui.roundRect(g, bx, y, bubbleWidth, height(font), 3, background);

            int ly = y + PAD_Y;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, bx + PAD_X, ly, textColor, false);
                ly += font.lineHeight;
            }
        }
    }

    /**
     * 「已深度思考（用时 N 秒）」那一行，点一下收起或展开。
     *
     * 后面那个小三角是展开状态的唯一提示。网页版有足够的地方写"点击展开"，
     * 这里没有，所以三角的方向必须一眼看得懂：▾ 是开着的，▸ 是收着的。
     */
    record ReasonHeader(String text, int color, boolean expanded) implements Piece {

        @Override
        public int height(Font font) { return font.lineHeight + 2; }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            String arrow = expanded ? "▾" : "▸";
            g.drawString(font, text, x, y + 1, color, false);
            g.drawString(font, arrow, x + font.width(text) + 3, y + 1, color, false);
        }
    }

    /** 一块带底色的提示，出错时那一条走它 */
    record Notice(List<FormattedCharSequence> lines, int background, int textColor)
            implements Piece {

        @Override
        public int height(Font font) {
            return Math.max(1, lines.size()) * font.lineHeight + 4;
        }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            Ui.roundRect(g, x, y, width, height(font), 2, background);

            int ly = y + 2;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + 3, ly, textColor, false);
                ly += font.lineHeight;
            }
        }
    }

    record Divider(int color) implements Piece {

        @Override
        public int height(Font font) { return 5; }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {
            Ui.hLine(g, x, y + 2, width, color);
        }
    }

    record Gap(int px) implements Piece {

        @Override
        public int height(Font font) { return px; }

        @Override
        public void draw(GuiGraphics g, Font font, int x, int y, int width) {}
    }

    /**
     * 把一段 Markdown 排成若干块。
     *
     * @param width    可用宽度
     * @param codeBg   代码块的底色
     */
    static List<Piece> fromMarkdown(Font font, int width, String text,
                                    Md.Colors colors, int codeBg) {
        List<Piece> out = new ArrayList<>();

        for (Md.Block block : Md.parse(text, colors)) {
            switch (block) {
                case Md.Blank ignored -> {
                    // 开头的空行不要：那会让每条回复都从半空里开始
                    if (!out.isEmpty()) out.add(new Gap(BLANK_HEIGHT));
                }

                case Md.Rule ignored -> out.add(new Divider(colors.quote()));

                case Md.Code code -> {
                    List<FormattedCharSequence> lines = new ArrayList<>();
                    int inner = Math.max(8, width - CODE_PAD_X * 2);

                    for (String raw : code.lines()) {
                        // 制表符按四空格摊开：原样交给字体的话宽度是 0，
                        // 缩进全糊在一起
                        String line = raw.replace("\t", "    ");
                        lines.addAll(font.split(
                                Component.literal(line).withStyle(s -> s.withColor(colors.code() & 0xFFFFFF)),
                                inner));
                    }
                    out.add(new CodeBox(lines, codeBg));
                }

                case Md.Para para -> {
                    if (para.kind() == Md.Kind.QUOTE) {
                        out.add(new QuoteRun(
                                font.split(para.body(), Math.max(8, width - QUOTE_GAP)),
                                colors.quote()));
                    } else {
                        int dx = para.indent() * INDENT_STEP;
                        String marker = para.marker();
                        if (!marker.isEmpty()) dx += font.width(marker) + 2;

                        out.add(new TextRun(
                                font.split(para.body(), Math.max(8, width - dx)),
                                dx, marker, colors.body()));
                    }
                }
            }
        }
        return out;
    }

    static int totalHeight(Font font, List<Piece> pieces) {
        int h = 0;
        for (Piece p : pieces) h += p.height(font);
        return h;
    }
}
