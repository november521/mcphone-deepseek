package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphonedeepseek.client.store.Conversation;
import com.november.mcphonedeepseek.client.store.ConversationStore;

/**
 * 交给 MCphone 的那一页 —— 底下压着三个视图，它负责路由。
 *
 * 为什么是一页而不是三页
 *
 * MCphone 的 App 只交出一页：{@code IPhoneApp.openPage()} 返回什么，手机就画
 * 什么。页面之间怎么切、返回键退到哪儿，全是我们自己的事。所以这个类对外
 * 是一页，对内是一个只有三格的路由表。
 *
 * 好处是这个 App 完全长在手机屏幕里：共用状态栏、导航栏、壁纸，返回键也是
 * 手机那一个。跳出去自己开一个 Screen 也做得到，但那样它就成了"从手机里
 * 弹出来的一个窗口"，而不是手机里的一个 App。
 *
 * 页面实例是活的
 *
 * {@link com.november.mcphonedeepseek.client.DeepSeekApp} 每次都返回同一个
 * 实例，不是每次点开新建一个。所以关掉手机再打开，输入框里的草稿、滚动位置、
 * 正在跑的那次请求都还在——就像手机上的 App 切到后台再切回来。
 *
 * 一个已知的边界：正在生成时关掉手机、然后直接退出游戏，那次回复就没了。
 * 它还在网络线程上跑，但把结果写进记录这件事只有渲染线程能做（见 Turn 的
 * 类注释），而那时已经没有帧了。代价是丢一次回复，换来的是"写记录"这件事
 * 永远只有一条线程在做。
 */
public final class DeepSeekPage implements IPhonePage {

    private final ChatView chat = new ChatView(this);
    private final HistoryView history = new HistoryView(this);
    private final SettingsView settings = new SettingsView(this);

    private View current = chat;

    //  ——— 路由 ———

    void showChat() { switchTo(chat); }

    void showHistory() { switchTo(history); }

    void showSettings() { switchTo(settings); }

    void openConversation(Conversation conv) {
        chat.open(conv);
        switchTo(chat);
    }

    void conversationRemoved(Conversation conv) {
        chat.forget(conv);
    }

    void allConversationsCleared() {
        chat.reset();
        switchTo(chat);
    }

    private void switchTo(View next) {
        if (next == current) return;

        current.onLeave();
        current = next;
        current.onEnter();
    }

    //  ——— IPhonePage ———

    @Override
    public void render(PhoneCanvas canvas) {
        // 每帧取一次配色：PhoneStyle 是接口而不是常量，正是为了让我们在
        // 这里问它当下的值，而不是用编译那天的颜色
        current.render(canvas, Theme.of(canvas.style()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return current.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return current.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return current.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return current.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean capturesKeyboard() {
        return current.capturesKeyboard();
    }

    /**
     * 导航栏的返回键。
     *
     * 在别的页上时退回对话页，在对话页上返回 false 让 MCphone 退回主屏。
     * 这是玩家对返回键唯一的预期：一层一层往回退，退到头就出去。
     */
    @Override
    public boolean onBack() {
        if (current == chat) return false;

        showChat();
        return true;
    }

    @Override
    public void onOpen() {
        current.onEnter();
    }

    @Override
    public void onClose() {
        current.onLeave();
        ConversationStore.save();
    }
}
