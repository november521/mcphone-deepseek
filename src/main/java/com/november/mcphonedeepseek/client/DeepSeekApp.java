package com.november.mcphonedeepseek.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphonedeepseek.MCphoneDeepSeek;
import com.november.mcphonedeepseek.client.ui.DeepSeekIcon;
import com.november.mcphonedeepseek.client.ui.DeepSeekPage;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 手机主屏上那一格。
 *
 * 它有多薄
 *
 * 整个 App 对 MCphone 的接触面只有这个类的六个方法：叫什么、什么图标、点开
 * 给哪一页。别的一切——网络、存储、排版、界面——都在它后面，MCphone 一个字
 * 都不需要知道。这正是那套 SPI 想要的形状：附属不必被本体感知。
 *
 * 为什么不继承 PhoneApp
 *
 * 那个基类把命名空间写死成 mcphone，是给内建 App 用的，本体的文档里专门
 * 交代了别继承它。我们的 id 得在自己的命名空间下（{@code mcphone_deepseek:deepseek}），
 * 否则会和本体将来某个同名 App 撞车，而撞车的后果是"后登记的被直接丢弃"——
 * 玩家看到的现象是这个 App 凭空消失，日志里什么都没有。
 *
 * 页面是同一个实例
 *
 * {@link #openPage()} 每次返回同一个 {@link DeepSeekPage}，不是每次点开新建。
 * 于是关掉手机再打开，草稿、滚动位置、正在生成的回复都还在。理由见那个类的
 * 注释。
 *
 * 客户端专用
 *
 * 这个类实现的接口签名里有 GuiGraphics，它只能在客户端加载。放在
 * {@code .client} 包下、由 SPI 在客户端构建 App 目录时才实例化，专用服务器
 * 碰不到它——本体的 {@code api.client} 包注释把这条讲得很清楚。
 */
public final class DeepSeekApp implements IPhoneApp {

    /**
     * 命名空间是自己的 modid，路径是 deepseek。
     *
     * 这个 id 同时钉住三样东西：SPI 认的身份、玩家的安装记录、主屏上的位置。
     * 改了它等于换了一个 App——老玩家的主屏上那一格会消失，得去商店重装。
     */
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MCphoneDeepSeek.MODID, "deepseek");

    /** 懒建。玩家没点开过这个 App 就不该有页面对象，更不该有 HTTP 客户端 */
    private DeepSeekPage page;

    /** SPI 要一个公开的无参构造。写出来是为了说明它不是多余的 */
    public DeepSeekApp() {}

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone_deepseek.app.deepseek");
    }

    @Override
    public ResourceLocation getIconTexture() {
        return DeepSeekIcon.TEXTURE;
    }

    @Override
    public IPhonePage openPage() {
        if (page == null) page = new DeepSeekPage();
        return page;
    }

    /**
     * 覆盖了 {@link #openPage()} 之后这个方法不会被调到，但接口要求实现它。
     *
     * 留空而不是抛异常：万一将来在一个不认识 openPage 的旧版 MCphone 上被
     * 加载，抛异常会让玩家点一下就看见一条崩溃日志，而什么都不做只是"点了
     * 没反应"。两者都不好，但后者不会毁掉别的东西。真要跑在旧版上，
     * mods.toml 里那条 versionRange 会先把它挡下来。
     */
    @Override
    public void onPress() {}

    @Override
    public String getVersion() {
        return MCphoneDeepSeek.version();
    }

    @Override
    public String getAuthor() {
        return "november521";
    }

    /**
     * 商店详情页里那段简介。
     *
     * 先问键在不在：查不到的翻译键不会报错，只会把键名原样画在详情页上，
     * 而玩家看到一行 "mcphone_deepseek.app.deepseek.desc" 只会以为坏了。
     * 本体的 PhoneApp 也是这么处理的。
     */
    @Override
    public String getDescription() {
        String key = "mcphone_deepseek.app.deepseek.desc";
        return I18n.exists(key) ? I18n.get(key) : "";
    }
}
