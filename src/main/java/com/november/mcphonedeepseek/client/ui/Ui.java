package com.november.mcphonedeepseek.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 画东西用的小零件 —— 圆角、圆、贴图、截字、滚动条。
 *
 * 为什么要自己写一份
 *
 * MCphone 内部有个 GuiUtil，这些它基本都有。但它在 core.client 下，而本体的
 * API 契约（MCphoneApi 第五条）写得很清楚：只有 api 包是给附属用的，core、
 * feature、compat、util 都是内部实现。用了它，本体哪天重构那个类，我们这边
 * 就是 NoSuchMethodError，而且是玩家先撞上。
 *
 * 所以这一份是刻意的重复。附属能碰的只有 PhoneCanvas 给的那支 GuiGraphics
 * 和 Font，剩下的自己搭——这本来就是"开放接口"的正常代价。
 *
 * 关于贴图为什么要自己开混合
 *
 * GuiGraphics 那条 blit(ResourceLocation, ...) 从头到尾不碰混合状态，而 GUI
 * 里每画完一次 fill 或一行字，收尾都会把混合关掉。于是带抗锯齿的图会画出
 * 发脏的硬边，半透明的整块图会变成实心。这一条是从 MCphone 的 GuiUtil 里学
 * 来的——它在注释里把这个坑写得很细，值得照抄这个做法（而不是照抄那段代码）。
 */
public final class Ui {

    private Ui() {}

    //  ——— DeepSeek 的颜色 ———
    //
    // 只有这几个是写死的，其余一律走 PhoneCanvas.style()。
    //
    // 分界线是这样划的：品牌蓝是"DeepSeek 这个产品长什么样"，换手机主题也
    // 不该变——变了这个 App 就不像 DeepSeek 了。而底色、正文色、次要文字色
    // 是"这部手机长什么样"，那必须跟着手机走，否则我们就是别人手机里的一块
    // 外来户。PhoneStyle 的类注释把这件事说得比这里清楚。

    /** DeepSeek 蓝。整个界面上最认得出来的那一样东西 */
    public static final int BRAND = 0xFF4D6BFE;

    /** 按下去时的深一档 */
    public static final int BRAND_PRESSED = 0xFF3D56CB;

    /** 垫在开启状态的胶囊按钮底下，半透明所以能压在任何壁纸上 */
    public static final int BRAND_SOFT = 0x334D6BFE;

    /** 用户气泡里的字。品牌蓝底上只有白字够清楚 */
    public static final int ON_BRAND = 0xFFFFFFFF;

    /** 代码块、输入框这类"凹进去"的底 */
    public static final int SUNKEN = 0x40000000;

    /** 出错时那一条的底与字 */
    public static final int ERROR_BG = 0x33E5484D;
    public static final int ERROR_FG = 0xFFFF8A8F;

    //  ——— 形状 ———

    /**
     * 圆角矩形。
     *
     * 角是 45 度切出来的，不是真圆弧：在这块屏幕上圆角半径最多 3 像素，
     * 3 个像素里画不出圆弧和斜边的区别，而斜边不需要开方。
     */
    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;

