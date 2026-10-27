package com.liuxingyu.liusignin.service;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.PlayerSigninEvent;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.config.SigninConfig;
import com.liuxingyu.liusignin.data.LeaderboardEntry;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.data.SigninLog;
import com.liuxingyu.liusignin.data.SigninStore;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.reward.RewardGroup;
import com.liuxingyu.liusignin.util.Schedulers;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 签到业务核心：抢占名额、连签计算、奖励发放、补签、排行与缓存。
 *
 * <p>并发模型：内存缓存只用于展示；是否“抢到今天的签到”完全由 MySQL
 * {@code liusignin_log} 的联合主键裁决，因此多台子服同时点签到也只会成功一次。</p>
 */
public final class SigninService {

    private static final long STATE_TTL_MS = 120_000L;

    public enum SignStatus {
        SUCCESS,
        ALREADY,
        COOLDOWN,
        NO_PERMISSION,
        ERROR
    }

    public record SignResult(SignStatus status, LocalDate date, int streak, int total,
                             List<Reward> rewards, String group, boolean firstOfDay) {
        public static SignResult of(SignStatus status) {
            return new SignResult(status, null, 0, 0, List.of(), "", false);
        }
    }

    public enum MakeupStatus {
        OK,
        DISABLED,
        NO_PERMISSION,
        NO_CARD,
        ALREADY,
        OUT_OF_RANGE,
        FUTURE,
        ERROR
    }

    public record MakeupResult(MakeupStatus status, LocalDate date, int streak, int total,
                               List<Reward> rewards, int cards) {
        public static MakeupResult of(MakeupStatus status, LocalDate date) {
            return new MakeupResult(status, date, 0, 0, List.of(), 0);
        }
    }

    private final LiuSigninPlugin plugin;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshingToday = new AtomicBoolean();
    private final AtomicInteger todayCount = new AtomicInteger();
    private volatile LocalDate todayCountDate;
    private volatile List<LeaderboardEntry> topStreak = List.of();
    private volatile List<LeaderboardEntry> topTotal = List.of();
    private volatile long topFetchedAt;

