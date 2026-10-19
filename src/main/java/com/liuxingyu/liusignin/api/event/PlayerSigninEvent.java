package com.liuxingyu.liusignin.api.event;

import com.liuxingyu.liusignin.reward.Reward;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.LocalDate;
import java.util.List;

/**
 * 玩家成功签到（数据库已记录，奖励即将发放）时触发。
 *
 * <p>在主线程同步触发，不可取消。监听方可以读取玩家、日期、连续天数与奖励列表，
 * 做额外统计或联动。</p>
 */
public class PlayerSigninEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final LocalDate date;
    private final int streak;
    private final int total;
    private final List<Reward> rewards;
    private final SignSource source;

    public PlayerSigninEvent(Player player, LocalDate date, int streak, int total,
                             List<Reward> rewards, SignSource source) {
        this.player = player;
        this.date = date;
        this.streak = streak;
        this.total = total;
        this.rewards = rewards == null ? List.of() : List.copyOf(rewards);
        this.source = source;
    }

    public Player player() {
        return player;
    }

    public LocalDate date() {
        return date;
    }

    public int streak() {
        return streak;
    }

    public int total() {
        return total;
    }

    public List<Reward> rewards() {
        return rewards;
    }

    public SignSource source() {
        return source;
    }

    public boolean makeup() {
        return source == SignSource.MAKEUP;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
