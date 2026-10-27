# LiuSignin

Minecraft **每日签到**插件（Paper 1.21+）：月历 / 无限递增两套奖励模式、补签卡、签到邮箱、跨服 MySQL 共享数据、
Redis 缓存失效、Sparrow UI 界面。

- 包名：`com.liuxingyu.liusignin`
- 命令：`/signin`（别名 `/sign`、`/qd`、`/qiandao`）
- 版本：`1.0.7`
- 硬依赖：[LiuLib](../LiuLib) 1.0.5+（MySQL 连接凭据 / Redis / Sparrow UI / 物品规格）
- 软依赖：Vault、PlaceholderAPI、Redis、[liuitemlib](../liuitemlib)、CraftEngine

## 功能

| 模块 | 说明 |
|---|---|
| 月历签到 | 6×7 日期网格 + 单列侧栏，按日期号发奖；翻月、漏签、今日高亮 |
| 无限递增 | 按连续签到天数递增金币/经验，可设上限与阶段奖励 |
| 奖励组 | 按权限 + priority 匹配，例如普通玩家走月历、VIP 走无限递增 |
| 补签 | 消耗补签卡补最近 N 天（可跨月，受 `max-days` 限制）；紧邻上次签到时可续连 |
| 签到邮箱 | 背包装不下的物品、Vault 不可用时的金币自动暂存，可单领/一键领取 |
| 多服共享 | 所有数据在 MySQL，`(uuid, sign_date)` 唯一键裁决并发；Redis Pub/Sub 通知他服丢缓存 |
| 自动签到 / 提醒 | 进服延迟自动签到；未签到收到多行提醒（随机文案），点击打开月历；同一天跨子服只提醒一次 |
| 排行 / 统计 | 连续、累计排行榜；今日签到人数 |
| 共享配置 | 多台子服共用同一份 yml，自动复制、补键与热重载 |
| PlaceholderAPI | `%liusignin_*%` 变量 |
| 开发者接口 | `LiuSigninApi` 服务 + `PlayerSigninEvent` 事件 |

> GUI 只使用 LiuLib 的 **Sparrow UI**，没有原版 Inventory 回退；Sparrow 不可用时相关界面会提示错误。

## 环境要求

- Paper **1.21+**（`api-version: 1.21`，Java 21）
- **LiuLib 1.0.5+**，并在 `LiuLib/config.yml` 中启用 MySQL：

  ```yaml
  mysql:
    enabled: true
    host: 127.0.0.1
    port: 3306
    username: liulib
    password: '******'
  ```

  LiuSignin 只复用这段的连接凭据；数据写在它自己的库 `liusignin`（首次启动自动建库建表），
  不再往 LiuLib 的 `liulib` 库里加表。LiuLib MySQL 未启用时插件会停用并提示。
- 可选：Vault + 经济插件、PlaceholderAPI、Redis、liuitemlib、CraftEngine

不支持 SQLite；单服同样使用 MySQL。

## 快速开始

1. 把 `LiuLib-1.0.5.jar` 与 `LiuSignin-1.0.7.jar` 放入 `plugins/`。
2. 在 LiuLib 中启用 MySQL 并重启。
3. 启动服务器，生成 `plugins/LiuSignin/{config.yml,rewards.yml,menus.yml,messages.yml}`。
4. 按需修改配置，执行 `/signin reload`。

## 文档

| 文档 | 内容 |
|---|---|
| [安装与升级](docs/installation.md) | 依赖、首次安装、1.0.5 → 1.0.7 数据库迁移、共享配置目录 |
| [命令与权限](docs/commands.md) | 全部命令、权限节点、别名 |
| [配置说明](docs/configuration.md) | `config.yml` / `rewards.yml` / `menus.yml` / `messages.yml` |
| [存储与数据库](docs/storage.md) | 独立库设计、旧库迁移、表结构、索引、多服并发 |
| [PlaceholderAPI](docs/placeholders.md) | 变量列表与示例 |
| [开发者 API](docs/api.md) | `LiuSigninApi`、`PlayerSigninEvent`、线程约定 |
| [开发与验证](docs/development.md) | 构建、测试、目录结构、设计限制 |

## 版本说明

### 1.0.7

- **修复断线补发奖励档位错误**：签到日志新增 `total` 列，补发时按当时累计天数计算；旧表启动时自动补列。
- **邮箱领取改为整条原子交付**：背包放不下时整条保留，不再异步回写剩余物品，避免崩溃/断服时物品丢失或重复；
  “一键领取”会跨多个批次领完，直到邮箱空或背包满。
- **future 不再挂起**：异步结果返回前玩家离线/切服时，签到、补签、邮箱等 future 会异常结束；命令与菜单统一显示失败提示。
- **跨服提醒复核**：发送提醒前后都用签到日志确认，避免另一子服刚签到却仍收到提醒。
- **迁移更稳**：旧库迁移按两表同名列复制，目标表为空时可补迁移，非空目标表不覆盖；
  新增 `log.total` 列由插件自动补齐。
- **其他**：补签卡数量参数校验、API `cachedProfile` 严格按缓存 TTL、离线玩家状态定时清理、
  奖励 DSL 拒绝 `NaN`/`Infinity`、奖励命令去掉前导 `/`。

### 1.0.6

- **数据库隔离**：改为使用本插件自己的 `liusignin` 库 + `liusignin_` 前缀，连接凭据仍读 LiuLib。
- 首次启动自动把旧 `liulib` 库中的 `liusignin_*` 表复制到新库（只补缺失表，可用
  `database.migrate-from-liulib` 关闭）。
- 新增 `database.name` / `auto-create-database` / `pool-size` / `connection-timeout-ms` 配置。
- 修改 `database.name` 需重启服务器才会切换数据库。

### 1.0.5 及之前

数据表写在 LiuLib 的 `liulib` 库中，仅靠表前缀隔离；升级到 1.0.6 时自动迁移。
