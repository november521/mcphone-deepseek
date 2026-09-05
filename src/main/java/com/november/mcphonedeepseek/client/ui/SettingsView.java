package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphonedeepseek.MCphoneDeepSeek;
import com.november.mcphonedeepseek.client.net.ModelCatalog;
import com.november.mcphonedeepseek.client.store.ConversationStore;
import com.november.mcphonedeepseek.client.store.DeepSeekConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页 —— 填 Key、选模型、几个开关。
 *
 * 为什么把设置做进手机里，而不是丢给 config 文件
 *
 * 因为第一次打开这个 App 的人，手上什么都没有。让他"退出游戏、找到 config
 * 目录、编辑一个 toml、再进游戏"，多数人就到此为止了。填 Key 这件事必须能在
 * 他此刻正看着的这块屏幕上做完。
 *
 * 一行只做一件事
 *
 * 120 像素宽放不下"标签 + 值"并排，所以文本项一律上下两行：上面是它是什么，
 * 下面是它现在是什么。点一下，下面那行原地变成输入框。
 *
 * 模型不是下拉框
 *
 * 是一个可以直接打字的文本项，底下列着服务端此刻真正提供的那几个供点选。
 * 做成写死的下拉框，等 DeepSeek 下次改模型名（它已经改过一次）就成了摆设。
 */
final class SettingsView implements View {

    private static final int PAD = 4;
    private static final int HEADER_H = 14;

    private static final int SECTION_H = 12;

    /**
     * 文本项两行：上面是名字，下面是值（或者原地变成的输入框）。
     *
     * 22 = 两行 9 像素 + 上下各 1 + 行间 1，再给编辑框留一格；画出来的
     * 高亮块 21。上一版是 18，值那一行排在 y+10、末端 y+19，而高亮块只有
     * 16 高——鼠标移上去，高亮盖不住下面那行值；进编辑态时那个圆角框更是
     * 整整戳出行外 3 像素。
     *
     * 那一格是给编辑框的：它比一行字高两像素（上下各一像素的内边距），
     * 按值那一行的位置往上挪一像素画，所以行框要比"两行字"再多一像素。
     */
    private static final int TEXT_H = 22;

    private static final int ROW_H = 14;
    private static final int PILL_H = 10;

    private static final int SCROLL_STEP = 16;

    /** 加减按钮的边长 */
    private static final int STEP_BTN = 11;

    /** 开关那条跑道的高度 */
    private static final int SWITCH_H = 8;

    private static final long ARM_TIMEOUT_MS = 3000L;

    private enum Kind { SECTION, TEXT, TOGGLE, NUMBER, PILL, DANGER, NOTE }

    private enum Editing { NONE, API_KEY, BASE_URL, MODEL }

    /**
     * 界面上的一行。
     *
     * 用一个带 kind 的记录而不是七个类：这几种行的差别只有"画成什么样"和
     * "点了做什么"，为此开一套继承体系，读起来比 switch 还费劲。
     */
    private record Item(Kind kind, String label, String value,
                        Runnable primary, Runnable minus, Runnable plus, int height) {

        static Item section(String label) {
            return new Item(Kind.SECTION, label, "", null, null, null, SECTION_H);
        }

        static Item text(String label, String value, Runnable onClick) {
            return new Item(Kind.TEXT, label, value, onClick, null, null, TEXT_H);
        }

        static Item toggle(String label, boolean on, Runnable onClick) {
            return new Item(Kind.TOGGLE, label, on ? "1" : "", onClick, null, null, ROW_H);
        }

        static Item number(String label, String value, Runnable minus, Runnable plus) {
            return new Item(Kind.NUMBER, label, value, null, minus, plus, ROW_H);
        }

        static Item pill(String value, Runnable onClick) {
            return new Item(Kind.PILL, "", value, onClick, null, null, PILL_H);
        }

        static Item danger(String label, Runnable onClick) {
            return new Item(Kind.DANGER, label, "", onClick, null, null, ROW_H);
        }

        static Item note(String label, int height) {
            return new Item(Kind.NOTE, label, "", null, null, null, height);
        }
    }

    private final DeepSeekPage page;

    private int scrollPx;
    private int maxScroll;

    private Editing editing = Editing.NONE;
    private EditBox box;

    private boolean clearArmed;
    private long armedAtMs;

    private List<Item> items = List.of();
    private int listX, listTop, listBottom, listW;

    /** 正在编辑的那一行画在哪，输入框每帧跟过去 */
    private int editRowY = Integer.MIN_VALUE;

    SettingsView(DeepSeekPage page) {
        this.page = page;
    }

    @Override
    public void render(PhoneCanvas c, Theme theme) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        if (clearArmed && System.currentTimeMillis() - armedAtMs > ARM_TIMEOUT_MS) {
            clearArmed = false;
        }

