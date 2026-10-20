package com.liuxingyu.liusignin.data;

import com.liuxingyu.liulib.storage.MySql;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 数据层。
 *
 * <p>全部写操作使用 LiuLib 的连接池；跨服并发由数据库唯一键
 * {@code (uuid, sign_date)} 裁决，不依赖内存缓存，因此多台子服可以安全地
 * 同时处理同一名玩家的签到。</p>
 */
public final class SigninStore {

    /** 签到结果：是否抢到当日名额，以及抢到后当天已签到人数。 */
    public record SignOutcome(boolean ok, int todayCount) {
        public static final SignOutcome ALREADY = new SignOutcome(false, 0);
    }

    public enum MakeupOutcome {
        OK,
        NO_CARD,
        ALREADY,
        ERROR
    }

    private final MySql mysql;
    private final Logger logger;
    private volatile String prefix;

    public SigninStore(MySql mysql, Logger logger, String prefix) {
        this.mysql = mysql;
        this.logger = logger;
        this.prefix = prefix == null ? "liusignin_" : prefix;
    }

    public void prefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            this.prefix = prefix;
        }
    }

    private String table(String name) {
        return "`" + prefix + name + "`";
    }

    private String playerTable() {
        return table("player");
    }

    private String logTable() {
        return table("log");
    }

    private String statsTable() {
        return table("stats");
    }

    private String mailboxTable() {
        return table("mailbox");
    }

    private String reminderTable() {
        return table("reminder");
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    /** 建表；失败抛异常，由插件在启用阶段决定是否停用。 */
    public void init() {
        mysql.execute("CREATE TABLE IF NOT EXISTS " + playerTable() + " ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`name` VARCHAR(32) NOT NULL DEFAULT '',"
                + "`last_sign_date` DATE NULL,"
                + "`streak` INT NOT NULL DEFAULT 0,"
                + "`best_streak` INT NOT NULL DEFAULT 0,"
                + "`total` INT NOT NULL DEFAULT 0,"
                + "`makeup_cards` INT NOT NULL DEFAULT 0,"
                + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (`uuid`),"
                + "KEY `idx_streak` (`streak`),"
                + "KEY `idx_total` (`total`),"
                + "KEY `idx_rank_streak` (`streak`, `total`, `updated_at`),"
                + "KEY `idx_rank_total` (`total`, `streak`, `updated_at`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        mysql.execute("CREATE TABLE IF NOT EXISTS " + logTable() + " ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`sign_date` DATE NOT NULL,"
                + "`kind` VARCHAR(16) NOT NULL DEFAULT 'normal',"
                + "`streak` INT NOT NULL DEFAULT 0,"
                + "`reward_group` VARCHAR(64) NOT NULL DEFAULT '',"
                + "`status` VARCHAR(16) NOT NULL DEFAULT 'pending',"
                + "`server_id` CHAR(36) NOT NULL DEFAULT '',"
                + "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (`uuid`, `sign_date`),"
                + "KEY `idx_date` (`sign_date`),"
                + "KEY `idx_status` (`status`),"
                + "KEY `idx_uuid_status` (`uuid`, `status`, `sign_date`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        mysql.execute("CREATE TABLE IF NOT EXISTS " + statsTable() + " ("
                + "`stat_date` DATE NOT NULL,"
                + "`sign_count` INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`stat_date`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        mysql.execute("CREATE TABLE IF NOT EXISTS " + mailboxTable() + " ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`uuid` CHAR(36) NOT NULL,"
                + "`payload` MEDIUMTEXT NOT NULL,"
                + "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (`id`),"
                + "KEY `idx_uuid` (`uuid`),"
                + "KEY `idx_uuid_id` (`uuid`, `id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        // 进服提醒去重：同一签到日只允许一台子服抢到提醒名额。
        mysql.execute("CREATE TABLE IF NOT EXISTS " + reminderTable() + " ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`reminder_date` DATE NOT NULL,"
                + "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (`uuid`, `reminder_date`),"
                + "KEY `idx_reminder_date` (`reminder_date`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        ensureIndexes();
    }

    /**
     * 老表补建排行 / 待发奖 / 邮箱复合索引，避免 ORDER BY 全表排序。
     * MySQL 不支持 IF NOT EXISTS 建索引，先查 information_schema；失败只告警，
     * 不让旧库因为 DDL 权限不足而无法启动。
     */
    private void ensureIndexes() {
        ensureIndex("player", "idx_rank_streak", "`streak`, `total`, `updated_at`");
        ensureIndex("player", "idx_rank_total", "`total`, `streak`, `updated_at`");
        ensureIndex("log", "idx_uuid_status", "`uuid`, `status`, `sign_date`");
        ensureIndex("mailbox", "idx_uuid_id", "`uuid`, `id`");
    }

    private void ensureIndex(String name, String index, String columns) {
        try {
            Integer exists = mysql.queryOne(
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    rs -> rs.getInt(1), prefix + name, index);
            if (exists != null && exists > 0) {
                return;
            }
            mysql.execute("ALTER TABLE " + table(name) + " ADD INDEX `" + index + "` (" + columns + ")");
            logger.info("已补充索引 " + prefix + name + "." + index);
        } catch (RuntimeException error) {
            logger.warning("补充索引失败 " + prefix + name + "." + index + "："
                    + error.getMessage());
        }
    }

    /**
     * 抢占某签到日的“进服提醒”名额。
     *
     * <p>多台子服共用 MySQL 时，{@code (uuid, reminder_date)} 主键保证只有
     * 一台能插入成功；玩家切换子服后不会再次收到同一天的提醒。</p>
     *
     * @return true 表示本次由当前子服提醒
     */
    public boolean claimReminder(UUID uuid, LocalDate date) {
        if (uuid == null || date == null) {
            return false;
        }
        return mysql.update("INSERT IGNORE INTO " + reminderTable()
                        + " (`uuid`, `reminder_date`) VALUES (?, ?)",
                uuid.toString(), Date.valueOf(date)) > 0;
    }

    public boolean ping() {
        return mysql.healthCheck();
    }

    // ==================================================================
    // 玩家档案
    // ==================================================================

    /** 读取档案；不存在时自动建档。已存在时只读，不再每次写 INSERT IGNORE。 */
    public PlayerSigninData load(UUID uuid) {
        String id = uuid.toString();
        PlayerSigninData data = selectPlayer(id, uuid);
        if (data != null) {
            return data;
        }
        mysql.update("INSERT IGNORE INTO " + playerTable() + " (`uuid`, `name`) VALUES (?, '')", id);
        data = selectPlayer(id, uuid);
        return data == null ? new PlayerSigninData(uuid) : data;
    }

    private PlayerSigninData selectPlayer(String id, UUID uuid) {
        return mysql.queryOne(
                "SELECT `name`, `last_sign_date`, `streak`, `best_streak`, `total`, `makeup_cards`"
                        + " FROM " + playerTable() + " WHERE `uuid` = ?",
                rs -> mapPlayer(rs, uuid),
                id);
    }

    private static PlayerSigninData mapPlayer(ResultSet rs, UUID uuid) throws SQLException {
        PlayerSigninData data = new PlayerSigninData(uuid);
        int i = 1;
        data.name(rs.getString(i++));
        Date last = rs.getDate(i++);
        data.lastSignDate(last == null ? null : last.toLocalDate());
        data.streak(rs.getInt(i++));
        data.bestStreak(rs.getInt(i++));
        data.total(rs.getInt(i++));
        data.makeupCards(rs.getInt(i));
        return data;
    }

    // ==================================================================
    // 签到（原子抢占）
    // ==================================================================

    /**
     * 抢占某一天的签到名额并累加档案。
     *
     * <p>关键点：{@code INSERT IGNORE INTO log} 的主键是 {@code (uuid, sign_date)}，
     * 多台子服同时写入只有一台能成功；成功者才继续累加玩家数据并发放奖励。</p>
     */
    public SignOutcome beginSign(UUID uuid, String name, LocalDate date, int newStreak,
                                 int newBest, String rewardGroup, String serverId) {
        String id = uuid.toString();
        return mysql.transaction(connection -> {
            int inserted;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT IGNORE INTO " + logTable()
                            + " (`uuid`, `sign_date`, `kind`, `streak`, `reward_group`, `status`, `server_id`)"
                            + " VALUES (?, ?, 'normal', ?, ?, 'pending', ?)")) {
                ps.setString(1, id);
                ps.setDate(2, Date.valueOf(date));
                ps.setInt(3, newStreak);
                ps.setString(4, rewardGroup == null ? "" : rewardGroup);
                ps.setString(5, serverId == null ? "" : serverId);
                inserted = ps.executeUpdate();
            }
            if (inserted == 0) {
                return SignOutcome.ALREADY;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + playerTable() + " SET `last_sign_date` = ?, `streak` = ?,"
                            + " `best_streak` = GREATEST(`best_streak`, ?), `total` = `total` + 1,"
                            + " `name` = ? WHERE `uuid` = ?")) {
                ps.setDate(1, Date.valueOf(date));
                ps.setInt(2, newStreak);
                ps.setInt(3, newBest);
                ps.setString(4, name == null ? "" : name);
                ps.setString(5, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + statsTable() + " (`stat_date`, `sign_count`) VALUES (?, 1)"
                            + " ON DUPLICATE KEY UPDATE `sign_count` = `sign_count` + 1")) {
                ps.setDate(1, Date.valueOf(date));
                ps.executeUpdate();
            }
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT `sign_count` FROM " + statsTable() + " WHERE `stat_date` = ?")) {
                ps.setDate(1, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getInt(1);
                    }
                }
            }
            return new SignOutcome(true, count);
        });
    }

    /**
     * 补签：扣卡 + 写记录 + 可选续签，同一事务完成；任一步失败整体回滚。
     *
     * @param advanceLast 补签日期紧邻上次签到日期时，是否把 last_sign_date / streak 推进
     */
    public MakeupOutcome beginMakeup(UUID uuid, String name, LocalDate date, int newStreak,
                                     int newBest, String rewardGroup, String serverId,
                                     int cardCost, boolean advanceLast) {
        String id = uuid.toString();
        try {
            return mysql.transaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE " + playerTable() + " SET `makeup_cards` = `makeup_cards` - ?"
                                + " WHERE `uuid` = ? AND `makeup_cards` >= ?")) {
                    ps.setInt(1, cardCost);
                    ps.setString(2, id);
                    ps.setInt(3, cardCost);
                    if (ps.executeUpdate() == 0) {
                        return MakeupOutcome.NO_CARD;
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO " + logTable()
                                + " (`uuid`, `sign_date`, `kind`, `streak`, `reward_group`, `status`, `server_id`)"
                                + " VALUES (?, ?, 'makeup', ?, ?, 'pending', ?)")) {
                    ps.setString(1, id);
                    ps.setDate(2, Date.valueOf(date));
                    ps.setInt(3, newStreak);
                    ps.setString(4, rewardGroup == null ? "" : rewardGroup);
                    ps.setString(5, serverId == null ? "" : serverId);
                    try {
                        ps.executeUpdate();
                    } catch (SQLIntegrityConstraintViolationException duplicate) {
                        throw new DuplicateSignException();
                    }
                }
                if (advanceLast) {
                    // 用 GREATEST 防止与另一台子服的“今天签到”并发时把 last_sign_date / streak 改小
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE " + playerTable() + " SET"
                                    + " `last_sign_date` = IFNULL(GREATEST(`last_sign_date`, ?), ?),"
                                    + " `streak` = GREATEST(`streak`, ?),"
                                    + " `best_streak` = GREATEST(`best_streak`, ?),"
                                    + " `total` = `total` + 1,"
                                    + " `name` = ? WHERE `uuid` = ?")) {
                        ps.setDate(1, Date.valueOf(date));
                        ps.setDate(2, Date.valueOf(date));
                        ps.setInt(3, newStreak);
                        ps.setInt(4, newBest);
                        ps.setString(5, name == null ? "" : name);
                        ps.setString(6, id);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE " + playerTable() + " SET `total` = `total` + 1, `name` = ?"
                                    + " WHERE `uuid` = ?")) {
                        ps.setString(1, name == null ? "" : name);
                        ps.setString(2, id);
                        ps.executeUpdate();
                    }
                }
                return MakeupOutcome.OK;
            });
        } catch (DuplicateSignException duplicate) {
            return MakeupOutcome.ALREADY;
        } catch (RuntimeException error) {
            if (isDuplicateKey(error)) {
                return MakeupOutcome.ALREADY;
            }
            logger.log(Level.WARNING, "补签事务失败: " + uuid + " " + date, error);
            return MakeupOutcome.ERROR;
        }
    }

    /** 奖励发放完成后标记记录；影响 0 行说明已被其它流程处理。 */
    public boolean markGranted(UUID uuid, LocalDate date, String status) {
        return mysql.update("UPDATE " + logTable()
                        + " SET `status` = ? WHERE `uuid` = ? AND `sign_date` = ? AND `status` = 'pending'",
                status, uuid.toString(), Date.valueOf(date)) > 0;
    }

    /** 本服/其它服遗留的待发奖记录。 */
    public List<SigninLog> pending(UUID uuid) {
        return mysql.query("SELECT `uuid`, `sign_date`, `kind`, `streak`, `reward_group`, `status`, `server_id`"
                        + " FROM " + logTable()
                        + " WHERE `uuid` = ? AND `status` = 'pending' ORDER BY `sign_date` LIMIT 50",
                rs -> new SigninLog(
                        UUID.fromString(rs.getString(1)),
                        rs.getDate(2).toLocalDate(),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7)),
                uuid.toString());
    }

    /** 指定区间内已签到的日期（月历渲染用）。 */
    public Set<LocalDate> signedDates(UUID uuid, LocalDate from, LocalDate to) {
        List<LocalDate> rows = mysql.query(
                "SELECT `sign_date` FROM " + logTable()
                        + " WHERE `uuid` = ? AND `sign_date` BETWEEN ? AND ?",
                rs -> rs.getDate(1).toLocalDate(),
                uuid.toString(), Date.valueOf(from), Date.valueOf(to));
        return new HashSet<>(rows);
    }

    // ==================================================================
    // 补签卡 / 统计 / 排行
    // ==================================================================

    /** 增加补签卡，返回最新数量；负数按 0 处理。 */
    public int addCards(UUID uuid, int delta) {
        int safe = Math.max(0, delta);
        mysql.update("UPDATE " + playerTable() + " SET `makeup_cards` = `makeup_cards` + ? WHERE `uuid` = ?",
                safe, uuid.toString());
        return cards(uuid);
    }

    /** 扣卡；数量不足返回 false。 */
    public boolean takeCards(UUID uuid, int amount) {
        if (amount <= 0) {
            return true;
        }
        return mysql.update("UPDATE " + playerTable()
                        + " SET `makeup_cards` = `makeup_cards` - ? WHERE `uuid` = ? AND `makeup_cards` >= ?",
                amount, uuid.toString(), amount) > 0;
    }

    public void setCards(UUID uuid, int amount) {
        mysql.update("UPDATE " + playerTable() + " SET `makeup_cards` = ? WHERE `uuid` = ?",
                Math.max(0, amount), uuid.toString());
    }

    public int cards(UUID uuid) {
        Integer value = mysql.queryOne("SELECT `makeup_cards` FROM " + playerTable() + " WHERE `uuid` = ?",
                rs -> rs.getInt(1), uuid.toString());
        return value == null ? 0 : value;
    }

    public int todayCount(LocalDate date) {
        Integer value = mysql.queryOne("SELECT `sign_count` FROM " + statsTable() + " WHERE `stat_date` = ?",
                rs -> rs.getInt(1), Date.valueOf(date));
        return value == null ? 0 : value;
    }

    /** 排行榜；field 只接受 streak / total（白名单，防注入）。 */
    public List<LeaderboardEntry> top(String field, int limit) {
        String order = "total".equalsIgnoreCase(field) ? "`total`" : "`streak`";
        int size = Math.max(1, Math.min(50, limit));
        return mysql.query("SELECT `uuid`, `name`, `streak`, `total`, `best_streak` FROM " + playerTable()
                        + " ORDER BY " + order + " DESC, `total` DESC, `updated_at` ASC LIMIT ?",
                rs -> new LeaderboardEntry(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getInt(4),
                        rs.getInt(5)),
                size);
    }

    public int purgeBefore(LocalDate date) {
        int logs = mysql.update("DELETE FROM " + logTable() + " WHERE `sign_date` < ?", Date.valueOf(date));
        mysql.update("DELETE FROM " + statsTable() + " WHERE `stat_date` < ?", Date.valueOf(date));
        mysql.update("DELETE FROM " + reminderTable() + " WHERE `reminder_date` < ?", Date.valueOf(date));
        return logs;
    }

    // ==================================================================
    // 邮箱
    // ==================================================================

    public void addMail(UUID uuid, String payload) {
        mysql.update("INSERT INTO " + mailboxTable() + " (`uuid`, `payload`) VALUES (?, ?)",
                uuid.toString(), payload);
    }

    public List<MailEntry> mail(UUID uuid, int limit) {
        return mysql.query("SELECT `id`, `uuid`, `payload`, UNIX_TIMESTAMP(`created_at`) FROM " + mailboxTable()
                        + " WHERE `uuid` = ? ORDER BY `id` ASC LIMIT ?",
                rs -> new MailEntry(
                        rs.getLong(1),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3),
                        rs.getLong(4) * 1000L),
                uuid.toString(), Math.max(1, Math.min(45, limit)));
    }

    public int mailCount(UUID uuid) {
        Integer value = mysql.queryOne("SELECT COUNT(*) FROM " + mailboxTable() + " WHERE `uuid` = ?",
                rs -> rs.getInt(1), uuid.toString());
        return value == null ? 0 : value;
    }

    public boolean deleteMail(long id, UUID uuid) {
        return mysql.update("DELETE FROM " + mailboxTable() + " WHERE `id` = ? AND `uuid` = ?",
                id, uuid.toString()) > 0;
    }

    /** 批量删除已领取邮件，避免逐条 DELETE。 */
    public int deleteMails(UUID uuid, Collection<Long> ids) {
        if (uuid == null || ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] params = new Object[ids.size() + 1];
        params[0] = uuid.toString();
        int index = 1;
        for (Long id : ids) {
            params[index++] = id;
        }
        return mysql.update("DELETE FROM " + mailboxTable()
                + " WHERE `uuid` = ? AND `id` IN (" + placeholders + ")", params);
    }

    // ==================================================================
    // 管理
    // ==================================================================

    /** 完全重置：档案归零 + 删除签到记录 + 清空邮箱。 */
    public void reset(UUID uuid) {
        String id = uuid.toString();
        mysql.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + logTable() + " WHERE `uuid` = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + mailboxTable() + " WHERE `uuid` = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + reminderTable() + " WHERE `uuid` = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + playerTable()
                            + " (`uuid`, `name`, `last_sign_date`, `streak`, `best_streak`, `total`, `makeup_cards`)"
                            + " VALUES (?, '', NULL, 0, 0, 0, 0)"
                            + " ON DUPLICATE KEY UPDATE `last_sign_date` = NULL, `streak` = 0,"
                            + " `best_streak` = 0, `total` = 0, `makeup_cards` = 0")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setStreak(UUID uuid, int streak) {
        mysql.update("UPDATE " + playerTable() + " SET `streak` = ?, `best_streak` = GREATEST(`best_streak`, ?)"
                + " WHERE `uuid` = ?", Math.max(0, streak), Math.max(0, streak), uuid.toString());
    }

    public void setTotal(UUID uuid, int total) {
        mysql.update("UPDATE " + playerTable() + " SET `total` = ? WHERE `uuid` = ?",
                Math.max(0, total), uuid.toString());
    }

    public void setName(UUID uuid, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        mysql.update("INSERT INTO " + playerTable() + " (`uuid`, `name`) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)", uuid.toString(), name);
    }

    private static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql
                    && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class DuplicateSignException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
