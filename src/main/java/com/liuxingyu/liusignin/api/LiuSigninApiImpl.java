package com.liuxingyu.liusignin.api;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.service.PlayerState;
import com.liuxingyu.liusignin.service.SigninService;
import com.liuxingyu.liusignin.util.Schedulers;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** {@link LiuSigninApi} 的默认实现，由 LiuSigninPlugin 注册为 Bukkit 服务。 */
public final class LiuSigninApiImpl implements LiuSigninApi {

    private final LiuSigninPlugin plugin;

    public LiuSigninApiImpl(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String serverId() {
        return plugin.serverId();
    }

    @Override
    public Profile cachedProfile(UUID uniqueId) {
        PlayerState state = uniqueId == null ? null : plugin.service().cachedFresh(uniqueId);
        return state == null ? null : profile(state);
    }

    @Override
    public CompletableFuture<Profile> profile(UUID uniqueId) {
        return profile(uniqueId, false);
    }

    @Override
    public CompletableFuture<Profile> profile(UUID uniqueId, boolean refresh) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return plugin.service().state(uniqueId, refresh).thenApply(this::profile);
    }

    @Override
    public boolean isSignedToday(UUID uniqueId) {
        return uniqueId != null && plugin.service().isSignedToday(uniqueId);
    }

    @Override
    public CompletableFuture<Boolean> isSigned(UUID uniqueId, LocalDate date) {
        if (uniqueId == null || date == null) {
            return CompletableFuture.completedFuture(false);
        }
        PlayerState state = plugin.service().cached(uniqueId);
        if (state != null && state.hasSigned(date)) {
            return CompletableFuture.completedFuture(true);
        }
        return Schedulers.async(plugin,
                () -> !plugin.store().signedDates(uniqueId, date, date).isEmpty());
    }

    @Override
    public CompletableFuture<SigninResult> sign(Player player) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(SigninResult.of(SigninStatus.ERROR));
        }
        return plugin.service().sign(player, SignSource.API)
                .thenApply(this::signResult)
                .exceptionally(error -> {
                    plugin.getLogger().warning("API 签到失败（" + player.getName() + "）："
                            + error.getMessage());
                    return SigninResult.of(SigninStatus.ERROR);
                });
    }

    @Override
    public CompletableFuture<MakeupResult> makeup(Player player, LocalDate date) {
        if (player == null || !player.isOnline() || date == null) {
            return CompletableFuture.completedFuture(MakeupResult.of(MakeupStatus.ERROR, date));
        }
        return plugin.service().makeup(player, date)
                .thenApply(this::makeupResult)
                .exceptionally(error -> {
                    plugin.getLogger().warning("API 补签失败（" + player.getName() + " " + date + "）："
                            + error.getMessage());
                    return MakeupResult.of(MakeupStatus.ERROR, date);
                });
    }

    @Override
    public CompletableFuture<Integer> addMakeupCards(UUID uniqueId, int amount) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(0);
        }
        return plugin.service().addCardsAsync(uniqueId, amount);
    }

    @Override
    public CompletableFuture<Boolean> takeMakeupCards(UUID uniqueId, int amount) {
        if (uniqueId == null || amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.service().takeCardsAsync(uniqueId, amount);
    }

    @Override
    public CompletableFuture<Integer> setMakeupCards(UUID uniqueId, int amount) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(0);
        }
        return plugin.service().setCardsAsync(uniqueId, amount);
    }

    @Override
    public void openMenu(Player player) {
        open(player, target -> plugin.signinMenu().open(target));
    }

    @Override
    public void openMakeupMenu(Player player, LocalDate date) {
        if (player == null || !player.isOnline() || date == null) {
            return;
        }
        UUID uniqueId = player.getUniqueId();
        plugin.service().state(uniqueId, false).thenAccept(state ->
                Schedulers.onPlayer(plugin, player, () -> {
                    if (player.isOnline()) {
                        plugin.makeupMenu().open(player, date, state);
                    }
                })).exceptionally(error -> {
                    plugin.getLogger().warning("打开补签界面失败（" + player.getName() + "）："
                            + error.getMessage());
                    return null;
                });
    }

    @Override
    public void openMailbox(Player player) {
        open(player, target -> plugin.mailMenu().open(target));
    }

    private void open(Player player, Consumer<Player> action) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Schedulers.onPlayer(plugin, player, () -> action.accept(player));
    }

    private Profile profile(PlayerState state) {
        PlayerSigninData data = state.data();
        return new Profile(
                data.uniqueId(),
                data.name(),
                data.lastSignDate(),
                data.streak(),
                data.bestStreak(),
                data.total(),
                data.makeupCards(),
                state.hasSigned(plugin.time().today()));
    }

    private SigninResult signResult(SigninService.SignResult result) {
        return new SigninResult(
                switch (result.status()) {
                    case SUCCESS -> SigninStatus.SUCCESS;
                    case ALREADY -> SigninStatus.ALREADY_SIGNED;
                    case COOLDOWN -> SigninStatus.COOLDOWN;
                    case NO_PERMISSION -> SigninStatus.NO_PERMISSION;
                    case ERROR -> SigninStatus.ERROR;
                },
                result.date(),
                result.streak(),
                result.total(),
                summaries(result.rewards()),
                result.firstOfDay());
    }

    private MakeupResult makeupResult(SigninService.MakeupResult result) {
        return new MakeupResult(
                switch (result.status()) {
                    case OK -> MakeupStatus.SUCCESS;
                    case DISABLED -> MakeupStatus.DISABLED;
                    case NO_PERMISSION -> MakeupStatus.NO_PERMISSION;
                    case NO_CARD -> MakeupStatus.NO_CARD;
                    case ALREADY -> MakeupStatus.ALREADY_SIGNED;
                    case OUT_OF_RANGE -> MakeupStatus.OUT_OF_RANGE;
                    case FUTURE -> MakeupStatus.FUTURE;
                    case ERROR -> MakeupStatus.ERROR;
                },
                result.date(),
                result.streak(),
                result.total(),
                summaries(result.rewards()),
                result.cards());
    }

    private List<String> summaries(List<Reward> rewards) {
        if (rewards == null || rewards.isEmpty()) {
            return List.of();
        }
        return rewards.stream()
                .map(reward -> reward.describe(plugin.items()))
                .toList();
    }
}
