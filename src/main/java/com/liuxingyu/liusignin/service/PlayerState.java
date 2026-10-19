package com.liuxingyu.liusignin.service;

import com.liuxingyu.liusignin.data.PlayerSigninData;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家签到缓存：档案 + 最近已签到日期集合（月历渲染用）。
 */
public final class PlayerState {

    private final PlayerSigninData data;
    private final Set<LocalDate> signed;
    private final long loadedAt;

    public PlayerState(PlayerSigninData data, Set<LocalDate> signed, long loadedAt) {
        this.data = data;
        this.signed = ConcurrentHashMap.newKeySet();
        if (signed != null) {
            this.signed.addAll(signed);
        }
        this.loadedAt = loadedAt;
    }

    public PlayerSigninData data() {
        return data;
    }

    public Set<LocalDate> signed() {
        return signed;
    }

    public boolean hasSigned(LocalDate date) {
        return date != null && signed.contains(date);
    }

    public long loadedAt() {
        return loadedAt;
    }

    public PlayerState withSigned(LocalDate date) {
        if (date != null) {
            signed.add(date);
        }
        return new PlayerState(data, signed, System.currentTimeMillis());
    }
}
