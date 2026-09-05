import com.november.mcphonedeepseek.client.ui.Md;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把 Markdown 解析对着一堆真实会遇到的写法跑一遍。
 *
 * 一半的用例是"不该发生什么"：3 * 4 * 5 不该变斜体、__init__ 不该被吃掉。
 * 模型的输出里代码含量很高，这些才是会天天撞上的。
 *
 * 跑法见 docs/run-tests.sh。
 */
public final class MdTest {

    static int failures = 0;

    /** 颜色随便给，测的是结构不是配色 */
    static final Md.Colors C = new Md.Colors(0xFFCCCCCC, 0xFFFFFFFF, 0xFF66D9EF, 0xFF4D6BFE, 0xFF888888);

    record Run(String text, boolean bold, boolean italic, boolean strike, int color) {}

    public static void main(String[] args) {
        heading();
        lists();
        fences();
        inlineStyles();
        theTrapsWeDodged();
        tables();
        quotesAndRules();

        System.out.println(failures == 0 ? "\n全部通过" : "\n有 " + failures + " 项没过");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void heading() {
        List<Md.Block> b = Md.parse("## 两步走", C);
        check("标题：一块", b.size() == 1, b.size());
        check("标题：kind 是 HEADING",
                b.get(0) instanceof Md.Para p && p.kind() == Md.Kind.HEADING, b.get(0));
        check("标题：井号不进正文", plain(b.get(0)).equals("两步走"), plain(b.get(0)));
        check("标题：是粗的", runs(b.get(0)).get(0).bold(), runs(b.get(0)));
    }

    static void lists() {
        List<Md.Block> b = Md.parse("- 甲\n- 乙\n\n1. 一\n2) 二", C);
        List<Md.Para> paras = paras(b);

        check("列表：四条", paras.size() == 4, paras.size());
        check("无序：记号是圆点", paras.get(0).marker().equals("•"), paras.get(0).marker());
        check("无序：kind 对", paras.get(0).kind() == Md.Kind.BULLET, paras.get(0).kind());
        check("有序：记号带序号", paras.get(2).marker().equals("1."), paras.get(2).marker());
        check("有序：右括号写法也认", paras.get(3).marker().equals("2."), paras.get(3).marker());

        List<Md.Block> nested = Md.parse("- 甲\n    - 乙", C);
        check("列表：缩进算出层级",
                paras(nested).get(1).indent() > paras(nested).get(0).indent(),
                paras(nested).get(1).indent());
    }

    static void fences() {
        List<Md.Block> b = Md.parse("说明：\n```java\nint a = 1;\n\tint b = 2;\n```\n完事", C);
        Md.Code code = (Md.Code) b.stream().filter(x -> x instanceof Md.Code).findFirst().orElseThrow();

        check("围栏：语言认出来了", code.lang().equals("java"), code.lang());
        check("围栏：两行代码", code.lines().size() == 2, code.lines());
        check("围栏：原样保留缩进", code.lines().get(1).equals("\tint b = 2;"), code.lines().get(1));
        check("围栏：前后的正文还在",
                b.stream().anyMatch(x -> x instanceof Md.Para p && plain(p).equals("完事")), b);

        // 流式输出时收尾的围栏还没到
        List<Md.Block> open = Md.parse("```python\nx = 1\ny = 2", C);
        check("围栏未闭合：仍当代码块",
                open.size() == 1 && open.get(0) instanceof Md.Code, open);
        check("围栏未闭合：内容不丢",
                ((Md.Code) open.get(0)).lines().equals(List.of("x = 1", "y = 2")),
                ((Md.Code) open.get(0)).lines());
    }

    static void inlineStyles() {
        List<Run> r = runs(Md.parse("先 **重点** 再 *轻声* 还有 ~~划掉~~ 和 `code`", C).get(0));

        check("粗体：认出来了", any(r, x -> x.text().equals("重点") && x.bold()), r);
        check("斜体：认出来了", any(r, x -> x.text().equals("轻声") && x.italic()), r);
        check("删除线：认出来了", any(r, x -> x.text().equals("划掉") && x.strike()), r);
        check("行内代码：换了颜色",
                any(r, x -> x.text().equals("code") && x.color() == (C.code() & 0xFFFFFF)), r);
        check("行内代码：不带粗斜",
                any(r, x -> x.text().equals("code") && !x.bold() && !x.italic()), r);

        List<Run> nested = runs(Md.parse("**外面 `里面` 外面**", C).get(0));
        check("嵌套：粗体里的代码两样都占",
                any(nested, x -> x.text().equals("里面") && x.bold()
                        && x.color() == (C.code() & 0xFFFFFF)), nested);

        List<Run> link = runs(Md.parse("见 [文档](https://example.com) 那一节", C).get(0));
        check("链接：只留文字", any(link, x -> x.text().equals("文档")), link);
        check("链接：地址不画出来", !plain(Md.parse("见 [文档](https://example.com)", C).get(0))
                .contains("example.com"), plain(Md.parse("见 [文档](https://example.com)", C).get(0)));
    }

    /** 这几条正是为了不被模型的输出坑到 */
    static void theTrapsWeDodged() {
        String math = plain(Md.parse("面积是 3 * 4 * 5 立方", C).get(0));
        check("乘号：不该变成斜体", math.equals("面积是 3 * 4 * 5 立方"), math);
        check("乘号：真的没有斜体",
                !any(runs(Md.parse("面积是 3 * 4 * 5 立方", C).get(0)), Run::italic),
                runs(Md.parse("面积是 3 * 4 * 5 立方", C).get(0)));

        String dunder = plain(Md.parse("重写 __init__ 和 __repr__ 即可", C).get(0));
        check("下划线：__init__ 原样留着", dunder.equals("重写 __init__ 和 __repr__ 即可"), dunder);

        String snake = plain(Md.parse("变量 max_retry_count 要调大", C).get(0));
        check("下划线：snake_case 不动", snake.equals("变量 max_retry_count 要调大"), snake);

        String escaped = plain(Md.parse("字面量 \\*星号\\* 就是星号", C).get(0));
        check("转义：反斜杠吃掉自己", escaped.equals("字面量 *星号* 就是星号"), escaped);

        String unpaired = plain(Md.parse("单个 * 落单时原样留着", C).get(0));
        check("落单的星号：不吞后面的文字",
                unpaired.equals("单个 * 落单时原样留着"), unpaired);
    }

    static void tables() {
        List<Md.Block> b = Md.parse("| 名称 | 说明 |\n|------|:----:|\n| a | 甲 |", C);
        List<Md.Para> paras = paras(b);

        check("表格：分隔行被扔掉", paras.size() == 2, paras.size());
        check("表格：竖线换成间隔点",
                plain(paras.get(0)).equals("名称  ·  说明"), plain(paras.get(0)));
        check("表格：数据行也压好了",
                plain(paras.get(1)).equals("a  ·  甲"), plain(paras.get(1)));
    }

    static void quotesAndRules() {
        List<Md.Block> b = Md.parse("> 引一句\n\n---\n\n正文", C);

        check("引用：kind 是 QUOTE",
                b.get(0) instanceof Md.Para p && p.kind() == Md.Kind.QUOTE, b.get(0));
        check("引用：尖括号不进正文", plain(b.get(0)).equals("引一句"), plain(b.get(0)));
        check("分割线：认出来了", b.stream().anyMatch(x -> x instanceof Md.Rule), b);
        check("空行：留了空块", b.stream().anyMatch(x -> x instanceof Md.Blank), b);
    }

    //  ——— 小工具 ———

    static List<Md.Para> paras(List<Md.Block> blocks) {
        List<Md.Para> out = new ArrayList<>();
        for (Md.Block b : blocks) if (b instanceof Md.Para p) out.add(p);
        return out;
    }

    static String plain(Md.Block block) {
        return block instanceof Md.Para p ? p.body().getString() : String.valueOf(block);
    }

    static List<Run> runs(Md.Block block) {
        List<Run> out = new ArrayList<>();
        if (!(block instanceof Md.Para p)) return out;

        p.body().visit((style, text) -> {
            out.add(new Run(text, style.isBold(), style.isItalic(), style.isStrikethrough(),
                    style.getColor() == null ? -1 : style.getColor().getValue()));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    static boolean any(List<Run> runs, java.util.function.Predicate<Run> p) {
        return runs.stream().anyMatch(p);
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
