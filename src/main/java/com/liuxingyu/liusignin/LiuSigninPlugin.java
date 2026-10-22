package com.liuxingyu.liusignin;

import com.liuxingyu.liulib.LiuLib;
import com.liuxingyu.liulib.api.LiuLibApi;
import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.ui.SparrowUi;
import com.liuxingyu.liusignin.api.LiuSigninApi;
import com.liuxingyu.liusignin.api.LiuSigninApiImpl;
import com.liuxingyu.liusignin.command.SigninCommand;
import com.liuxingyu.liusignin.config.SigninConfig;
import com.liuxingyu.liusignin.data.SigninDatabase;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * LiuSignin 主类：加载配置、组装模块、注册命令与监听器。
 */
public final class LiuSigninPlugin extends JavaPlugin {

    private static final int MENUS_VERSION = 2;
    private static final String AUTO_SHARED_CONFIG = "/mc/shared/liusignin-config";
    private static final long CONFIG_WATCH_TICKS = 40L;

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
    private volatile LiuSigninApi pluginApi;

    private volatile ItemService items;
    private volatile SparrowUi sparrow;
    private volatile File configRoot;
    private volatile YamlConfiguration runtimeConfig;
    private volatile BukkitTask configWatchTask;
    private volatile File watchedRoot;
    private volatile long watchedFingerprint;
    private volatile boolean configReloadQueued;
    private final String serverId = UUID.randomUUID().toString();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            LiuLibApi api = LiuLib.api();
            this.items = api.items();
            this.sparrow = api.sparrow();

            mergeLocalBootstrap();
            resolveConfigRoot();
            saveIfMissing("config.yml");
            saveIfMissing("rewards.yml");
            saveIfMissing("menus.yml");
            migrateMenus();
            saveIfMissing("messages.yml");
            mergeMessages();
            loadRuntimeConfig();
            this.config = SigninConfig.load(runtimeConfig);

            MySql library = api.mysql();
            if (library == null || !library.isReady()) {
                getLogger().severe("LiuLib 的 MySQL 未启用或连接失败，请在 LiuLib/config.yml 配置 mysql 后重启");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            MySql mysql = SigninDatabase.open(library.config(), config, getLogger());
            if (mysql == null) {
                getLogger().severe("无法连接本插件数据库 " + config.databaseName()
                        + "（凭据复用 LiuLib 的 mysql 段，库名/前缀见 LiuSignin/config.yml: database）");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (config.migrateFromLiulib()) {
                SigninDatabase.migrate(library, mysql, config.tablePrefix(), getLogger());
            }
            this.store = new SigninStore(mysql, getLogger(), config.tablePrefix());
            this.store.init();

            this.time = new SigninTime(config.timezone(), config.resetTime());
            this.messages = Messages.load(file("messages.yml"), items, getLogger());
            this.rewards = RewardTable.load(
                    YamlConfiguration.loadConfiguration(file("rewards.yml")),
                    config.fallbackRewards(), getLogger());
            this.menus = MenuConfig.load(YamlConfiguration.loadConfiguration(file("menus.yml")));

            this.vault = new VaultHook();
            this.service = new SigninService(this);
            this.mailbox = new MailboxService(this, items, vault);
            this.rewardService = new RewardService(this, items, vault);
            this.signinMenu = new SigninMenu(this);
            this.makeupMenu = new MakeupMenu(this);
            this.mailMenu = new MailMenu(this);

            this.pluginApi = new LiuSigninApiImpl(this);
            getServer().getServicesManager().register(
                    LiuSigninApi.class, pluginApi, this, ServicePriority.Normal);

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
            startConfigWatcher();

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
        stopConfigWatcher();
        if (sync != null) {
            sync.stop();
        }
        if (store != null) {
            store.close();
        }
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("LiuSignin 已停用");
    }

    /** 重载 config.yml / rewards.yml / menus.yml / messages.yml 并清空缓存。 */
    public synchronized void reloadEverything() {
        reloadConfig();
        mergeLocalBootstrap();
        resolveConfigRoot();
        saveIfMissing("config.yml");
        saveIfMissing("rewards.yml");
        saveIfMissing("menus.yml");
        migrateMenus();
        saveIfMissing("messages.yml");
        mergeMessages();
        loadRuntimeConfig();
        SigninConfig previous = this.config;
        SigninConfig fresh = SigninConfig.load(runtimeConfig == null ? getConfig() : runtimeConfig);
        this.config = fresh;
        if (previous != null && !previous.databaseName().equalsIgnoreCase(fresh.databaseName())) {
            getLogger().warning("database.name 已改为 " + fresh.databaseName()
                    + "，需要重启服务器才会切换数据库（当前仍连接旧库）");
        }
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
        startConfigWatcher();
        this.watchedFingerprint = configFingerprint();
    }

    /**
     * 配置根目录：本地 config.yml 的 shared-config-path →
     * 自动检测 /mc/shared/liusignin-config → 插件数据目录。
     * 与 LiuLib 一致，shared-config-path 只从各服本地 config.yml 读取。
     */
    private void resolveConfigRoot() {
        String configured = getConfig().getString("shared-config-path",
                getConfig().getString("settings.shared-config-path", ""));
        File root;
        if (configured != null && !configured.isBlank()) {
            File explicit = new File(configured.trim());
            if (explicit.isDirectory()) {
                getLogger().info("使用共享配置目录: " + explicit.getAbsolutePath());
                root = explicit;
            } else if (explicit.mkdirs()) {
                getLogger().info("已创建共享配置目录: " + explicit.getAbsolutePath());
                root = explicit;
            } else {
                getLogger().warning("共享配置目录不可用，回退插件目录: " + explicit.getAbsolutePath());
                root = getDataFolder();
            }
        } else {
            File auto = new File(AUTO_SHARED_CONFIG);
            if (auto.isDirectory()) {
                getLogger().info("检测到共享配置目录: " + auto.getAbsolutePath());
                root = auto;
            } else {
                root = getDataFolder();
            }
        }
        try {
            root = root.getCanonicalFile();
        } catch (IOException ignored) {
            root = root.getAbsoluteFile();
        }
        File previous = this.configRoot;
        this.configRoot = root;
        if (previous == null || !previous.equals(root)) {
            getLogger().info("配置目录: " + root.getAbsolutePath());
        }
    }

    /** 给各服本地 bootstrap config.yml 补全新增键（含 shared-config-path），不覆盖用户值。 */
    private void mergeLocalBootstrap() {
        File local = new File(getDataFolder(), "config.yml");
        if (!local.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.load(local);
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "本地 bootstrap config.yml 读取失败，跳过缺失键补全", error);
            return;
        }
        int added = mergeMissingKeys(yaml, loadBundled("config.yml"));
        if (added > 0 && saveAtomic(local, yaml)) {
            getLogger().info("已为本地 " + local + " 补全 " + added + " 个缺失配置键");
            reloadConfig();
        }
    }

