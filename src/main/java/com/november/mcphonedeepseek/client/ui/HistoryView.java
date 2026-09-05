package com.november.mcphonedeepseek.client.ui;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphonedeepseek.client.store.Conversation;
import com.november.mcphonedeepseek.client.store.ConversationStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 对话列表 —— 网页版左边那条边栏，在这块屏幕上只能是单独一页。
 *
 * 删除要点两下
 *
 * 第一下把那条标红、叉变成实心，第二下才真删。中间隔着一个超时，超时之后
 * 自动松开。理由很直接：一段对话可能是几十次往返攒出来的，删了没有回收站、
 * 没有撤销，而这一行离"打开它"只有几个像素远。多点一下的代价，比误删一次
 * 小得多。
 */
final class HistoryView implements View {

    private static final int PAD = 4;
    private static final int HEADER_H = 14;

    /**
     * 一条占多高：标题一行 + 时间一行。
     *
     * 21 = 两行 9 像素 + 上下各 1 像素留白 + 行间 1 像素。上一版是 19，
     * 实际画出来的高亮块只有 18，而两行字排到了 y+2 与 y+11，末端在 y+20
     * ——时间戳整整戳出高亮块 2 像素，正好落在下一条的地盘上。
     */
    private static final int ROW_H = 21;

    /** 底下那条「设置」的高度 */
    private static final int FOOTER_H = 14;

    private static final int SCROLL_STEP = ROW_H;

    /** 叉的点击区 */
    private static final int X_HIT = 11;

    /** 举起了删除的手，多久之后自己放下 */
    private static final long ARM_TIMEOUT_MS = 3000L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final DeepSeekPage page;

    private int scrollPx;
    private int maxScroll;

    private Conversation armed;
    private long armedAtMs;

    private int listX, listTop, listBottom, listW;
    private int footerX, footerY, footerW;

    HistoryView(DeepSeekPage page) {
        this.page = page;
    }

    @Override
    public void render(PhoneCanvas c, Theme theme) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        disarmIfStale();

        final int x = c.x() + PAD;
        final int w = c.width() - PAD * 2;

        // 头
        g.drawString(font, Component.translatable("mcphone_deepseek.ui.history").getString(),
                x, Ui.textY(font, c.y(), HEADER_H), theme.title(), true);
        Ui.hLine(g, x, c.y() + HEADER_H - 1, w, theme.subtle() & 0x40FFFFFF);

        final int top = c.y() + HEADER_H;
        final int bottom = c.y() + c.height() - FOOTER_H;

        listX = x;
        listW = w;
        listTop = top;
        listBottom = bottom;

        List<Conversation> all = listed();

        if (all.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(
                    Component.translatable("mcphone_deepseek.ui.empty_history"), w);
            int ty = top + 12;
            for (var line : lines) {
                g.drawString(font, line, x + (w - font.width(line)) / 2, ty, theme.subtle(), false);
                ty += font.lineHeight;
            }
        } else {
            drawList(c, theme, all, x, top, w, bottom);
        }

