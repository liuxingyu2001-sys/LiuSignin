# 安装与升级

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21+（`folia-supported: false`） |
| Java | 21 |
| 硬依赖 | LiuLib 1.0.5+，且已启用 MySQL |
| 可选 | Vault + 经济插件、PlaceholderAPI、Redis、liuitemlib、CraftEngine |
| 数据库 | 仅 MySQL，不支持 SQLite |

LiuSignin **不再自己维护账号密码**：host / port / username / password / use-ssl 全部读 LiuLib 的 `mysql` 段；
本插件只决定自己的库名（默认 `liusignin`）与表前缀（默认 `liusignin_`）。

## 2. 配置 LiuLib

`plugins/LiuLib/config.yml`：

```yaml
mysql:
  enabled: true
  host: 127.0.0.1
  port: 3306
  username: liulib
  password: '******'
  # 可选：database / table-prefix / pool-size / connection-timeout-ms / use-ssl
```

LiuLib 的 `mysql.database` 只决定 LiuLib 自己用哪个库，LiuSignin 不会往里写表。

若 LiuLib MySQL 未启用或连接失败，LiuSignin 会输出：

```text
[LiuSignin] LiuLib 的 MySQL 未启用或连接失败，请在 LiuLib/config.yml 配置 mysql 后重启
```

随后插件停用。这是预期行为（本插件没有 SQLite 回退）。

## 3. 安装步骤

1. 把 `LiuLib-1.0.5.jar` 与 `LiuSignin-1.0.7.jar` 放入 `plugins/`。
2. 配置 LiuLib 的 MySQL 并启动服务器。
3. 首次启动会自动：
   - 创建 `liusignin` 库（`database.auto-create-database: true`，账号需要 `CREATE` 权限）；
   - 创建 5 张 `liusignin_*` 表；
   - 生成 `plugins/LiuSignin/{config.yml,rewards.yml,menus.yml,messages.yml}`。
4. 按需修改配置，执行 `/signin reload`；数据库名/连接参数改动需要重启。

## 4. 从 1.0.5 升级（数据库迁移）

1.0.5 及之前版本把表写在 LiuLib 的 `liulib` 库里，仅靠表前缀隔离。1.0.6 起：

- 连接凭据仍是 LiuLib 的 `mysql` 段；
- 数据写入本插件自己的 `database.name`（默认 `liusignin`）；
- `database.migrate-from-liulib: true`（默认）时，首次启动自动迁移：

| 行为 | 说明 |
|---|---|
| 迁移范围 | `liusignin_player` / `liusignin_log` / `liusignin_stats` / `liusignin_mailbox` / `liusignin_reminder` |
| 迁移方式 | 新库缺表或空表时 `CREATE TABLE ... LIKE`，再按两表同名列 `INSERT IGNORE ... SELECT` |
| 不覆盖 | 新库已有表且已有数据时跳过，不会动新库已有数据 |
| 同库跳过 | 若 LiuLib 库名与本插件库名相同，不迁移 |
| 失败处理 | 单表失败只告警并继续；旧数据仍保留在旧库，可手动复制 |
| 幂等 | 重复启动不会重复迁移（非空表跳过） |

迁移成功后旧 `liulib` 库中的 `liusignin_*` 表**不会自动删除**，确认数据无误后可手动清理。

1.0.7 起 `liusignin_log` 新增 `total` 列；旧表启动时自动执行 `ALTER TABLE` 补齐，无需手动迁移。
若 MySQL 账号没有 `ALTER` 权限，请先手动执行：

```sql
ALTER TABLE liusignin_log ADD COLUMN `total` INT NOT NULL DEFAULT 0 AFTER `streak`;
```

权限要求：LiuSignin 使用的 MySQL 账号需要能读旧库（`SELECT`）并在新库建表（`CREATE` / `INSERT`）。

关闭迁移：

```yaml
database:
  migrate-from-liulib: false
```

## 5. 共享配置目录

多台子服可以共用一份配置。各服本地 `plugins/LiuSignin/config.yml` 只负责指路：

```yaml
shared-config-path: '/mc/shared/liusignin-config'
```

- 留空时自动检测 `/mc/shared/liusignin-config`，目录存在才启用；否则使用本服 `plugins/LiuSignin`。
- 显式路径不存在时会自动创建；创建失败则回退本服目录。
- 启用后 `config.yml`、`rewards.yml`、`menus.yml`、`messages.yml` 全部从共享目录读取，首次自动复制默认文件。
- `shared-config-path` 只从**本服本地** `config.yml` 读取；共享 `config.yml` 里的同键不会反向影响路径选择。
- 共享 `config.yml` 新增但缺失的键会自动补全，不覆盖已有值。
- 本服 `config.yml` 的 `shared-config-watch: true`（默认）会监听共享目录及本服 bootstrap 的变化并自动重载；
  改完路径也可以手动 `/signin reload`（会重新解析配置根目录）。

## 6. 重载与重启

`/signin reload` 会重新读取配置、奖励表、菜单、消息、时区与重置时间，并重建缓存；共享配置目录也会重新解析。

以下改动需要**重启服务器**：

| 改动 | 原因 |
|---|---|
| `database.name` | 连接池在 `onEnable` 时按库名建立；reload 仅告警，不会切换 |
| LiuLib 的 MySQL / Redis 连接参数 | 由 LiuLib 在启动阶段初始化 |
| 新增/移除软依赖插件 | 依赖探测在启动阶段完成 |

`database.table-prefix` 在 reload 时会切换并使用新前缀建表，但**不会迁移旧前缀的数据**；已有数据请保持前缀不变。
