package com.liuxingyu.liusignin.data;

import com.liuxingyu.liulib.storage.MySql;
import com.liuxingyu.liulib.storage.SqlConfig;
import com.liuxingyu.liusignin.config.SigninConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 签到库的初始化与旧库迁移。
 *
 * <p>连接参数（host / port / username / password / use-ssl）复用 LiuLib 的
 * {@code mysql} 段；本插件只决定自己的库名（默认 {@code liusignin}）和表前缀
 * （默认 {@code liusignin_}），不再把表写进 LiuLib 的 {@code liulib} 库。</p>
 */
public final class SigninDatabase {

    private SigninDatabase() {
    }

    /**
     * 基于 LiuLib 的连接凭据建立本插件自己的连接池。
     *
     * @return 初始化失败返回 {@code null}
     */
    public static MySql open(SqlConfig library, SigninConfig config, Logger logger) {
        SqlConfig target = SqlConfig.builder()
                .enabled(true)
                .host(library.host())
                .port(library.port())
                .database(config.databaseName())
                .username(library.username())
                .password(library.password())
                .tablePrefix(config.tablePrefix())
                .poolSize(config.poolSize() > 0 ? config.poolSize() : library.poolSize())
                .connectionTimeoutMs(config.connectionTimeoutMs() > 0
                        ? config.connectionTimeoutMs()
                        : library.connectionTimeoutMs())
                .useSsl(library.useSsl())
                .autoCreateDatabase(config.autoCreateDatabase())
                .build();
        MySql mysql = new MySql(target, logger);
        if (!mysql.init()) {
            mysql.close();
            return null;
        }
        return mysql;
    }

    /**
     * 首次升级时，把旧版留在 LiuLib 库（通常 {@code liulib}）里的 {@code liusignin_*} 表
     * 复制到新库。只复制“新库不存在”的表，不会覆盖新库已有数据。
     */
    public static void migrate(MySql library, MySql target, String prefix, Logger logger) {
        String sourceDb = library.config().database();
        String targetDb = target.config().database();
        if (sourceDb.equalsIgnoreCase(targetDb)) {
            return;
        }
        int copied = 0;
        for (String name : SigninStore.TABLES) {
            String table = prefix + name;
            try {
                if (exists(target, targetDb, table)) {
                    continue;
                }
                if (!exists(target, sourceDb, table)) {
                    continue;
                }
                target.execute("CREATE TABLE `" + targetDb + "`.`" + table + "` LIKE `"
                        + sourceDb + "`.`" + table + "`");
                int rows = target.update("INSERT IGNORE INTO `" + targetDb + "`.`" + table
                        + "` SELECT * FROM `" + sourceDb + "`.`" + table + "`");
                copied++;
                logger.info("已迁移表 " + sourceDb + "." + table + " → "
                        + targetDb + "." + table + "（" + rows + " 行）");
            } catch (RuntimeException error) {
                logger.log(Level.WARNING, "迁移表 " + table + " 失败；旧数据仍在 " + sourceDb
                        + " 库，可手动复制到 " + targetDb + " 库", error);
            }
        }
        if (copied == 0) {
            logger.info("未发现需要迁移的旧表（" + sourceDb + " 库）");
        }
    }

    private static boolean exists(MySql mysql, String database, String table) {
        Boolean value = mysql.queryOne(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                rs -> rs.getInt(1) > 0, database, table);
        return Boolean.TRUE.equals(value);
    }
}