        final int x = c.x() + PAD;
        final int w = c.width() - PAD * 2;

        g.drawString(font, Component.translatable("mcphone_deepseek.ui.settings").getString(),
                x, Ui.textY(font, c.y(), HEADER_H), theme.title(), true);
        Ui.hLine(g, x, c.y() + HEADER_H - 1, w, theme.subtle() & 0x40FFFFFF);

        final int top = c.y() + HEADER_H;
        final int bottom = c.y() + c.height();

        listX = x;
        listW = w;
        listTop = top;
        listBottom = bottom;

        items = build(font, w);

        int contentH = 0;
        for (Item item : items) contentH += item.height();

        int viewH = bottom - top;
        maxScroll = Math.max(0, contentH - viewH);
        scrollPx = Math.clamp(scrollPx, 0, maxScroll);

        int y = top - scrollPx;
        editRowY = Integer.MIN_VALUE;

        g.enableScissor(x, top, x + w, bottom);
        for (Item item : items) {
            if (y + item.height() > top && y < bottom) {
                draw(c, theme, item, x, y, w);
            }
            y += item.height();
        }
        g.disableScissor();

        Ui.scrollbar(g, x + w - 1, top, viewH, scrollPx, contentH, viewH,
                theme.subtle() & 0x60FFFFFF);
    }

    //  ——— 有哪几行 ———

    private List<Item> build(Font font, int w) {
        DeepSeekConfig cfg = DeepSeekConfig.get();
        List<Item> out = new ArrayList<>();

        out.add(Item.section(text("section_access")));

        out.add(Item.text(text("api_key"),
                cfg.hasApiKey() ? cfg.maskedApiKey() : text("api_key_empty"),
                () -> beginEdit(Editing.API_KEY, cfg.apiKey())));

        out.add(Item.text(text("base_url"), cfg.baseUrl(),
                () -> beginEdit(Editing.BASE_URL, cfg.baseUrl())));

        out.add(Item.text(text("model"), cfg.model(),
                () -> beginEdit(Editing.MODEL, cfg.model())));

        // 服务端此刻真正提供的那几个。拉不到就是垫底的两个，一样能点
        for (String model : ModelCatalog.models()) {
            if (model.equals(cfg.model())) continue;
            out.add(Item.pill(model, () -> {
                cfg.setModel(model);
                cancelEdit();
            }));
        }

        out.add(Item.section(text("section_chat")));

        out.add(Item.toggle(text("thinking"), cfg.thinking(),
                () -> cfg.setThinking(!cfg.thinking())));

        out.add(Item.number(text("context"), String.valueOf(cfg.contextMessages()),
                () -> cfg.setContextMessages(cfg.contextMessages() - 2),
                () -> cfg.setContextMessages(cfg.contextMessages() + 2)));

        out.add(Item.number(text("max_tokens"),
                cfg.maxTokens() == 0 ? text("unlimited") : String.valueOf(cfg.maxTokens()),
                () -> cfg.setMaxTokens(stepTokens(cfg.maxTokens(), -512)),
                () -> cfg.setMaxTokens(stepTokens(cfg.maxTokens(), 512))));

        out.add(Item.section(text("section_data")));

        out.add(Item.danger(clearArmed ? text("clear_confirm") : text("clear"),
                this::clearAll));

        String privacy = Component.translatable("mcphone_deepseek.settings.privacy").getString();
        int lines = Math.max(1, font.split(Component.literal(privacy), w - 4).size());
        out.add(Item.note(privacy, lines * font.lineHeight + 8));

        out.add(Item.note("v" + MCphoneDeepSeek.version(), font.lineHeight + 6));

        return out;
    }

    /** 0 是「不限」，它在 512 的下面而不是 512 的上面 */
    private static int stepTokens(int current, int delta) {
        int next = current + delta;
        if (next < 512) return delta < 0 ? 0 : 512;
        return next;
    }

    private static String text(String suffix) {
        return Component.translatable("mcphone_deepseek.settings." + suffix).getString();
    }

    //  ——— 画一行 ———

    private void draw(PhoneCanvas c, Theme theme, Item item, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        switch (item.kind()) {
            case SECTION -> g.drawString(font, item.label(), x,
                    Ui.textY(font, y, SECTION_H), theme.subtle(), false);

            case TEXT -> {
                boolean hover = c.hovered(x, y, w, TEXT_H - 1);
                if (hover) Ui.roundRect(g, x - 1, y, w + 2, TEXT_H - 1, 2, theme.pressedOverlay());

                int labelY = y + 1;
                int valueY = labelY + font.lineHeight + 1;

                g.drawString(font, item.label(), x, labelY, theme.subtle(), false);

                if (isEditingRow(item)) {
                    // 输入框就摆在值原来的位置上，一个像素都不挪：进出编辑态
                    // 时那行字纹丝不动，才不会看着像抖了一下
                    editRowY = valueY;
                    drawEditBox(c, theme, x, editRowY, w);
                } else {
                    g.drawString(font, Ui.truncate(font, item.value(), w),
                            x, valueY, theme.body(), false);
                }
            }

            case TOGGLE -> {
                boolean hover = c.hovered(x, y, w, ROW_H - 1);
                if (hover) Ui.roundRect(g, x - 1, y, w + 2, ROW_H - 1, 2, theme.pressedOverlay());

                int labelY = Ui.textY(font, y, ROW_H - 1);
                g.drawString(font, item.label(), x, labelY, theme.body(), false);
                drawSwitch(g, x + w - 18, Ui.alignY(font, labelY, SWITCH_H),
                        !item.value().isEmpty(), theme);
            }

            case NUMBER -> {
                int labelY = Ui.textY(font, y, ROW_H - 1);
                g.drawString(font, item.label(), x, labelY, theme.body(), false);

                int plusX = x + w - STEP_BTN;
                int minusX = plusX - STEP_BTN - 1;

                // 加减按钮对到文字那一行上。上一版按钮写死 y+1、文字写死 y+3，
                // 两者中线差一像素，一行里三样东西没一样是齐的
                int btnY = Ui.alignY(font, labelY, STEP_BTN);
                drawStepButton(c, theme, minusX, btnY, false);
                drawStepButton(c, theme, plusX, btnY, true);

                int valueRight = minusX - 3;
                String v = item.value();
                g.drawString(font, v, valueRight - font.width(v), labelY, theme.accent(), false);
            }

            case PILL -> {
                boolean hover = c.hovered(x, y, w, PILL_H - 1);
                Ui.roundRect(g, x + 6, y, w - 6, PILL_H - 1, 3,
                        hover ? Ui.BRAND_SOFT : Ui.SUNKEN);
                g.drawString(font, Ui.truncate(font, item.value(), w - 12),
                        x + 10, Ui.controlTextY(font, y, PILL_H - 1),
                        hover ? Ui.BRAND : theme.subtle(), false);
            }

            case DANGER -> {
                boolean hover = c.hovered(x, y, w, ROW_H - 1);
                Ui.roundRect(g, x, y, w, ROW_H - 1, 2,
                        clearArmed ? Ui.ERROR_BG : (hover ? theme.pressedOverlay() : Ui.SUNKEN));
                Ui.centered(g, font, item.label(), x,
                        Ui.controlTextY(font, y, ROW_H - 1), w,
                        clearArmed ? Ui.ERROR_FG : theme.subtle());
            }

            case NOTE -> {
                // 高度是 行数×lineHeight + 8，所以上下各留 4
                int ty = y + 4;
                for (FormattedCharSequence line
                        : font.split(Component.literal(item.label()), w - 2)) {
                    g.drawString(font, line, x, ty, theme.subtle(), false);
                    ty += font.lineHeight;
                }
            }
        }
    }

    /** 开关：一个跑道加一个圆点，开着时是品牌蓝 */
    private static void drawSwitch(GuiGraphics g, int x, int y, boolean on, Theme theme) {
        int w = 18;
        Ui.roundRect(g, x, y, w, SWITCH_H, SWITCH_H / 2,
                on ? Ui.BRAND : theme.buttonDisabled());

        int knob = SWITCH_H - 2;
        Ui.circle(g, on ? x + w - knob - 1 : x + 1, y + 1, knob, Ui.ON_BRAND);
    }

    private void drawStepButton(PhoneCanvas c, Theme theme, int x, int y, boolean plus) {
        GuiGraphics g = c.graphics();

        boolean hover = c.hovered(x, y, STEP_BTN, STEP_BTN);
        Ui.roundRect(g, x, y, STEP_BTN, STEP_BTN, 2,
                hover ? theme.buttonHover() : theme.button());

        int cx = x + STEP_BTN / 2;
        int cy = y + STEP_BTN / 2;
        g.fill(cx - 3, cy, cx + 4, cy + 1, theme.body());
        if (plus) g.fill(cx, cy - 3, cx + 1, cy + 4, theme.body());
    }

    //  ——— 编辑 ———

    private boolean isEditingRow(Item item) {
        return switch (editing) {
            case NONE -> false;
            case API_KEY -> item.label().equals(text("api_key"));
            case BASE_URL -> item.label().equals(text("base_url"));
            case MODEL -> item.label().equals(text("model"));
        };
    }

    private void drawEditBox(PhoneCanvas c, Theme theme, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        Ui.roundRect(g, x - 1, y - 1, w + 2, font.lineHeight + 2, 2, Ui.SUNKEN);

        if (box == null) return;

        box.setX(x + 1);
        box.setY(y);              // 无边框 EditBox 直接拿 y 当文字顶端
        box.setWidth(w - 4 - font.width("_"));
        box.setTextColor(theme.body());
        box.render(g, c.mouseX(), c.mouseY(), c.partialTick());
    }

    private void beginEdit(Editing what, String initial) {
        commitEdit();

        editing = what;

        Font font = net.minecraft.client.Minecraft.getInstance().font;
        box = new EditBox(font, 0, 0, 10, font.lineHeight, Component.empty());
        box.setMaxLength(what == Editing.API_KEY ? 256 : 128);
        box.setBordered(false);
        box.setValue(initial);
        box.moveCursorToEnd(false);
        box.setFocused(true);
    }

    /**
     * 收下正在编辑的那一项。
     *
     * Key 存的是去掉首尾空白之后的原样。不做"看着像不像 sk- 开头"的校验——
     * 这个 App 允许指向任何 OpenAI 兼容的服务，别家的 Key 长什么样不由我们
     * 规定，拦下去只会把用别家服务的人挡在门外。真填错了，一次请求就知道了，
     * 而那条报错说得比任何格式检查都准。
     */
    private void commitEdit() {
        if (editing == Editing.NONE || box == null) {
            editing = Editing.NONE;
            box = null;
            return;
        }

        DeepSeekConfig cfg = DeepSeekConfig.get();
        String value = box.getValue();

        switch (editing) {
            case API_KEY -> {
                cfg.setApiKey(value);
                // Key 换了，能用哪些模型也就换了
                ModelCatalog.refresh();
            }
            case BASE_URL -> {
                cfg.setBaseUrl(value);
                ModelCatalog.refresh();
            }
            case MODEL -> cfg.setModel(value);
            case NONE -> { }
        }

        editing = Editing.NONE;
        box = null;
    }

    private void cancelEdit() {
        editing = Editing.NONE;
        box = null;
    }

    private void clearAll() {
        if (!clearArmed) {
            clearArmed = true;
            armedAtMs = System.currentTimeMillis();
            return;
        }
        clearArmed = false;
        ConversationStore.clear();
        page.allConversationsCleared();
    }

    //  ——— 输入 ———

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;

        Font font = net.minecraft.client.Minecraft.getInstance().font;

        // 正编辑着，点在输入框上就是移光标，点在别处就是收下
        if (editing != Editing.NONE && box != null) {
            // editRowY 是哨兵值时，说明正在编辑的那一行这一帧被滚出了视野。
            // 那就没有命中区可言，点哪儿都算"点在别处"
            if (editRowY != Integer.MIN_VALUE
                    && Ui.hit(mx, my, box.getX() - 2, editRowY - 1,
                            box.getWidth() + 6, box.getHeight() + 2)) {
                box.mouseClicked(mx, my, button);
                return true;
            }
            commitEdit();
        }

        if (my < listTop || my >= listBottom) return true;

        int y = listTop - scrollPx;
        for (Item item : items) {
            int h = item.height();

            if (my >= y && my < y + h) {
                if (item.kind() == Kind.NUMBER) {
                    int plusX = listX + listW - STEP_BTN;
                    int minusX = plusX - STEP_BTN - 1;
                    // 和 draw() 里那一行用同一个算法，否则点击区会和画出来的
                    // 按钮错开——而错开一像素是查起来最烦的一种
                    int btnY = Ui.alignY(font, Ui.textY(font, y, ROW_H - 1), STEP_BTN);

                    if (Ui.hit(mx, my, minusX, btnY, STEP_BTN, STEP_BTN)) {
                        item.minus().run();
                    } else if (Ui.hit(mx, my, plusX, btnY, STEP_BTN, STEP_BTN)) {
                        item.plus().run();
                    }
                } else if (item.primary() != null) {
                    item.primary().run();
                }
                return true;
            }
            y += h;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (maxScroll <= 0) return false;

        scrollPx = Math.clamp(scrollPx - (int) (amount * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editing == Editing.NONE || box == null) return false;

        if (keyCode == 257 || keyCode == 335) {      // Enter
            commitEdit();
            return true;
        }
        return box.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return editing != Editing.NONE && box != null && box.charTyped(codePoint, modifiers);
    }

    /**
     * 一直返回 true，哪怕这一页没在编辑。
     *
     * 因为这一页【可能】有输入框，而这个方法只在页面切换时被问一次，不是
     * 每次按键都问。返回 false 的那一刻如果玩家正要点开 Key 那一行，
     * 他打的第一个 e 就会命中背包键，手机当场关掉、刚粘贴的 Key 全丢。
     */
    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    @Override
    public void onEnter() {
        scrollPx = 0;
        clearArmed = false;
        cancelEdit();
        ModelCatalog.refreshIfStale();
    }

    @Override
    public void onLeave() {
        commitEdit();
        clearArmed = false;
    }
}
