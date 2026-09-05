package com.november.mcphonedeepseek.client.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型吐出来的 Markdown 变成一串能画的块。
 *
 * 为什么非做不可
 *
 * DeepSeek 的回答几乎每一条都带 Markdown：标题、列表、行内代码、围栏代码块。
 * 原样画出来的话，玩家看到的是满屏的 ** 和 ```，那不叫"照着网页版做"，那叫
 * 把接口返回值糊在屏幕上。这一层是"像网页版"与"能用"之间的全部差别。
 *
 * 做到哪一步为止
 *
 * 支持：标题、无序/有序列表（含缩进）、引用、分割线、围栏代码块、行内代码、
 * **粗体**、*斜体*、~~删除线~~、[链接文字](地址)，以及把表格压成一行。
 *
 * 刻意不支持 __粗体__ 和 _斜体_。理由很具体：模型写的东西里代码含量极高，
 * 而 __init__、snake_case 这类标识符会被下划线语法当场吃掉，变成一串斜体的
 * 怪东西。模型要加粗时用的几乎全是 **，为了一个几乎没人用的写法去毁掉所有
 * 带下划线的标识符，不划算。
 *
 * 链接画成带下划线的蓝字，但点不了。这是老实话：让它可点就得自己做命中
 * 判定、还要决定"在游戏里打开外部浏览器"该不该弹确认框，那是另一件事。
 * 画成链接的样子是为了让人认出"这里本来是个链接"，不是假装能点。
 *
 * 产出的是 Component 不是纯字符串
 *
 * 因为换行要交给 {@code Font.split}，而它是认 Style 的：一段粗体在折行之后
 * 仍然是粗体。自己按字符宽度切再逐段染色，等于把原版已经写好的东西重写一遍，
 * 还会在中英文混排时切错位置。
 */
public final class Md {

    private Md() {}

    /** 排版要用到的几种颜色。跟着手机主题走，所以由调用方传进来 */
    public record Colors(int body, int heading, int code, int link, int quote) {}

    public sealed interface Block permits Para, Code, Rule, Blank {}

    public enum Kind { PARAGRAPH, HEADING, BULLET, ORDERED, QUOTE }

    /**
     * 一段会自动折行的文字。
     *
     * @param marker 列表前面那个记号（"•" 或 "1."），没有就是空串
     * @param indent 缩进几级，列表套列表时用
     */
    public record Para(Kind kind, int indent, String marker, Component body) implements Block {}

    /** 围栏代码块。整块不折行地存着，画的时候再按宽度切 */
    public record Code(String lang, List<String> lines) implements Block {}

    public record Rule() implements Block {}

    /** 空行。它是有意义的：段与段之间该有一口气 */
    public record Blank() implements Block {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^([ \\t]*)[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^([ \\t]*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$");

    /** 表格的分隔行，形如 |---|:--:|。它对读的人没有信息，直接扔掉 */
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?[\\s:|-]*\\|[\\s:|-]*$");

    public static List<Block> parse(String text, Colors colors) {
        List<Block> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        String[] lines = text.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String line = raw.strip();

            // 列表要靠前导空格分层级，所以给它们留一份【只去尾】的。
            // 早先这里两用一个 strip 过的串，后果是缩进永远算成 0——嵌套列表
            // 全部拍平成一层，而这件事在界面上只表现为"层次感没了"，没人会
            // 想到是解析吃掉了空格。
            String indented = raw.stripTrailing();

            // 围栏代码块。收尾的围栏可以没有——流式输出时它本来就还没到，
            // 那时也得把已经吐出来的部分当成代码块画，否则每收一个字符
            // 整段就在"代码块"和"普通段落"之间闪一次
            if (line.startsWith("```") || line.startsWith("~~~")) {
                String fence = line.substring(0, 3);
                String lang = line.substring(3).strip();

                List<String> body = new ArrayList<>();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith(fence)) {
                    body.add(lines[i]);
                    i++;
                }
                if (i < lines.length) i++;

                out.add(new Code(lang, body));
                continue;
            }

            i++;

            if (line.isEmpty()) {
                out.add(new Blank());
                continue;
            }
            if (RULE.matcher(line).matches()) {
                out.add(new Rule());
                continue;
            }
            if (line.contains("|") && TABLE_SEP.matcher(line).matches()) {
                continue;
            }

            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                out.add(new Para(Kind.HEADING, 0, "",
                        inline(h.group(2), style(colors.heading()).withBold(true), colors)));
                continue;
            }

            Matcher q = QUOTE.matcher(line);
            if (q.matches()) {
                out.add(new Para(Kind.QUOTE, 0, "",
                        inline(q.group(1), style(colors.quote()), colors)));
                continue;
            }

            Matcher o = ORDERED.matcher(indented);
            if (o.matches()) {
                out.add(new Para(Kind.ORDERED, indentOf(o.group(1)), o.group(2) + ".",
                        inline(o.group(3), style(colors.body()), colors)));
                continue;
            }

            Matcher b = BULLET.matcher(indented);
            if (b.matches()) {
                out.add(new Para(Kind.BULLET, indentOf(b.group(1)), "•",
                        inline(b.group(2), style(colors.body()), colors)));
                continue;
            }

            out.add(new Para(Kind.PARAGRAPH, 0, "",
                    inline(tableRow(line), style(colors.body()), colors)));
        }
        return out;
    }

    /**
     * 表格的一行压成一行普通文字。
     *
     * 120 像素宽画不了表格，这一点没有商量余地。但把 | 原样留着最难看，
     * 换成间隔点之后至少读得下去，也看得出这几样是并列的。
     */
    private static String tableRow(String line) {
        if (!line.startsWith("|")) return line;

        String inner = line.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);

        String[] cells = inner.split("\\|", -1);
        if (cells.length < 2) return line;

        StringBuilder sb = new StringBuilder();
        for (String cell : cells) {
            String c = cell.strip();
            if (c.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("  ·  ");
            sb.append(c);
        }
        return sb.toString();
    }

    /** 四个空格或一个 Tab 算一级，最多认三级——再深就没地方画了 */
    private static int indentOf(String prefix) {
        int width = 0;
        for (int i = 0; i < prefix.length(); i++) {
            width += prefix.charAt(i) == '\t' ? 4 : 1;
        }
        return Math.min(3, width / 2);
    }

    private static Style style(int argb) {
        return Style.EMPTY.withColor(argb & 0xFFFFFF);
    }

    //  ——— 行内 ———

    private static Component inline(String s, Style base, Colors colors) {
        MutableComponent out = Component.empty();
        scan(s, base, out, colors);
        return out;
    }

    /**
     * 扫一段文字，遇到成对的记号就递归进去。
     *
     * 递归而不是循环嵌套，是因为 **粗体里的 `代码`** 这种套法很常见，而每层
     * 只需要在上一层的 Style 上再加一样东西——这正是递归最省事的形状。
     */
    private static void scan(String s, Style style, MutableComponent out, Colors colors) {
        StringBuilder buf = new StringBuilder();

        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);

            // 转义：\* 就是一个星号，不是斜体的开始
            if (ch == '\\' && i + 1 < s.length() && isPunctuation(s.charAt(i + 1))) {
                buf.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            // 行内代码优先级最高：`**` 在反引号里就是两个星号
            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    flush(buf, out, style);
                    out.append(Component.literal(s.substring(i + 1, end))
                            .setStyle(style.withColor(colors.code() & 0xFFFFFF)));
                    i = end + 1;
                    continue;
                }
            }

            if (s.startsWith("**", i)) {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    flush(buf, out, style);
                    scan(s.substring(i + 2, end), style.withBold(true), out, colors);
                    i = end + 2;
                    continue;
                }
            }

            if (s.startsWith("~~", i)) {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    flush(buf, out, style);
                    scan(s.substring(i + 2, end), style.withStrikethrough(true), out, colors);
                    i = end + 2;
                    continue;
                }
            }

            // 单个 * 是斜体，但只在它真的裹着东西时才算：开头那个后面不能是
            // 空格，结尾那个前面也不能是空格。少了这一条，"3 * 4 * 5" 会变成
            // 一段斜体的 " 4 "
            if (ch == '*' && i + 1 < s.length() && !Character.isWhitespace(s.charAt(i + 1))) {
                int end = closingStar(s, i + 1);
                if (end > i + 1) {
                    flush(buf, out, style);
                    scan(s.substring(i + 1, end), style.withItalic(true), out, colors);
                    i = end + 1;
                    continue;
                }
            }

            if (ch == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > 0) {
                        flush(buf, out, style);
                        scan(s.substring(i + 1, close),
                                style.withColor(colors.link() & 0xFFFFFF).withUnderlined(true),
                                out, colors);
                        i = paren + 1;
                        continue;
                    }
                }
            }

            buf.append(ch);
            i++;
        }
        flush(buf, out, style);
    }

    /** 找配对的那个星号：它前面不能是空格，自己也不能是 ** 的一半 */
    private static int closingStar(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) != '*') continue;
            if (i == from) continue;
            if (Character.isWhitespace(s.charAt(i - 1))) continue;
            return i;
        }
        return -1;
    }

    private static void flush(StringBuilder buf, MutableComponent out, Style style) {
        if (buf.isEmpty()) return;

        out.append(Component.literal(buf.toString()).setStyle(style));
        buf.setLength(0);
    }

    private static boolean isPunctuation(char c) {
        return "\\`*_{}[]()#+-.!~|>".indexOf(c) >= 0;
    }
}
