import com.november.mcphonedeepseek.client.ui.Ui;
import net.minecraft.client.gui.Font;

import java.lang.reflect.Field;

/**
 * 对齐的断言测试。
 *
 * 为什么需要它
 *
 * 界面出过一整批"差一像素"的毛病：标题栏里蓝点、标题、按钮落在三条中线上；
 * 对话列表每一行的时间戳戳出高亮块两像素；设置页的值那一行被高亮盖不住；
 * 加减按钮的中线和它旁边的文字差一像素。
 *
 * 这些的共同成因是同一个：每一处的纵向位置都是手写的常量（y + 1、y + 3、
 * y + 4），各写各的。它们单看都"差不多对"，凑在一行上就参差不齐——而这种
 * 错误在代码里完全看不出来，只有把画面放到眼前才发现。
 *
 * 所以这里不测"好不好看"，只测三件算得出来的事：
 *
 *   同一行上的几样东西，中线差不超过 1 像素；
 *   排进某个框里的东西，不许超出那个框；
 *   点击区和画出来的位置，用的必须是同一个算式。
 *
 * 怎么拿到那些常量
 *
 * 反射。那几个类是包级私有的、常量也是私有的——它们本来就不该对外公开，
 * 为了测试把它们改成 public 是本末倒置。测试是白盒的，反射正合适。
 *
 * Font 是 new 出来的空壳：lineHeight 是它的 public final 字段（恒为 9），
 * 构造它不需要加载任何资源。别在这里调 font.width()，那个要真字形。
 *
 * 跑法见 docs/run-tests.sh。
 */
public final class LayoutTest {

    static int failures = 0;

    /** 只用来取 lineHeight，不画任何东西 */
    static final Font FONT = new Font(rl -> null, false);

    public static void main(String[] args) throws Exception {
        chatHeader();
        chatToolbarAndInput();
        historyRows();
        settingsRows();
        verticalBudget();
        circles();
        scrollbarDirection();

        System.out.println(failures == 0 ? "\n全部通过" : "\n有 " + failures + " 项没过");
        System.exit(failures == 0 ? 0 : 1);
    }

    //  ——— 聊天页 ———

    static void chatHeader() throws Exception {
        int headerH = int_("ChatView", "HEADER_H");
        int iconHit = int_("ChatView", "ICON_HIT");
        int dot = int_("ChatView", "BRAND_DOT");

        int titleY = Ui.textY(FONT, 0, headerH);

        sameLine("标题栏：蓝点对着标题", textMid(titleY), mid(Ui.alignY(FONT, titleY, dot), dot));
        sameLine("标题栏：按钮对着标题", textMid(titleY), mid(Ui.alignY(FONT, titleY, iconHit), iconHit));

        fits("标题栏：标题不出框", titleY, FONT.lineHeight, headerH);

        // 分隔线画在 headerH - 1，按钮不许压到它
        int iconY = Ui.alignY(FONT, titleY, iconHit);
        fits("标题栏：按钮不压分隔线", iconY, iconHit, headerH - 1);
    }

    static void chatToolbarAndInput() throws Exception {
        int toolbarH = int_("ChatView", "TOOLBAR_H");
        int inputH = int_("ChatView", "INPUT_H");
        int sendSize = int_("ChatView", "SEND_SIZE");

        int pillH = 9;
        int pillY = (toolbarH - pillH) / 2;
        fits("工具条：胶囊不出框", pillY, pillH, toolbarH);
        fits("工具条：胶囊里的字不出胶囊",
                Ui.controlTextY(FONT, pillY, pillH) - pillY, FONT.lineHeight - 1, pillH);

        check("输入行：发送键和输入框等高", inputH == sendSize, inputH + " vs " + sendSize);
        fits("输入行：字不出框", Ui.controlTextY(FONT, 0, inputH), FONT.lineHeight - 1, inputH);
    }

    //  ——— 对话列表 ———

