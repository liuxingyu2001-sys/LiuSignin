package com.liuxingyu.liusignin.data;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 玩家签到档案（内存缓存对象）。
 *
 * <p>字段以数据库为准；缓存对象只做展示与连签计算，真正的并发裁决在
 * {@link SigninStore} 的唯一键写入上。字段使用 volatile，允许主线程读、
 * 异步线程写。</p>
 */
public final class PlayerSigninData {

    private final UUID uniqueId;
    private volatile String name;
    private volatile LocalDate lastSignDate;
    private volatile int streak;
    private volatile int bestStreak;
    private volatile int total;
    private volatile int makeupCards;

    public PlayerSigninData(UUID uniqueId) {
        this.uniqueId = uniqueId;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public LocalDate lastSignDate() {
        return lastSignDate;
    }

    public void lastSignDate(LocalDate date) {
        this.lastSignDate = date;
    }

    public int streak() {
        return streak;
    }

    public void streak(int streak) {
        this.streak = Math.max(0, streak);
    }

    public int bestStreak() {
        return bestStreak;
    }

    public void bestStreak(int bestStreak) {
        this.bestStreak = Math.max(0, bestStreak);
    }

    public int total() {
        return total;
    }

    public void total(int total) {
        this.total = Math.max(0, total);
    }

    public int makeupCards() {
        return makeupCards;
    }

    public void makeupCards(int cards) {
        this.makeupCards = Math.max(0, cards);
    }

    /** 是否已签今天（需传入当前签到日）。 */
    public boolean hasSigned(LocalDate today) {
        return today != null && today.equals(lastSignDate);
    }
}
