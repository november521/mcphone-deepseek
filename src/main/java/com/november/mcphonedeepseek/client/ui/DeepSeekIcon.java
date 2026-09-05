package com.november.mcphonedeepseek.client.ui;

import com.november.mcphonedeepseek.MCphoneDeepSeek;
import net.minecraft.resources.ResourceLocation;

/**
 * App 图标的路径，一处定义。
 *
 * 两个地方要用它：手机主屏那一格（{@code IPhoneApp.getIconTexture()}），
 * 以及对话页一条消息都没有时画在正中间的那张。后者是刻意的——空页面上出现
 * 的是玩家刚点过的那个图标，从主屏到这一页有一条看得见的线。
 *
 * 20×20 的 PNG-32。这个尺寸不是随便定的：MCphone 的主屏格子就是按 20×20
 * 画的，给大了会被拉伸得发糊。
 */
public final class DeepSeekIcon {

    private DeepSeekIcon() {}

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MCphoneDeepSeek.MODID, "textures/app/deepseek.png");
}
