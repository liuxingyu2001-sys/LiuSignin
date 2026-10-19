package com.liuxingyu.liusignin.data;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 一条签到记录。{@code (uuid, date)} 是数据库主键，也是跨服防重复签到的唯一裁决点。
 *
 * @param kind    normal / makeup / auto
 * @param status  pending / granted / failed
 */
public record SigninLog(
        UUID uniqueId,
        LocalDate date,
        String kind,
        int streak,
        String rewardGroup,
        String status,
        String serverId
) {
    public static final String KIND_NORMAL = "normal";
    public static final String KIND_MAKEUP = "makeup";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_GRANTED = "granted";
    public static final String STATUS_FAILED = "failed";
}
