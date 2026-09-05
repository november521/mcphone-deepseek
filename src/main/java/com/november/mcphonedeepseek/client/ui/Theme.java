package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.PhoneStyle;

/**
 * 这一帧要用的全部颜色 —— 手机主题的那几个，加上 DeepSeek 自己的品牌色。
 *
 * 为什么要复制一份出来，而不是一路把 PhoneStyle 传下去
 *
 * 两个理由。一是排版层（{@link Blocks}）要把颜色烤进排好的块里，那一步发生
 * 在绘制之前，手里不该还攥着一个每帧新建的上下文对象。二是缓存：界面靠
 * {@link #signature()} 判断"主题变了没有"，而接口本身没法比较——每次拿到的
 * 都是同一个实例，比引用永远相等。
 *
 * PhoneStyle 的类注释解释了它为什么是接口而不是一堆常量：常量会被内联进
 * 附属的 class 文件，手机换了配色我们还画着编译那天的颜色。这里每帧现取一次
 * 值，正是它想要的用法——复制的是【这一帧的值】，不是编译期的字面量。
 */
record Theme(int title, int body, int subtle, int accent, int pressedOverlay,
             int button, int buttonHover, int buttonDisabled, int buttonDisabledText) {

    /**
     * 没有 screenBackground。
     *
     * 屏幕底是 MCphone 自己铺的（壁纸就在那儿），我们这一页画在它上面。
     * 顺手抄一份"以防万一"的结果，是有人哪天真拿它糊一层不透明的底，
     * 把玩家挑的壁纸盖掉。
     */
    static Theme of(PhoneStyle s) {
        return new Theme(
                s.titleColor(), s.bodyColor(), s.subtleColor(), s.accentColor(),
                s.pressedOverlay(),
                s.buttonColor(), s.buttonHoverColor(),
                s.buttonDisabledColor(), s.buttonDisabledTextColor());
    }

    /**
     * 主题的指纹。界面拿它当重排版的凭据之一。
     *
     * 只取四个真正会进排版的颜色。按钮那几个只在绘制时用，变了不需要重排。
     */
    int signature() {
        return ((title * 31 + body) * 31 + subtle) * 31 + accent;
    }

    /**
     * 交给 Markdown 解析器的那几个色。
     *
     * 标题用手机的 titleColor（最亮那一档），行内代码用 accentColor，链接用
     * DeepSeek 蓝——前两个跟着手机走，最后一个不跟：链接是蓝的这件事属于
     * "网页版长什么样"，换手机主题不该把它变成别的颜色。
     */
    Md.Colors md() {
        return new Md.Colors(body, title, accent, Ui.BRAND, subtle);
    }
}
