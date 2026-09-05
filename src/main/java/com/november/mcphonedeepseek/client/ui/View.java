package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;

/**
 * App 里的一页 —— 聊天、对话列表、设置各是一个。
 *
 * 为什么不让这三个各自实现 IPhonePage
 *
 * 因为 MCphone 那边一个 App 只交出【一页】：{@code openPage()} 返回什么，
 * 手机就画什么，页面之间怎么切是我们自己的事。所以外面只有一个
 * {@link DeepSeekPage}，它拿着这三页，负责路由和转发。
 *
 * 这一层长得像 IPhonePage 但不是它：签名里多一个 {@link Theme}（每帧算一次，
 * 三页共用，不必各取一遍），少了 onBack 之外的生命周期噪声。
 */
interface View {

    void render(PhoneCanvas canvas, Theme theme);

    default boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }

    default boolean mouseScrolled(double mouseX, double mouseY, double amount) { return false; }

    default boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }

    default boolean charTyped(char codePoint, int modifiers) { return false; }

    /** 这一页有没有输入框。有就得返回 true，否则玩家打拼音按到 e 会关掉手机 */
    default boolean capturesKeyboard() { return false; }

    default void onEnter() {}

    default void onLeave() {}
}