        int r = Math.clamp(radius, 0, Math.min(w, h) / 2);
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }

        g.fill(x, y + r, x + w, y + h - r, color);

        for (int i = 0; i < r; i++) {
            int inset = r - i - 1;
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /**
     * 圆。发送键那个圆按钮用它。
     *
     * 上一版是错的，而且错得不显眼：圆心写成 {@code x + (int) r}，直径 13 时
     * 6.5 被截成 6，然后左右各取 ±hw——于是每一行的宽度必然是【偶数】，
     * 13 像素的圆画出来是 6/8/10/12/14 这么一串，最宽的一行 14 比框还宽，
     * 还溢出到框外一列。整个圆偏了半像素，画在它上面的箭头自然就显得歪。
     *
     * 现在圆心是浮点的，左右两边各自四舍五入，对称是算出来的而不是凑的。
     */
    public static void circle(GuiGraphics g, int x, int y, int size, int color) {
        if (size <= 0) return;

        for (int dy = 0; dy < size; dy++) {
            int left = circleLeft(size, dy);
            int right = circleRight(size, dy);
            if (right > left) g.fill(x + left, y + dy, x + right, y + dy + 1, color);
        }
    }

    /**
     * 圆的第 dy 行从哪一列开始（相对圆的左边界）。
     *
     * 和 {@link #circleRight} 拆成两个方法而不是返回一对值，是为了能被断言
     * 测试直接调——画出来的东西对不对称，光看代码是看不出来的。
     */
    public static int circleLeft(int size, int dy) {
        return (int) Math.round(size / 2.0 - circleHalfWidth(size, dy));
    }

    /** 圆的第 dy 行到哪一列为止（右开区间） */
    public static int circleRight(int size, int dy) {
        return (int) Math.round(size / 2.0 + circleHalfWidth(size, dy));
    }

    private static double circleHalfWidth(int size, int dy) {
        double r = size / 2.0;
        double yy = dy + 0.5 - r;
        return Math.sqrt(Math.max(0, r * r - yy * yy));
    }

    /** 一条 1 像素的横线。分隔线、水平分割都走它 */
    public static void hLine(GuiGraphics g, int x, int y, int w, int color) {
        g.fill(x, y, x + w, y + 1, color);
    }

    /** 一条竖线。引用块左边那道杠 */
    public static void vLine(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 1, y + h, color);
    }

    //  ——— 贴图 ———

    /** 整张图拉伸到目标区域，带混合。所有贴图都该走这里，别直接调 g.blit */
    public static void texture(GuiGraphics g, ResourceLocation tex,
                               int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(tex, x, y, w, h, 0, 0, w, h, w, h);
        RenderSystem.disableBlend();
    }

    //  ——— 对齐 ———
    //
    // 这三个方法是这一版补上的。在它们之前，每一处的纵向位置都是手写的
    // 常量——y + 1、y + 3、y + 4——各写各的。后果是同一行上的几样东西落在
    // 几条不同的中线上：标题栏里蓝点、标题、按钮差着 1 像素，而 13 像素高
    // 的一行里 1 像素是看得出来的。
    //
    // 本体那边的写法是统一的：{@code y + (容器高 - font.lineHeight) / 2}，
    // 填色控件（按钮、输入框、标签页）再 +1。这里把那条公式变成方法，
    // 免得再有人凭手感写一个偏移。

    /** 一行字在 h 高的容器里，顶端该放在哪 */
    public static int textY(Font font, int containerY, int containerH) {
        return containerY + (containerH - font.lineHeight) / 2;
    }

    /**
     * 填色控件里的一行字。比 {@link #textY} 低一像素。
     *
     * 这一像素不是随手加的：原版字形只占满 9 像素行框的上面 8 行，按行框
     * 居中会显得偏上。本体的按钮、输入框、标签页都加了这一下，照抄。
     */
    public static int controlTextY(Font font, int containerY, int containerH) {
        return textY(font, containerY, containerH) + 1;
    }

    /**
     * 把一个 elementH 高的东西对到某一行字上。
     *
     * 参数是那行字的【顶端】而不是容器，因为一行上真正定基准的是文字：
     * 图标、开关、圆点都是围着它摆的。给容器高的话，容器高一变，文字和
     * 图标会各自朝不同方向跑。
     */
    public static int alignY(Font font, int textTop, int elementH) {
        return textTop + font.lineHeight / 2 - elementH / 2;
    }

    //  ——— 文字 ———

    /** 放不下就截断加省略号。省略号的宽度按字体真实量，不写死 */
    public static String truncate(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;

        String ellipsis = "…";
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) return "";

        return font.plainSubstrByWidth(s, room) + ellipsis;
    }

    /** 居中画一行 */
    public static void centered(GuiGraphics g, Font font, String s, int x, int y, int w, int color) {
        g.drawString(font, s, x + (w - font.width(s)) / 2, y, color, false);
    }

    //  ——— 命中判定 ———

    public static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    //  ——— 滚动条 ———

    /**
     * 右边那条细滚动条。内容不足一屏时不画。
     *
     * 只画 1 像素宽：这块屏幕一共 120 像素，滚动条每多占一像素，正文就少
     * 一像素。它的用途是告诉玩家"还有"，不是让人拖。
     *
     * <b>scrollFromTop 必须是"距内容顶端多远"</b>
     *
     * 这一条不是废话。聊天页的滚动量是反着记的——它记的是"从最新一条往回
     * 翻了多远"，因为那一页是贴着底排的。把那个数直接传进来，滑块就和内容
     * 反着走：刚打开时看着最新消息（内容在底部），滑块却顶在最上面；往回翻
     * 历史，滑块反而往下跑。玩家看到的就是"滚轮方向反了"。
     *
     * 底端锚定的页面要自己换算成 {@code maxScroll - scrollPx} 再传进来。
     */
    public static void scrollbar(GuiGraphics g, int x, int y, int h,
                                 int scrollFromTop, int contentH, int viewH, int color) {
        if (contentH <= viewH || viewH <= 0) return;

        int thumbH = thumbHeight(contentH, viewH);
        int thumbY = y + thumbTop(scrollFromTop, contentH, viewH);

        g.fill(x, thumbY, x + 1, thumbY + thumbH, color);
    }

    /** 滑块多高。太短了看不见，给个下限 */
    public static int thumbHeight(int contentH, int viewH) {
        return Math.max(6, viewH * viewH / contentH);
    }

    /** 滑块顶端离轨道顶端多远。拆出来是为了能被断言测试直接验方向 */
    public static int thumbTop(int scrollFromTop, int contentH, int viewH) {
        int maxScroll = contentH - viewH;
        if (maxScroll <= 0) return 0;

        int travel = viewH - thumbHeight(contentH, viewH);
        return travel * Math.clamp(scrollFromTop, 0, maxScroll) / maxScroll;
    }
}