    private void loadRuntimeConfig() {
        File configFile = file("config.yml");
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.options().parseComments(true);
        boolean parsed = false;
        try {
            if (configFile.isFile()) {
                loaded.load(configFile);
                parsed = true;
            }
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "config.yml 读取失败，使用内置默认配置", error);
        }
        YamlConfiguration defaults = loadBundled("config.yml");
        if (!parsed) {
            this.runtimeConfig = defaults;
            return;
        }
        int added = mergeMissingKeys(loaded, defaults);
        if (added > 0) {
            if (saveAtomic(configFile, loaded)) {
                getLogger().info("已为 " + configFile + " 补全 " + added + " 个缺失配置键");
            } else {
                getLogger().warning("配置缺失键补全后保存失败: " + configFile);
            }
        }
        this.runtimeConfig = loaded;
    }

    /**
     * 补全 messages.yml 新增键，并把旧版默认单行 reminder 升级为多行模板。
     * 用户自定义过的 reminder 不覆盖，只补缺失的 reminder-quotes 等键。
     */
    private void mergeMessages() {
        File target = file("messages.yml");
        if (!target.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.load(target);
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "messages.yml 读取失败，跳过缺失消息补全", error);
            return;
        }
        YamlConfiguration defaults = loadBundled("messages.yml");
        int added = mergeMissingKeys(yaml, defaults);
        String oldReminder = "<yellow>今天还没有签到哦，点击打开签到界面。</yellow>";
        boolean upgraded = oldReminder.equals(yaml.getString("reminder"))
                && defaults.contains("reminder");
        if (upgraded) {
            yaml.set("reminder", defaults.getString("reminder"));
        }
        if (added == 0 && !upgraded) {
            return;
        }
        if (saveAtomic(target, yaml)) {
            getLogger().info("已更新 messages.yml：补全 " + added + " 个键"
                    + (upgraded ? "，并升级签到提醒模板" : ""));
        } else {
            getLogger().warning("messages.yml 更新后保存失败");
        }
    }

    private YamlConfiguration loadBundled(String name) {
        try (InputStream stream = getResource(name)) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception error) {
            getLogger().log(Level.WARNING, "读取内置配置失败: " + name, error);
            return new YamlConfiguration();
        }
    }

    private boolean copyBundled(String name, File target, boolean replace) {
        if (!replace && target.exists()) {
            return false;
        }
        try (InputStream stream = getResource(name)) {
            if (stream == null) {
                getLogger().warning("jar 内缺少默认配置资源: " + name);
                return false;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                getLogger().warning("无法创建配置目录: " + parent);
                return false;
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            getLogger().log(Level.WARNING, "复制默认配置失败: " + name, error);
            return false;
        }
    }

    private void saveIfMissing(String name) {
        copyBundled(name, file(name), false);
    }

    /**
     * menus.yml 结构升级：旧版没有 menus-version，备份后替换为新版默认布局。
     * 玩家自定义的旧文件会保留为 menus-v{old}-backup.yml，可手动合并。
     */
    private void migrateMenus() {
        File menusFile = file("menus.yml");
        if (!menusFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(menusFile);
        int version = yaml.getInt("menus-version", 0);
        if (version >= MENUS_VERSION) {
            return;
        }
        File backup = new File(menusFile.getParentFile(), "menus-v" + version + "-backup.yml");
        try {
            Files.copy(menusFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (copyBundled("menus.yml", menusFile, true)) {
                getLogger().info("menus.yml 已升级到 v" + MENUS_VERSION
                        + "，旧配置备份为 " + backup.getName());
            }
        } catch (IOException error) {
            getLogger().warning("menus.yml 升级失败，继续使用旧配置：" + error.getMessage());
        }
    }

    private void startConfigWatcher() {
        if (configRoot == null || runtimeConfig == null) {
            return;
        }
        boolean enabled = getConfig().getBoolean("shared-config-watch", true)
                && !sameFile(configRoot, getDataFolder());
        if (enabled && configWatchTask != null && !configWatchTask.isCancelled()
                && Objects.equals(watchedRoot, configRoot)) {
            return;
        }
        stopConfigWatcher();
        if (!enabled) {
            return;
        }
        this.watchedRoot = configRoot;
        this.watchedFingerprint = configFingerprint();
        this.configWatchTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, this::pollConfigChanges, CONFIG_WATCH_TICKS, CONFIG_WATCH_TICKS);
    }

    private void stopConfigWatcher() {
        BukkitTask task = this.configWatchTask;
        this.configWatchTask = null;
        this.watchedRoot = null;
        this.configReloadQueued = false;
        if (task != null) {
            task.cancel();
        }
    }

    private void pollConfigChanges() {
        if (configReloadQueued || configRoot == null || !isEnabled()) {
            return;
        }
        long stamp = configFingerprint();
        if (stamp == watchedFingerprint) {
            return;
        }
        this.watchedFingerprint = stamp;
        this.configReloadQueued = true;
        try {
            getServer().getScheduler().runTask(this, () -> {
                try {
                    reloadEverything();
                } finally {
                    configReloadQueued = false;
                }
            });
        } catch (RuntimeException error) {
            configReloadQueued = false;
        }
    }

    private long configFingerprint() {
        long latest = 0L;
        for (String name : new String[]{"config.yml", "rewards.yml", "menus.yml", "messages.yml"}) {
            File current = file(name);
            if (current.isFile()) {
                latest = Math.max(latest, current.lastModified());
            }
        }
        // 共享模式下也监听本服 bootstrap config.yml，改路径后无需重启即可切目录
        File local = new File(getDataFolder(), "config.yml");
        if (local.isFile()) {
            latest = Math.max(latest, local.lastModified());
        }
        return latest;
    }

    private boolean sameFile(File first, File second) {
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (IOException ignored) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
        }
    }

    /** 只补缺失键，不覆盖用户值；与 LiuLib Configs.mergeMissingKeys 行为一致。 */
    private int mergeMissingKeys(ConfigurationSection current, ConfigurationSection defaults) {
        if (current == null || defaults == null) {
            return 0;
        }
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            try {
                if (defaults.isConfigurationSection(key)) {
                    if (!current.isConfigurationSection(key) && !current.contains(key)) {
                        current.createSection(key);
                        added++;
                    }
                    continue;
                }
                if (!current.contains(key)) {
                    current.set(key, defaults.get(key));
                    added++;
                }
            } catch (RuntimeException conflict) {
                // 用户把 section 写成标量（或反之）：保留用户值
            }
        }
        return added;
    }

    private boolean saveAtomic(File target, YamlConfiguration yaml) {
        if (target == null || yaml == null) {
            return false;
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            Path temporary = target.toPath().resolveSibling(
                    target.getName() + "." + UUID.randomUUID() + ".tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable error) {
            getLogger().warning("保存配置失败: " + target + " (" + error.getMessage() + ")");
            return false;
        }
    }

    private File file(String name) {
        File root = configRoot;
        return new File(root == null ? getDataFolder() : root, name);
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

    /** 对外服务 API；其它插件通过 Bukkit ServicesManager 获取。 */
    public LiuSigninApi api() {
        return pluginApi;
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
