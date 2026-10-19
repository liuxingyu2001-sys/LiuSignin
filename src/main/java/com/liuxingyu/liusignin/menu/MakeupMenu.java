package com.liuxingyu.liusignin.menu;

import com.liuxingyu.liulib.ui.SparrowUi;
import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.reward.RewardGroup;
import com.liuxingyu.liusignin.service.PlayerState;
import com.liuxingyu.liusignin.service.SigninService;
import com.liuxingyu.liusignin.util.Schedulers;
import com.liuxingyu.liusignin.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 补签确认界面。 */
public final class MakeupMenu {

    private final LiuSigninPlugin plugin;

    public MakeupMenu(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, LocalDate date, PlayerState state) {
        if (!player.isOnline()) {
            return;
        }
        SparrowUi ui = plugin.sparrow();
        if (ui == null || !ui.available()) {
            plugin.messages().send(player, "error");
            return;
        }
        MenuConfig.Makeup config = plugin.menus().makeup();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("date", date.toString());
        placeholders.put("cost", String.valueOf(plugin.config().makeup().cardsPerMakeup()));
        placeholders.put("cards", String.valueOf(state.data().makeupCards()));
        placeholders.put("streak", String.valueOf(state.data().streak()));
        placeholders.put("total", String.valueOf(state.data().total()));

        RewardGroup group = plugin.rewards().groupFor(player);
        boolean advance = plugin.config().makeup().keepStreak()
                && state.data().lastSignDate() != null
                && state.data().lastSignDate().plusDays(1).equals(date);
        int previewStreak = Math.max(1, state.data().streak() + (advance ? 1 : 0));
        LocalDate rewardDate = plugin.config().makeup().rewardFromDate()
                ? date : plugin.time().today();
        List<Reward> rewards = plugin.rewards().rewardsFor(
                group, rewardDate, previewStreak, state.data().total() + 1);
        placeholders.put("reward", rewards.isEmpty() ? "无" : rewards.stream()
                .map(reward -> reward.describe(plugin.items()))
                .collect(java.util.stream.Collectors.joining("，")));

        SparrowUi.MenuBuilder menu = ui.menu(
                plugin.items().names().parse(Text.fill(config.title(), placeholders)), config.rows());
        ItemStack filler = plugin.menus().calendar().background().build(plugin.items(), "", List.of());
        menu.fillEmpty(filler);

        menu.set(config.infoSlot(), build(config.info(), placeholders));
        menu.set(config.confirmSlot(), build(config.confirm(), placeholders), click ->
                plugin.service().makeup(player, date).thenAccept(result ->
                        Schedulers.onPlayer(plugin, player, () -> handleResult(player, result))));
        menu.set(config.cancelSlot(), build(config.cancel(), placeholders), click ->
                plugin.signinMenu().open(player));

        menu.open(player);
    }

    private void handleResult(Player player, SigninService.MakeupResult result) {
        SigninService.MakeupStatus status = result.status();
        switch (status) {
            case OK -> plugin.signinMenu().open(player);
            case DISABLED -> plugin.messages().send(player, "makeup-disabled");
            case NO_PERMISSION -> plugin.messages().send(player, "no-permission");
            case NO_CARD -> plugin.messages().send(player, "makeup-no-card",
                    "cost", String.valueOf(plugin.config().makeup().cardsPerMakeup()));
            case ALREADY -> plugin.messages().send(player, "makeup-already",
                    "date", result.date() == null ? "" : result.date().toString());
            case OUT_OF_RANGE -> plugin.messages().send(player, "makeup-out-of-range",
                    "days", String.valueOf(plugin.config().makeup().maxDays()));
            case FUTURE -> plugin.messages().send(player, "makeup-future");
            case ERROR -> plugin.messages().send(player, "error");
        }
    }

    private ItemStack build(ItemTemplate template, Map<String, String> placeholders) {
        return template.build(plugin.items(),
                Text.fill(template.name(), placeholders),
                template.lore().stream().map(line -> Text.fill(line, placeholders)).toList());
    }
}