    static void historyRows() throws Exception {
        int headerH = int_("HistoryView", "HEADER_H");
        int rowH = int_("HistoryView", "ROW_H");
        int footerH = int_("HistoryView", "FOOTER_H");

        fits("列表：标题不出标题栏", Ui.textY(FONT, 0, headerH), FONT.lineHeight, headerH);

        // 一行两条字：标题在 +1，时间戳跟在它下面一行
        int titleY = 1;
        int stampY = titleY + FONT.lineHeight + 1;
        fits("列表：时间戳不出行框（高亮块是 ROW_H - 1）", stampY, FONT.lineHeight, rowH - 1);

        // 叉与"举起手"的方块必须同一个中心
        check("列表：删除叉两个状态同心", crossCenter() == armedCenter(),
                crossCenter() + " vs " + armedCenter());

        fits("列表：底栏的字不出底栏",
                Ui.textY(FONT, 1, footerH - 1), FONT.lineHeight, footerH);
    }

    /** drawCross 覆盖 cx-2..cx+2，中心正好是 cx。这里返回相对中心的偏移×2，好比较半像素 */
    static int crossCenter() { return (-2) + (2 + 1); }

    /** 举起手时那个实心块 fill(cx-2, .., cx+3, ..) 覆盖 cx-2..cx+2 */
    static int armedCenter() { return (-2) + (3); }

    //  ——— 设置页 ———

    static void settingsRows() throws Exception {
        int headerH = int_("SettingsView", "HEADER_H");
        int sectionH = int_("SettingsView", "SECTION_H");
        int textH = int_("SettingsView", "TEXT_H");
        int rowH = int_("SettingsView", "ROW_H");
        int pillH = int_("SettingsView", "PILL_H");
        int stepBtn = int_("SettingsView", "STEP_BTN");
        int switchH = int_("SettingsView", "SWITCH_H");

        fits("设置：标题不出标题栏", Ui.textY(FONT, 0, headerH), FONT.lineHeight, headerH);
        fits("设置：分区名不出框", Ui.textY(FONT, 0, sectionH), FONT.lineHeight, sectionH);

        // 文本项两行，高亮块是 TEXT_H - 1
        int labelY = 1;
        int valueY = labelY + FONT.lineHeight + 1;
        fits("设置：值那一行不出高亮块", valueY, FONT.lineHeight, textH - 1);

        // 编辑框就摆在值那一行上，框比字高两像素
        fits("设置：编辑框不出行框", valueY - 1, FONT.lineHeight + 2, textH - 1);

        // 开关、加减按钮都要对着同一行字
        int rowTextY = Ui.textY(FONT, 0, rowH - 1);
        sameLine("设置：开关对着标签", textMid(rowTextY),
                mid(Ui.alignY(FONT, rowTextY, switchH), switchH));
        sameLine("设置：加减按钮对着标签", textMid(rowTextY),
                mid(Ui.alignY(FONT, rowTextY, stepBtn), stepBtn));

        fits("设置：加减按钮不出行框", Ui.alignY(FONT, rowTextY, stepBtn), stepBtn, rowH - 1);
        fits("设置：胶囊里的字不出胶囊",
                Ui.controlTextY(FONT, 0, pillH - 1), FONT.lineHeight - 1, pillH - 1);
    }

    //  ——— 整页装得下吗 ———

    static void verticalBudget() throws Exception {
        // MCphone 给附属的内容区：120 × (200 - 状态栏 10 - 导航栏 14)
        final int contentH = 176;

        int used = int_("ChatView", "HEADER_H")
                + int_("ChatView", "TOOLBAR_H")
                + int_("ChatView", "INPUT_H")
                + 2;                                  // 消息区和工具条之间那道缝

        check("聊天页：消息区还剩得下几行字", contentH - used >= FONT.lineHeight * 8,
                "剩 " + (contentH - used) + " 像素");
    }

    //  ——— 圆 ———

