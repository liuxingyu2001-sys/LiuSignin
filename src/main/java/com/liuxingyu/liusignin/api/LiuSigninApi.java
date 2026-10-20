package com.liuxingyu.liusignin.api;

import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LiuSignin 对外服务接口。
 *
 * <p>通过 Bukkit 服务管理器获取：</p>
 *
 * <pre>{@code
 * LiuSigninApi api = Bukkit.getServicesManager().load(LiuSigninApi.class);
 * }</pre>
 *
 * <p>读取类方法返回 {@link CompletableFuture} 的会在异步线程访问 MySQL；
 * {@link #sign(Player)}、{@link #makeup(Player, LocalDate)} 会操作玩家背包 / Vault，
 * 必须在玩家所在线程调用。打开界面的方法会自动切回玩家线程。</p>
 */
public interface LiuSigninApi {

    /** 玩家签到档案快照。 */
    record Profile(
            UUID uniqueId,
            String name,
            LocalDate lastSignDate,
            int streak,
            int bestStreak,
            int total,
            int makeupCards,
            boolean signedToday
    ) {
    }

    /** 签到结果状态。 */
    enum SigninStatus {
        SUCCESS,
        ALREADY_SIGNED,
        COOLDOWN,
        NO_PERMISSION,
        ERROR
    }

    /** 补签结果状态。 */
    enum MakeupStatus {
        SUCCESS,
        DISABLED,
        NO_PERMISSION,
        NO_CARD,
        ALREADY_SIGNED,
        OUT_OF_RANGE,
        FUTURE,
        ERROR
    }

    /** 签到结果；奖励为普通文本摘要，便于 API 调用方自行展示。 */
    record SigninResult(
            SigninStatus status,
            LocalDate date,
            int streak,
            int total,
            List<String> rewards,
            boolean firstOfDay
    ) {
        public static SigninResult of(SigninStatus status) {
            return new SigninResult(status, null, 0, 0, List.of(), false);
        }
    }

    /** 补签结果。 */
    record MakeupResult(
            MakeupStatus status,
            LocalDate date,
            int streak,
            int total,
            List<String> rewards,
            int makeupCards
    ) {
        public static MakeupResult of(MakeupStatus status, LocalDate date) {
            return new MakeupResult(status, date, 0, 0, List.of(), 0);
        }
    }

    /** 当前子服 ID（每次启动生成，用于日志/排障）。 */
    String serverId();

    /** 只读缓存；未加载或已过期返回 null，不触发数据库查询。 */
    Profile cachedProfile(UUID uniqueId);

    /** 读取档案；缓存未命中时异步查 MySQL。 */
    CompletableFuture<Profile> profile(UUID uniqueId);

    /** 读取档案；{@code refresh=true} 强制绕过缓存。 */
    CompletableFuture<Profile> profile(UUID uniqueId, boolean refresh);

    /** 当前缓存中是否已签到今天；未加载时返回 false。 */
    boolean isSignedToday(UUID uniqueId);

    /** 查询指定签到日是否已签到；优先读缓存，否则异步查 MySQL。 */
    CompletableFuture<Boolean> isSigned(UUID uniqueId, LocalDate date);

    /**
     * 以 {@link com.liuxingyu.liusignin.api.event.SignSource#API} 来源签到。
     * 必须在玩家所在线程调用。
     */
    CompletableFuture<SigninResult> sign(Player player);

    /**
     * 补签指定日期。必须在玩家所在线程调用。
     */
    CompletableFuture<MakeupResult> makeup(Player player, LocalDate date);

    /** 增加补签卡，返回变更后的数量。 */
    CompletableFuture<Integer> addMakeupCards(UUID uniqueId, int amount);

    /** 扣除补签卡；数量不足返回 false。 */
    CompletableFuture<Boolean> takeMakeupCards(UUID uniqueId, int amount);

    /** 直接设置补签卡数量，返回设置后的数量。 */
    CompletableFuture<Integer> setMakeupCards(UUID uniqueId, int amount);

    /** 打开签到月历。 */
    void openMenu(Player player);

    /** 打开指定日期的补签确认界面。 */
    void openMakeupMenu(Player player, LocalDate date);

    /** 打开签到邮箱。 */
    void openMailbox(Player player);
}
