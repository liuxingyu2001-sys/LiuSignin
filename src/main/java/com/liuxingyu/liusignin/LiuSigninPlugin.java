package com.liuxingyu.liusignin;

import com.liuxingyu.liulib.LiuLib;
import com.liuxingyu.liulib.api.LiuLibApi;
import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.ui.SparrowUi;
import com.liuxingyu.liusignin.command.SigninCommand;
import com.liuxingyu.liusignin.config.SigninConfig;
import com.liuxingyu.liusignin.data.SigninStore;
import com.liuxingyu.liusignin.hook.PlaceholderHook;
import com.liuxingyu.liusignin.hook.VaultHook;
import com.liuxingyu.liusignin.listener.PlayerListener;
import com.liuxingyu.liusignin.menu.MailMenu;
import com.liuxingyu.liusignin.menu.MakeupMenu;
import com.liuxingyu.liusignin.menu.MenuConfig;
import com.liuxingyu.liusignin.menu.SigninMenu;
import com.liuxingyu.liusignin.reward.RewardTable;
import com.liuxingyu.liusignin.service.MailboxService;
import com.liuxingyu.liusignin.service.RewardService;
import com.liuxingyu.liusignin.service.SigninService;
import com.liuxingyu.liusignin.sync.CrossServerSync;
import com.liuxingyu.liusignin.time.SigninTime;
import com.liuxingyu.liusignin.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * LiuSignin 主类：加载配置、组装模块、注册命令与监听器。
 */
public final class LiuSigninPlugin extends JavaPlugin {

    private volatile SigninConfig config = SigninConfig.defaults();
    private volatile SigninTime time = new SigninTime(config.timezone(), config.resetTime());
    private volatile Messages messages;
    private volatile RewardTable rewards;
    private volatile MenuConfig menus;

    private volatile SigninMenu signinMenu;
    private volatile MakeupMenu makeupMenu;
    private volatile MailMenu mailMenu;

    private volatile SigninStore store;
    private volatile SigninService service;
    private volatile RewardService rewardService;
    private volatile MailboxService mailbox;
    private volatile VaultHook vault;
    private volatile CrossServerSync sync;
    private volatile SigninCommand command;

    private volatile ItemService items;
    private volatile SparrowUi sparrow;
    private final String serverId = UUID.randomUUID().toString();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("rewards.yml");
        saveIfMissing("menus.yml");
        saveIfMissing("messages.yml");

        try {
            LiuLibApi api = LiuLib.api();
            this.items = api.items();
            this.sparrow = api.sparrow();

            MySql mysql = api.mysql();
            if (mysql == null || !mysql.isReady()) {
                getLogger().severe("LiuLib 的 MySQL 未启用或连接失败，请在 LiuLib/config.yml 配置 mysql 后重启");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            this.config = SigninConfig.load(getConfig());
            this.time = new SigninTime(config.timezone(), config.resetTime());
            this.messages = Messages.load(file("messages.yml"), items, getLogger());
            this.rewards = RewardTable.load(
                    YamlConfiguration.loadConfiguration(file("rewards.yml")),
                    config.fallbackRewards(), getLogger());
            this.menus = MenuConfig.load(YamlConfiguration.loadConfiguration(file("menus.yml")));

            this.store = new SigninStore(mysql, getLogger(), config.tablePrefix());
            this.store.init();

            this.vault = new VaultHook();
            this.service = new SigninService(this);
            this.mailbox = new MailboxService(this, items, vault);
            this.rewardService = new RewardService(this, items, vault);
            this.signinMenu = new SigninMenu(this);
            this.makeupMenu = new MakeupMenu(this);
            this.mailMenu = new MailMenu(this);

            this.command = new SigninCommand(this);
            PluginCommand pluginCommand = getCommand("signin");
            if (pluginCommand != null) {
                pluginCommand.setExecutor(command);
                pluginCommand.setTabCompleter(command);
            } else {
                getLogger().warning("plugin.yml 中缺少 signin 命令定义");
            }

            getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

            this.sync = new CrossServerSync(api.redis(), getLogger(), service::invalidate);
            this.sync.start();

            PlaceholderHook.register(this);

            // 每 60 秒刷新一次“今日签到人数”缓存
            getServer().getAsyncScheduler().runAtFixedRate(this,
                    task -> service.refreshTodayCount(), 60L, 60L, TimeUnit.SECONDS);

            getLogger().info("LiuSignin 已启用：MySQL=" + mysql.config().host() + "/"
                    + mysql.config().database()
                    + " 表前缀=" + config.tablePrefix()
                    + " 时区=" + config.timezone()
                    + " 重置=" + config.resetTime()
                    + " 奖励组=" + rewards.groups().size()
                    + " Sparrow=" + (sparrow.available() ? "可用" : "不可用")
                    + " Vault=" + (vault.available() ? "可用" : "未安装")
                    + " Redis=" + (sync.started() ? "已连接" : "未启用"));
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "LiuSignin 启动失败", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (sync != null) {
            sync.stop();
        }
        getLogger().info("LiuSignin 已停用");
    }

    /** 重载 config.yml / rewards.yml / menus.yml / messages.yml 并清空缓存。 */
    public void reloadEverything() {
        reloadConfig();
        SigninConfig fresh = SigninConfig.load(getConfig());
        this.config = fresh;
        this.time = new SigninTime(fresh.timezone(), fresh.resetTime());
        this.messages = Messages.load(file("messages.yml"), items, getLogger());
        this.rewards = RewardTable.load(
                YamlConfiguration.loadConfiguration(file("rewards.yml")),
                fresh.fallbackRewards(), getLogger());
        this.menus = MenuConfig.load(YamlConfiguration.loadConfiguration(file("menus.yml")));
        if (store != null) {
            store.prefix(fresh.tablePrefix());
            store.init();
        }
        if (service != null) {
            service.clearCaches();
        }
    }

    private void saveIfMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private File file(String name) {
        return new File(getDataFolder(), name);
    }

    // ==================================================================
    // 模块访问
    // ==================================================================

    public SigninConfig config() {
        return config;
    }

    public SigninTime time() {
        return time;
    }

    public Messages messages() {
        return messages;
    }

    public RewardTable rewards() {
        return rewards;
    }

    public MenuConfig menus() {
        return menus;
    }

    public SigninStore store() {
        return store;
    }

    public SigninService service() {
        return service;
    }

    public RewardService rewardService() {
        return rewardService;
    }

    public MailboxService mailbox() {
        return mailbox;
    }

    public VaultHook vault() {
        return vault;
    }

    public CrossServerSync sync() {
        return sync;
    }

    public SigninCommand command() {
        return command;
    }

    public SigninMenu signinMenu() {
        return signinMenu;
    }

    public MakeupMenu makeupMenu() {
        return makeupMenu;
    }

    public MailMenu mailMenu() {
        return mailMenu;
    }

    public ItemService items() {
        return items;
    }

    public SparrowUi sparrow() {
        return sparrow;
    }

    public String serverId() {
        return serverId;
    }
}