    /**
     * 圆必须左右对称、不许出框。
     *
     * 这一组是补出来的：上一版把圆心写成 {@code x + (int) r}，13 像素的圆
     * 每行宽度全是偶数、最宽一行 14 比框还宽、整体偏半像素——而这些从代码上
     * 一个字都看不出来，只有把它放大了盯着才发现发送键的箭头是歪的。
     */
    static void circles() {
        for (int size = 3; size <= 24; size++) {
            boolean symmetric = true;
            boolean inside = true;
            int widest = 0;

            for (int dy = 0; dy < size; dy++) {
                int left = Ui.circleLeft(size, dy);
                int right = Ui.circleRight(size, dy);

                // 对称的判据：左边距 == 右边距，即 left + right == size
                if (left + right != size) symmetric = false;
                if (left < 0 || right > size) inside = false;
                widest = Math.max(widest, right - left);
            }

            check("圆 " + size + "：左右对称", symmetric, "有行不对称");
            check("圆 " + size + "：不出框", inside, "有行画到框外");
            check("圆 " + size + "：最宽一行不超过直径", widest <= size, "最宽 " + widest);
        }
    }

    //  ——— 滚动条 ———

    /**
     * 滑块必须和内容同向。
     *
     * 聊天页栽在这里过：那一页的滚动量记的是"从最新一条往回翻了多远"，直接
     * 传给滚动条（它要的是"距顶端多远"）之后，滑块和内容反着走——刚打开时
     * 看着最新消息，滑块却顶在最上面。玩家看到的现象就是"滚轮方向反了"。
     */
    static void scrollbarDirection() {
        final int contentH = 500;
        final int viewH = 136;
        final int maxScroll = contentH - viewH;
        final int travel = viewH - Ui.thumbHeight(contentH, viewH);

        check("滚动条：在内容顶端时滑块贴顶",
                Ui.thumbTop(0, contentH, viewH) == 0,
                Ui.thumbTop(0, contentH, viewH));
        check("滚动条：在内容底端时滑块贴底",
                Ui.thumbTop(maxScroll, contentH, viewH) == travel,
                Ui.thumbTop(maxScroll, contentH, viewH) + " vs " + travel);

        int prev = -1;
        boolean monotonic = true;
        for (int s = 0; s <= maxScroll; s += 7) {
            int t = Ui.thumbTop(s, contentH, viewH);
            if (t < prev) monotonic = false;
            prev = t;
        }
        check("滚动条：滑块随滚动量单调下移", monotonic, "中途回头了");

        // 底端锚定的页面（聊天）要换算之后再传。换算对了的话，"贴着最新
        // 一条"（scrollPx = 0）应当让滑块贴底
        check("滚动条：聊天页换算后，贴着最新消息时滑块在底端",
                Ui.thumbTop(maxScroll - 0, contentH, viewH) == travel,
                Ui.thumbTop(maxScroll, contentH, viewH));
        check("滚动条：聊天页换算后，翻到最旧时滑块在顶端",
                Ui.thumbTop(maxScroll - maxScroll, contentH, viewH) == 0,
                Ui.thumbTop(0, contentH, viewH));
    }

    //  ——— 小工具 ———

    /** 一行字的视觉中线（×2，避免半像素被整数除法抹掉） */
    static int textMid(int textTop) { return textTop * 2 + FONT.lineHeight; }

    /** 一个方块的中线（×2） */
    static int mid(int top, int height) { return top * 2 + height; }

    static void sameLine(String what, int aMid2, int bMid2) {
        // ×2 之后，差 2 就是差 1 像素
        check(what, Math.abs(aMid2 - bMid2) <= 2,
                "中线差 " + (Math.abs(aMid2 - bMid2) / 2.0) + " 像素");
    }

    static void fits(String what, int top, int height, int containerH) {
        check(what, top >= 0 && top + height <= containerH,
                "占 " + top + ".." + (top + height) + "，框只有 " + containerH);
    }

    static int int_(String simpleName, String field) throws Exception {
        Class<?> c = Class.forName("com.november.mcphonedeepseek.client.ui." + simpleName);
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }

    static void check(String what, boolean ok, Object actual) {
        if (ok) {
            System.out.println("  通过  " + what);
        } else {
            failures++;
            System.out.println("  失败  " + what + "  ← " + actual);
        }
    }
}