    public SigninService(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // 缓存
    // ==================================================================

    public CompletableFuture<PlayerState> state(UUID uuid, boolean force) {
        PlayerState cached = states.get(uuid);
        if (!force && cached != null && System.currentTimeMillis() - cached.loadedAt() < STATE_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        return Schedulers.async(plugin, () -> loadState(uuid));
    }

    public PlayerState cached(UUID uuid) {
        return states.get(uuid);
    }

    /** 只返回 TTL 内的缓存；过期或未加载返回 null。 */
    public PlayerState cachedFresh(UUID uuid) {
        PlayerState state = uuid == null ? null : states.get(uuid);
        if (state != null && System.currentTimeMillis() - state.loadedAt() >= STATE_TTL_MS) {
            return null;
        }
        return state;
    }

    /** 清掉离线且过期玩家的状态，避免 /signin info、API 查离线玩家导致 Map 无限增长。 */
    public void evictInactive() {
        long cutoff = System.currentTimeMillis() - STATE_TTL_MS;
        states.entrySet().removeIf(entry -> {
            var online = plugin.getServer().getPlayer(entry.getKey());
            return (online == null || !online.isOnline()) && entry.getValue().loadedAt() < cutoff;
        });
    }

    public PlayerSigninData data(UUID uuid) {
        PlayerState state = states.get(uuid);
        return state == null ? null : state.data();
    }

    public boolean isSignedToday(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        PlayerState state = states.get(uuid);
        return state != null && state.data().hasSigned(plugin.time().today());
    }

    /** 其它子服已修改该玩家数据时调用：丢弃缓存，下次读取走 MySQL。 */
    public void invalidate(UUID uuid) {
        if (uuid != null) {
            states.remove(uuid);
        }
    }

    /** reload 后清空内存缓存，强制下次读取走数据库。 */
    public void clearCaches() {
        states.clear();
        cooldowns.clear();
        topStreak = List.of();
        topTotal = List.of();
        topFetchedAt = 0L;
        todayCountDate = null;
        todayCount.set(0);
    }

    private PlayerState loadState(UUID uuid) {
        LocalDate today = plugin.time().today();
        PlayerSigninData data = plugin.store().load(uuid);
        Set<LocalDate> signed = plugin.store().signedDates(uuid, today.minusDays(400), today);
        PlayerState state = new PlayerState(data, signed, System.currentTimeMillis());
        states.put(uuid, state);
        return state;
    }

    private void cache(UUID uuid, PlayerSigninData data, LocalDate date, boolean signed) {
        PlayerState old = states.get(uuid);
        Set<LocalDate> dates = ConcurrentHashMap.newKeySet();
        if (old != null) {
            dates.addAll(old.signed());
        }
        if (signed && date != null) {
            dates.add(date);
        }
        states.put(uuid, new PlayerState(data, dates, System.currentTimeMillis()));
    }

    // ==================================================================
    // 签到
    // ==================================================================

    public CompletableFuture<SignResult> sign(Player player, SignSource source) {
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission("liusignin.sign")) {
            return CompletableFuture.completedFuture(SignResult.of(SignStatus.NO_PERMISSION));
        }
        SigninConfig config = plugin.config();
        boolean manual = source == SignSource.COMMAND || source == SignSource.GUI;
        if (manual) {
            long now = System.currentTimeMillis();
            Long last = cooldowns.get(uuid);
            if (last != null && now - last < config.cooldownMs()
                    && !player.hasPermission("liusignin.bypass.cooldown")) {
                return CompletableFuture.completedFuture(SignResult.of(SignStatus.COOLDOWN));
            }
            cooldowns.put(uuid, now);
        }

        RewardGroup group = plugin.rewards().groupFor(player);
        String name = player.getName();
        CompletableFuture<SignResult> future = new CompletableFuture<>();

        Schedulers.async(plugin, () -> claimSign(uuid, name, group))
                .thenAccept(result -> {
                    if (result.status() != SignStatus.SUCCESS || !player.isOnline()) {
                        future.complete(result);
                        return;
                    }
                    Schedulers.onPlayer(plugin, player, () -> finishSign(player, result, source, future))
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    future.completeExceptionally(error);
                                }
                            });
                })
                .exceptionally(error -> {
                    plugin.getLogger().warning("签到失败（" + name + "）：" + rootMessage(error));
                    future.completeExceptionally(error);
                    return null;
                });
        return future;
    }

    private SignResult claimSign(UUID uuid, String name, RewardGroup group) {
        LocalDate today = plugin.time().today();
        PlayerSigninData data = plugin.store().load(uuid);
        if (today.equals(data.lastSignDate())) {
            cache(uuid, data, today, true);
            return SignResult.of(SignStatus.ALREADY);
        }
        LocalDate last = data.lastSignDate();
        int newStreak = last != null && last.plusDays(1).equals(today) ? data.streak() + 1 : 1;
        int newBest = Math.max(data.bestStreak(), newStreak);
        int newTotal = data.total() + 1;

        SigninStore.SignOutcome outcome = plugin.store().beginSign(
                uuid, name, today, newStreak, newBest, newTotal, group.id(), plugin.serverId());
        if (!outcome.ok()) {
            PlayerSigninData fresh = plugin.store().load(uuid);
            cache(uuid, fresh, today, true);
            return SignResult.of(SignStatus.ALREADY);
        }

        data.name(name);
        data.lastSignDate(today);
        data.streak(newStreak);
        data.bestStreak(newBest);
        data.total(newTotal);
        cache(uuid, data, today, true);

        todayCountDate = today;
        todayCount.set(outcome.todayCount());
        topFetchedAt = 0L;

        List<Reward> rewards = plugin.rewards().rewardsFor(group, today, newStreak, newTotal);
        return new SignResult(SignStatus.SUCCESS, today, newStreak, newTotal,
                rewards, group.id(), outcome.todayCount() == 1);
    }

    private void finishSign(Player player, SignResult result, SignSource source,
                            CompletableFuture<SignResult> future) {
        if (!player.isOnline()) {
            future.complete(result);
            return;
        }
        try {
            UUID uuid = player.getUniqueId();
            plugin.getServer().getPluginManager().callEvent(new PlayerSigninEvent(
                    player, result.date(), result.streak(), result.total(), result.rewards(), source));

            RewardService.GrantOutcome outcome = plugin.rewardService().grant(
                    player, result.rewards(), result.streak(), result.total(), result.date());

            String rewardText = outcome.summaries().isEmpty() ? "无" : outcome.summary("，");
            plugin.messages().send(player, "signed",
                    "streak", String.valueOf(result.streak()),
                    "total", String.valueOf(result.total()),
                    "rewards", rewardText);
            if (outcome.mailed() > 0) {
                plugin.messages().send(player, "mail-notice",
                        "count", String.valueOf(outcome.mailed()));
            }

            String playerName = player.getName();
            if (plugin.config().broadcast().sign()) {
                plugin.getServer().broadcast(plugin.messages().raw("broadcast",
                        "player", playerName,
                        "streak", String.valueOf(result.streak())));
            }
            if (result.firstOfDay() && plugin.config().broadcast().firstOfDay()) {
                plugin.getServer().broadcast(plugin.messages().raw("first-broadcast",
                        "player", playerName));
            }

            LocalDate date = result.date();
            Schedulers.asyncRun(plugin, () -> {
                plugin.store().markGranted(uuid, date, SigninLog.STATUS_GRANTED);
                plugin.sync().publishInvalidate(uuid);
            });
            future.complete(result);
        } catch (Throwable error) {
            plugin.getLogger().warning("签到奖励发放异常：" + rootMessage(error));
            future.completeExceptionally(error);
        }
    }

    // ==================================================================
    // 补签
    // ==================================================================

    public CompletableFuture<MakeupResult> makeup(Player player, LocalDate date) {
        UUID uuid = player.getUniqueId();
        SigninConfig.Makeup config = plugin.config().makeup();
        if (!config.enabled()) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.DISABLED, date));
        }
        if (!player.hasPermission("liusignin.makeup")) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.NO_PERMISSION, date));
        }
        LocalDate today = plugin.time().today();
        if (date == null || date.isAfter(today)) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.FUTURE, date));
        }
        if (date.isEqual(today)) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.FUTURE, date));
        }
        if (date.isBefore(today.minusDays(config.maxDays()))) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.OUT_OF_RANGE, date));
        }

        RewardGroup group = plugin.rewards().groupFor(player);
        String name = player.getName();
        CompletableFuture<MakeupResult> future = new CompletableFuture<>();
        Schedulers.async(plugin, () -> claimMakeup(uuid, name, group, date, today))
                .thenAccept(result -> {
                    if (result.status() != MakeupStatus.OK || !player.isOnline()) {
                        future.complete(result);
                        return;
                    }
                    Schedulers.onPlayer(plugin, player, () -> finishMakeup(player, result, future))
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    future.completeExceptionally(error);
                                }
                            });
                })
                .exceptionally(error -> {
                    plugin.getLogger().warning("补签失败（" + name + " " + date + "）：" + rootMessage(error));
                    future.completeExceptionally(error);
                    return null;
                });
        return future;
    }

    private MakeupResult claimMakeup(UUID uuid, String name, RewardGroup group,
                                     LocalDate date, LocalDate today) {
        SigninConfig.Makeup config = plugin.config().makeup();
        PlayerSigninData data = plugin.store().load(uuid);
        if (date.equals(data.lastSignDate())) {
            return new MakeupResult(MakeupStatus.ALREADY, date, data.streak(), data.total(),
                    List.of(), data.makeupCards());
        }
        if (!plugin.store().signedDates(uuid, date, date).isEmpty()) {
            return new MakeupResult(MakeupStatus.ALREADY, date, data.streak(), data.total(),
                    List.of(), data.makeupCards());
        }
        if (data.makeupCards() < config.cardsPerMakeup()) {
            return new MakeupResult(MakeupStatus.NO_CARD, date, data.streak(), data.total(),
                    List.of(), data.makeupCards());
        }

        LocalDate last = data.lastSignDate();
        boolean advance = config.keepStreak() && last != null && last.plusDays(1).equals(date);
        int newStreak = advance ? data.streak() + 1 : data.streak();
        int newBest = Math.max(data.bestStreak(), newStreak);
        int newTotal = data.total() + 1;

        SigninStore.MakeupOutcome outcome = plugin.store().beginMakeup(
                uuid, name, date, newStreak, newBest, newTotal, group.id(), plugin.serverId(),
                config.cardsPerMakeup(), advance);
        if (outcome != SigninStore.MakeupOutcome.OK) {
            MakeupStatus status = switch (outcome) {
                case NO_CARD -> MakeupStatus.NO_CARD;
                case ALREADY -> MakeupStatus.ALREADY;
                default -> MakeupStatus.ERROR;
            };
            return new MakeupResult(status, date, data.streak(), data.total(), List.of(), data.makeupCards());
        }

        data.makeupCards(data.makeupCards() - config.cardsPerMakeup());
        data.total(newTotal);
        data.name(name);
        if (advance) {
            data.lastSignDate(date);
            data.streak(newStreak);
            data.bestStreak(newBest);
        }
        cache(uuid, data, date, true);

        LocalDate rewardDate = config.rewardFromDate() ? date : today;
        List<Reward> rewards = plugin.rewards().rewardsFor(group, rewardDate, newStreak, newTotal);
        return new MakeupResult(MakeupStatus.OK, date, newStreak, newTotal,
                rewards, data.makeupCards());
    }

    private void finishMakeup(Player player, MakeupResult result, CompletableFuture<MakeupResult> future) {
        if (!player.isOnline()) {
            future.complete(result);
            return;
        }
        try {
            UUID uuid = player.getUniqueId();
            plugin.getServer().getPluginManager().callEvent(new PlayerSigninEvent(
                    player, result.date(), result.streak(), result.total(),
                    result.rewards(), SignSource.MAKEUP));
            RewardService.GrantOutcome outcome = plugin.rewardService().grant(
                    player, result.rewards(), result.streak(), result.total(), result.date());
            String rewardText = outcome.summaries().isEmpty() ? "无" : outcome.summary("，");
            plugin.messages().send(player, "makeup-success",
                    "date", result.date().toString(),
                    "rewards", rewardText);
            if (outcome.mailed() > 0) {
                plugin.messages().send(player, "mail-notice", "count", String.valueOf(outcome.mailed()));
            }
            LocalDate date = result.date();
            Schedulers.asyncRun(plugin, () -> {
                plugin.store().markGranted(uuid, date, SigninLog.STATUS_GRANTED);
                plugin.sync().publishInvalidate(uuid);
            });
            future.complete(result);
        } catch (Throwable error) {
            plugin.getLogger().warning("补签奖励发放异常：" + rootMessage(error));
            future.completeExceptionally(error);
        }
    }

    // ==================================================================
    // 断线补偿
    // ==================================================================

    /** 进服时补发“已入库但未标记发放”的奖励（服务端在发奖中途崩溃）。 */
    public void recoverPending(Player player) {
        if (!plugin.config().retryPending() || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Schedulers.async(plugin, () -> plugin.store().pending(uuid))
                .thenAccept(logs -> {
                    if (logs == null || logs.isEmpty()) {
                        return;
                    }
                    Schedulers.onPlayer(plugin, player, () -> {
                        for (SigninLog log : logs) {
                            if (!player.isOnline()) {
                                return;
                            }
                            RewardGroup group = plugin.rewards().groupById(log.rewardGroup())
                                    .orElseGet(() -> plugin.rewards().groupFor(player));
                            int logTotal = log.total() > 0 ? log.total()
                                    : Math.max(1, plugin.service().total(uuid));
                            List<Reward> rewards = plugin.rewards().rewardsFor(
                                    group, log.date(), log.streak(), logTotal);
                            plugin.getLogger().info("补发未完成的签到奖励：" + player.getName()
                                    + " " + log.date() + " streak=" + log.streak());
                            plugin.rewardService().grant(player, rewards, log.streak(),
                                    logTotal, log.date());
                            Schedulers.asyncRun(plugin, () -> plugin.store()
                                    .markGranted(uuid, log.date(), SigninLog.STATUS_GRANTED));
                        }
                    });
                })
                .exceptionally(error -> {
                    plugin.getLogger().fine("检查待发放奖励失败：" + rootMessage(error));
                    return null;
                });
    }

    // ==================================================================
    // 补签卡 / 排行 / 统计 / 管理
    // ==================================================================

    public CompletableFuture<Integer> addCardsAsync(UUID uuid, int amount) {
        if (amount <= 0) {
            return Schedulers.async(plugin, () -> plugin.store().cards(uuid));
        }
        return Schedulers.async(plugin, () -> {
            int cards = plugin.store().addCards(uuid, amount);
            PlayerState state = states.get(uuid);
            if (state != null) {
                state.data().makeupCards(cards);
            }
            plugin.sync().publishInvalidate(uuid);
            return cards;
        });
    }

    public CompletableFuture<Boolean> takeCardsAsync(UUID uuid, int amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return Schedulers.async(plugin, () -> {
            boolean ok = plugin.store().takeCards(uuid, amount);
            if (ok) {
                PlayerState state = states.get(uuid);
                if (state != null) {
                    state.data().makeupCards(state.data().makeupCards() - amount);
                }
                plugin.sync().publishInvalidate(uuid);
            }
            return ok;
        });
    }

    public CompletableFuture<Integer> setCardsAsync(UUID uuid, int amount) {
        int safe = Math.max(0, amount);
        return Schedulers.async(plugin, () -> {
            plugin.store().setCards(uuid, safe);
            PlayerState state = states.get(uuid);
            if (state != null) {
                state.data().makeupCards(safe);
            }
            plugin.sync().publishInvalidate(uuid);
            return safe;
        });
    }

    public CompletableFuture<Void> resetAsync(UUID uuid) {
        return Schedulers.asyncRun(plugin, () -> {
            plugin.store().reset(uuid);
            states.remove(uuid);
            topFetchedAt = 0L;
            plugin.sync().publishInvalidate(uuid);
        });
    }

    public CompletableFuture<Void> setStreakAsync(UUID uuid, int streak) {
        return Schedulers.asyncRun(plugin, () -> {
            plugin.store().setStreak(uuid, streak);
            states.remove(uuid);
            topFetchedAt = 0L;
            plugin.sync().publishInvalidate(uuid);
        });
    }

    public CompletableFuture<Void> setTotalAsync(UUID uuid, int total) {
        return Schedulers.asyncRun(plugin, () -> {
            plugin.store().setTotal(uuid, total);
            states.remove(uuid);
            topFetchedAt = 0L;
            plugin.sync().publishInvalidate(uuid);
        });
    }

    public CompletableFuture<Integer> purgeAsync(int days) {
        return Schedulers.async(plugin, () -> plugin.store().purgeBefore(
                plugin.time().today().minusDays(Math.max(1, days))));
    }

    public CompletableFuture<List<LeaderboardEntry>> top(String type) {
        boolean total = "total".equalsIgnoreCase(type);
        long now = System.currentTimeMillis();
        long ttl = plugin.config().leaderboardRefreshSeconds() * 1000L;
        List<LeaderboardEntry> cached = total ? topTotal : topStreak;
        if (now - topFetchedAt < ttl && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return Schedulers.async(plugin, () -> plugin.store().top(total ? "total" : "streak", 10))
                .thenApply(list -> {
                    if (total) {
                        topTotal = list;
                    } else {
                        topStreak = list;
                    }
                    topFetchedAt = System.currentTimeMillis();
                    return list;
                });
    }

    /** 排行榜名次（1 起）；未加载返回 0。 */
    public int rank(UUID uuid, boolean total) {
        List<LeaderboardEntry> list = total ? topTotal : topStreak;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uniqueId().equals(uuid)) {
                return i + 1;
            }
        }
        return 0;
    }

    public void refreshTodayCount() {
        if (!refreshingToday.compareAndSet(false, true)) {
            return;
        }
        LocalDate today = plugin.time().today();
        Schedulers.async(plugin, () -> plugin.store().todayCount(today))
                .whenComplete((count, error) -> {
                    refreshingToday.set(false);
                    if (error == null && count != null) {
                        todayCountDate = today;
                        todayCount.set(count);
                    }
                });
    }

    public int todayCount() {
        LocalDate today = plugin.time().today();
        if (today.equals(todayCountDate)) {
            return todayCount.get();
        }
        refreshTodayCount();
        return 0;
    }

    public int cards(UUID uuid) {
        PlayerSigninData data = data(uuid);
        return data == null ? 0 : data.makeupCards();
    }

    public int streak(UUID uuid) {
        PlayerSigninData data = data(uuid);
        return data == null ? 0 : data.streak();
    }

    public int total(UUID uuid) {
        PlayerSigninData data = data(uuid);
        return data == null ? 0 : data.total();
    }

    public int best(UUID uuid) {
        PlayerSigninData data = data(uuid);
        return data == null ? 0 : data.bestStreak();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