        drawFooter(c, theme, x, c.y() + c.height() - FOOTER_H, w);
    }

    private void drawList(PhoneCanvas c, Theme theme, List<Conversation> all,
                          int x, int top, int w, int bottom) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        int viewH = bottom - top;
        int contentH = all.size() * ROW_H;
        maxScroll = Math.max(0, contentH - viewH);
        scrollPx = Math.clamp(scrollPx, 0, maxScroll);

        // 这一页从顶往下排，滚动量是"往下翻了多少"——和聊天页相反，
        // 因为列表最要紧的是最新那条，它在最上面
        int y = top - scrollPx;

        g.enableScissor(x, top, x + w, bottom);
        for (Conversation conv : all) {
            if (y + ROW_H > top && y < bottom) {
                drawRow(c, theme, conv, x, y, w);
            }
            y += ROW_H;
        }
        g.disableScissor();

        Ui.scrollbar(g, x + w - 1, top, viewH, scrollPx, contentH, viewH,
                theme.subtle() & 0x60FFFFFF);
    }

    private void drawRow(PhoneCanvas c, Theme theme, Conversation conv, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        boolean isArmed = conv == armed;
        boolean hover = c.hovered(x, y, w, ROW_H - 1);

        // 高亮块朝两边各外扩一像素，文字就能和标题栏一样从 x 开始。
        // 上一版是块贴着 x、文字缩进 x+2，于是标题栏在 x、行内文字在 x+2，
        // 竖着看有一道台阶
        if (isArmed) {
            Ui.roundRect(g, x - 1, y, w + 2, ROW_H - 1, 2, Ui.ERROR_BG);
        } else if (hover) {
            Ui.roundRect(g, x - 1, y, w + 2, ROW_H - 1, 2, theme.pressedOverlay());
        }

        String fallback = Component.translatable("mcphone_deepseek.ui.untitled").getString();
        String title = conv.displayTitle(fallback);

        int titleY = y + 1;
        int stampY = titleY + font.lineHeight + 1;

        g.drawString(font, Ui.truncate(font, title, w - X_HIT - 4),
                x, titleY, isArmed ? Ui.ERROR_FG : theme.body(), false);
        g.drawString(font, stamp(conv.updatedMs()), x, stampY, theme.subtle(), false);

        // 叉。举起手时画成实心方块，让"再点一下就没了"看得出来
        int xx = x + w - X_HIT;
        boolean xHover = c.hovered(xx, y, X_HIT, ROW_H - 1);
        int cx = xx + X_HIT / 2;
        int cy = y + (ROW_H - 1) / 2;

        if (isArmed) {
            // 和叉一样是 5×5、中心在 (cx, cy)。上一版这里是 6×6，中心偏了
            // 半像素，举起手的那一下图标会跳一下
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, Ui.ERROR_FG);
        } else {
            drawCross(g, cx, cy, xHover ? theme.title() : theme.subtle());
        }
    }

    /** 一个叉，两条对角线用像素点出来 */
    private static void drawCross(GuiGraphics g, int cx, int cy, int color) {
        for (int i = -2; i <= 2; i++) {
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
        }
    }

    private void drawFooter(PhoneCanvas c, Theme theme, int x, int y, int w) {
        GuiGraphics g = c.graphics();
        Font font = c.font();

        footerX = x;
        footerY = y;
        footerW = w;

        Ui.hLine(g, x, y, w, theme.subtle() & 0x40FFFFFF);

        boolean hover = c.hovered(x, y + 1, w, FOOTER_H - 1);
        String label = Component.translatable("mcphone_deepseek.ui.settings").getString();
        g.drawString(font, label, x, Ui.textY(font, y + 1, FOOTER_H - 1),
                hover ? theme.title() : theme.subtle(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;

        if (Ui.hit(mx, my, footerX, footerY + 1, footerW, FOOTER_H - 1)) {
            page.showSettings();
            return true;
        }

        if (my >= listTop && my < listBottom) {
            List<Conversation> all = listed();
            int y = listTop - scrollPx;

            for (Conversation conv : all) {
                if (Ui.hit(mx, my, listX, y, listW, ROW_H - 1)) {
                    int xx = listX + listW - X_HIT;

                    if (Ui.hit(mx, my, xx, y, X_HIT, ROW_H - 1)) {
                        if (conv == armed) {
                            ConversationStore.remove(conv);
                            armed = null;
                            page.conversationRemoved(conv);
                        } else {
                            armed = conv;
                            armedAtMs = System.currentTimeMillis();
                        }
                    } else {
                        armed = null;
                        page.openConversation(conv);
                    }
                    return true;
                }
                y += ROW_H;
            }
        }

        armed = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (maxScroll <= 0) return false;

        scrollPx = Math.clamp(scrollPx - (int) (amount * SCROLL_STEP), 0, maxScroll);
        return true;
    }

    @Override
    public void onEnter() {
        armed = null;
        scrollPx = 0;
    }

    @Override
    public void onLeave() {
        armed = null;
    }

    /**
     * 列表里该出现哪几段。
     *
     * 空的不算。点开 App 时会先建一段还没说话的新对话，它在列表里就是一行
     * 「新对话」，点进去还是同一个地方——一条既没有信息也没有去处的记录。
     * 网页版也是等你说了第一句才把它收进边栏的。
     */
    private static List<Conversation> listed() {
        return ConversationStore.all().stream().filter(c -> !c.isEmpty()).toList();
    }

    private void disarmIfStale() {
        if (armed != null && System.currentTimeMillis() - armedAtMs > ARM_TIMEOUT_MS) {
            armed = null;
        }
    }

    /** 今天的显示时刻，更早的显示日期。120 像素里放不下完整写法 */
    private static String stamp(long ms) {
        ZoneId zone = ZoneId.systemDefault();
        var when = Instant.ofEpochMilli(ms).atZone(zone);
        return when.toLocalDate().equals(LocalDate.now(zone))
                ? when.format(TIME)
                : when.format(DATE);
    }
}
