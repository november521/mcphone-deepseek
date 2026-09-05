package com.november.mcphonedeepseek;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * MCphone 的「DeepSeek」App —— 把网页版 DeepSeek 搬进手机屏幕。
 *
 * 这个类几乎什么都不做，这是对的
 *
 * App 走的是 MCphone 的 SPI：{@code META-INF/services/} 里登记一行
 * {@link com.november.mcphonedeepseek.client.DeepSeekApp}，手机在构建 App
 * 目录时用 ServiceLoader 自己发现它。整个过程不需要我们注册任何东西，也
 * 不需要 MCphone 认识我们——这正是它那套接口开放的意义。
 *
 * 所以这里只剩两件事：拿到自己的版本号（App 详情页要显示），以及在日志里
 * 留一行"我加载了"。
 *
 * dist = Dist.CLIENT
 *
 * 本模组【只有客户端】的东西。IPhoneApp 的签名里带 GuiGraphics，实现类
 * 一旦在专用服务器上被加载就是启动即崩；HTTP 请求也是玩家自己那台机器
 * 发的，服务端不该、也没必要碰。
 *
 * 声明成 CLIENT 之后，这个 jar 被丢进服务端的 mods/ 也只是不加载，不会崩，
 * 更不会要求进服的玩家也装 —— 我们一个注册表项、一个网络包都没有。
 */
@Mod(value = MCphoneDeepSeek.MODID, dist = Dist.CLIENT)
public final class MCphoneDeepSeek {

    public static final String MODID = "mcphone_deepseek";

    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 运行时的真实版本号。App 详情页里那行版本用它，不在代码里写死字面量——
     * 写死的那个迟早会和 gradle.properties 对不上，而且没人会发现。
     */
    private static String version = "0.0.0";

    public MCphoneDeepSeek(ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        LOGGER.info("[MCphone-DeepSeek] v{} 已加载，App 会由 MCphone 的 SPI 自行发现", version);
    }

    /** 本模组版本号。模组构造之前调用会得到占位值 "0.0.0" */
    public static String version() {
        return version;
    }
}
