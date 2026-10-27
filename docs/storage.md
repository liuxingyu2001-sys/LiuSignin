# 存储与数据库

## 1. 设计：凭据复用，数据隔离

LiuSignin 是跨服签到插件，数据必须放在共享 MySQL 中。1.0.6 起的存储模型：

| 项 | 归属 |
|---|---|
| host / port / username / password / use-ssl / 连接池 | LiuLib 的 `mysql` 段 |
| 库名 | 本插件 `database.name`（默认 `liusignin`） |
| 表前缀 | 本插件 `database.table-prefix`（默认 `liusignin_`） |
| 自动建库、超时、日志保留 | 本插件 `config.yml: database` |

这样改一次数据库密码只需要改 LiuLib；数据又不会和 LiuLib 自己的表混在同一个库。

```yaml
database:
  name: 'liusignin'
  table-prefix: 'liusignin_'
  auto-create-database: true
  pool-size: 0
  connection-timeout-ms: 0
  migrate-from-liulib: true
  log-retain-days: 180
```

- `pool-size` / `connection-timeout-ms` 为 `0` 时跟随 LiuLib。
- `auto-create-database` 需要账号有 `CREATE` 权限；失败会记录错误并停用插件。
- 仅支持 MySQL，没有 SQLite 回退；LiuLib MySQL 未启用时插件停用。

## 2. 从旧版本迁移

旧版本（≤ 1.0.5）把 `liusignin_*` 表写在 LiuLib 的库（通常是 `liulib`）里。1.0.6 首次启动时：

1. 连接 LiuLib 的库，读取 `information_schema.TABLES`；
2. 对 5 张表逐一检查新库是否已有数据；已有数据则跳过，缺表或空表则迁移；
3. 缺表时 `CREATE TABLE 新库.表 LIKE 旧库.表`，再按两表同名列 `INSERT IGNORE ... SELECT`；
4. 每张表记录迁移行数；失败只告警，不阻断启动；
5. 重复启动不会重复迁移；`log.total` 等新列由 `SigninStore.init()` 自动补齐。

相关日志：

```text
[LiuSignin] MySQL 已连接: 127.0.0.1:3306/liusignin（表前缀 liusignin_）
[LiuSignin] 已迁移表 liulib.liusignin_player → liusignin.liusignin_player（1 行）
[LiuSignin] 未发现需要迁移的旧表（liulib 库）
```

迁移完成后旧库的表仍保留，确认无误后可手动清理。关闭迁移：`database.migrate-from-liulib: false`。

## 3. 表结构

默认库 `liusignin`，前缀 `liusignin_`：

| 表 | 关键字段 | 用途 |
|---|---|---|
| `liusignin_player` | `uuid` PK, `name`, `last_sign_date`, `streak`, `best_streak`, `total`, `makeup_cards`, `updated_at` | 玩家档案 |
| `liusignin_log` | `uuid` + `sign_date` 复合主键, `kind`, `streak`, `total`, `reward_group`, `status`, `server_id`, `created_at` | 签到记录、防重复闸门、发奖状态、断线补发档位 |
| `liusignin_stats` | `stat_date` PK, `sign_count` | 每日签到人数 |
| `liusignin_mailbox` | `id` PK, `uuid`, `payload`, `created_at` | 暂存奖励（物品 base64 / 金币 / 经验） |
| `liusignin_reminder` | `uuid` + `reminder_date` 复合主键, `created_at` | 进服提醒去重 |

索引：

- `player`：`idx_streak`、`idx_total`、`idx_rank_streak (streak,total,updated_at)`、`idx_rank_total`
- `log`：`idx_date`、`idx_status`、`idx_uuid_status (uuid,status,sign_date)`
- `mailbox`：`idx_uuid`、`idx_uuid_id (uuid,id)`
- `reminder`：`idx_reminder_date`

老库启动时会自动补建复合索引（`information_schema` 检查；DDL 失败只告警，不阻断启动）。

## 4. 并发与一致性

- 是否签到成功只由 `liusignin_log (uuid, sign_date)` 主键裁决：多台子服同时点，只有一条能插入。
- 玩家档案累加与日志写入在同一事务内完成（`SigninStore.beginSign/beginMakeup`）。
- 奖励状态：先写 `pending`，发奖成功后标记 `granted`；两步之间崩溃时，下次进服按
  `recovery.retry-pending` 补发（极小概率重复发奖，换取不丢奖）。
- 写操作完成后通过 Redis 发布 `serverId;uuid` 失效消息；其他子服丢弃该玩家缓存。
- 无 Redis 时功能正常，只是他服修改要等缓存 TTL（120 秒）或玩家重新登录。

## 5. 多服部署

1. 所有子服使用**同一个** `database.name`（默认 `liusignin`）与 `database.table-prefix`；
   连接地址/账号由 LiuLib 统一提供。
2. `settings.timezone` / `settings.reset-time` / `rewards.yml` 保持一致。
3. Redis（推荐）：各服连同一个 Redis，用于缓存失效广播。
4. 签到、补签、邮箱都读写同一套表，无需额外的同步脚本。

## 6. 运维

- 连接自检：`/signin dbtest`。
- 日志清理：`/signin purge [天数]`，默认使用 `database.log-retain-days`（180 天，`0` = 不限制）；
  同时清理 `liusignin_log` 与 `liusignin_stats`。
- 备份：`mysqldump liusignin > liusignin.sql`，包含玩家档案、日志、邮箱、提醒与统计。
- 修改 `database.name` 需要重启；修改 `table-prefix` 会在 reload 时切换到新表（不迁移旧数据）。
- `shared-config-path` 指向共享目录时，数据库配置也以共享目录里的 `config.yml` 为准。

## 7. 数据存储优化（1.0.5+）

- 玩家档案读取改为「先 SELECT，命中即返回」，日常加载不再每次执行 `INSERT IGNORE` 写库。
- 签到日志保存 `total`，断线补发不再用连续天数冒充累计天数。
- 排行榜、待发奖、邮箱列表使用复合索引，避免全表排序。
- 邮箱一键领取改为一条 `DELETE ... WHERE uuid = ? AND id IN (...)` 批量清理。
- 提醒去重单独建表，不污染玩家档案；`/signin reset` 与 `/signin purge` 会同步清理。

## 8. 实测

- 临时 MariaDB（127.0.0.1:3307），旧库预置 `liusignin_player` 一行：
  - 启动日志显示 `MySQL 已连接 .../liusignin`、`已迁移表 liulib.liusignin_player → liusignin.liusignin_player（1 行）`；
  - `SHOW TABLES FROM liusignin` 得到 5 张表，旧行保留；
  - 游戏内签到成功，数据写入新库；`/signin dbtest` 返回“数据库连接正常”；
  - 再次重启：`未发现需要迁移的旧表`，行数不变（幂等）。
