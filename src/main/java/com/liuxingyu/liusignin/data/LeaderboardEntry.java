package com.liuxingyu.liusignin.data;

import java.util.UUID;

/** 排行榜条目。 */
public record LeaderboardEntry(UUID uniqueId, String name, int streak, int total, int bestStreak) {
}
