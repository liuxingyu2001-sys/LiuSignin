# LiuSignin

Minecraft **每日签到**插件（Paper 1.21+）。月历 / 无限递增两套奖励模式、补签卡、签到邮箱、跨服 MySQL 共享数据、Redis 缓存失效、Sparrow UI 界面。

- 包名：`com.liuxingyu.liusignin`
- 命令：`/signin`（别名 `/sign`、`/qd`、`/qiandao`）
- 版本：`1.0.2`
- 硬依赖：[LiuLib](../LiuLib)（MySQL 连接池 / Redis / Sparrow UI / 物品解析）

---

## 功能

| 模块 | 说明 |
| --- | --- |
| 月历签到 | 6×7 日期网格 + 单列侧栏，按日期号发奖；翻月、漏签、今日高亮 |
| 无限递增 | 按连续签到天数递增金币/经验，可设上限与阶段奖励 |
| 奖励组 | 按权限 + priority 匹配，例如普通玩家走月历、VIP 走无限递增 |
| 补签 | 消耗补签卡补最近 N 天；紧邻上次签到时可续连 |
| 签到邮箱 | 背包装不下的物品、Vault 不可用时的金币自动暂存，可单领/一键领取 |
| 多服共享 | 所有数据在 MySQL，`(uuid, sign_date)` 唯一键裁决并发；Redis Pub/Sub 通知他服丢缓存 |
| 自动签到 / 提醒 | 进服延迟自动签到、未签到点击提醒 |
| 排行 / 统计 | 连续、累计排行榜；今日签到人数 |
| PlaceholderAPI | `%liusignin_*%` 变量 |
| 开发者事件 | `PlayerSigninEvent`（成功入库、发奖前触发） |

> GUI 只使用 LiuLib 的 **Sparrow UI**，没有原版 Inventory 回退。Sparrow 不可用时相关界面会提示错误。

---

## 环境要求

- Paper **1.21+**（`api-version: 1.21`，Java 21）
- **LiuLib 1.0.0+**，且在 `LiuLib/config.yml` 中启用 MySQL：
  ```yaml
  mysql:
    enabled: true
    host: 127.0.0.1
    port: 3306
    database: liulib
    username: root
    password: '你的密码'
  ```
- 可选：Vault + 经济插件（金币奖励；没有时金币转入签到邮箱）
- 可选：PlaceholderAPI
- 可选：Redis（跨服缓存失效；没有也能运行，只是他服数据要等重新登录/缓存过期）

不支持 SQLite；单服同样使用 MySQL。

---

## 安装

1. 把 `LiuLib-1.0.0.jar` 与 `LiuSignin-1.0.2.jar` 放入 `plugins/`。
2. 启动一次服务器，生成 `plugins/LiuSignin/config.yml`、`rewards.yml`、`menus.yml`、`messages.yml`。
3. 按需修改配置，`/signin reload` 重载（表前缀变更会重新建表）。
4. 多台子服共用同一个 MySQL 库，并保持 `timezone`、`reset-time`、`table-prefix` 完全一致。

---

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/signin` | 打开签到月历 | `liusignin.gui` |
| `/signin claim` | 直接签到（`sign` / `checkin` 同义） | `liusignin.sign` |
| `/signin menu` | 打开月历（`gui` / `calendar` 同义） | `liusignin.gui` |
| `/signin makeup [yyyy-MM-dd]` | 补签，缺省为昨天；也支持 `昨天` | `liusignin.makeup` |
| `/signin mail [all]` | 打开邮箱；`all` 一键领取 | `liusignin.mail` |
| `/signin top [streak\|total]` | 查看连续 / 累计排行 | `liusignin.leaderboard` |
| `/signin info [玩家]` | 查看签到档案 | 无 |
| `/signin help` | 帮助 | 无 |
| `/signin reload` | 重载配置 | `liusignin.admin` |
| `/signin card give\|take\|set <玩家> <数量>` | 补签卡管理 | `liusignin.admin` |
| `/signin reset <玩家>` | 清空档案、记录、邮箱 | `liusignin.admin` |
| `/signin setstreak <玩家> <天数>` | 设置连续天数 | `liusignin.admin` |
| `/signin settotal <玩家> <天数>` | 设置累计天数 | `liusignin.admin` |
| `/signin purge [天数]` | 清理早于 N 天的日志与统计（默认取配置） | `liusignin.admin` |
| `/signin dbtest` | 测试 MySQL 连接 | `liusignin.admin` |

### 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `liusignin.sign` | true | 允许签到 |
| `liusignin.gui` | true | 打开签到界面 |
| `liusignin.makeup` | true | 使用补签 |
| `liusignin.mail` | true | 打开签到邮箱 |
| `liusignin.leaderboard` | true | 查看排行 |
| `liusignin.reward.vip` | false | 匹配 VIP 奖励组 |
| `liusignin.bypass.cooldown` | op | 跳过签到冷却 |
| `liusignin.admin` | op | 管理命令 |
| `liusignin.*` | op | 全部权限（含 admin） |

---

## 配置

### `config.yml`

关键项：

```yaml
settings:
  timezone: 'Asia/Shanghai'   # 签到日时区，多服必须一致
  reset-time: '00:00'         # 每日重置；04:00 表示凌晨 4 点前算前一天
  auto-sign:
    enabled: false
    delay-ticks: 40
  broadcast:
    sign: false               # 每次签到都广播
    first-of-day: true        # 每天第一个签到广播
  reminder:
    on-join: true
    delay-ticks: 60
  cooldown-ms: 1500
  leaderboard-refresh-seconds: 30
  mailbox:
    auto-claim-on-join: true
    claim-batch: 45

database:
  table-prefix: 'liusignin_'   # 多服必须一致
  log-retain-days: 180

makeup:
  enabled: true
  max-days: 7
  cards-per-makeup: 1
  keep-streak: true            # 补“昨天”时续接连续天数
  reward-from-date: true       # 按补签日期发奖；false = 按今天档位

recovery:
  retry-pending: true          # 进服补发“已入库未发奖”的记录

rewards:
  fallback: []                 # 奖励组算不出奖励时的兜底 DSL
```

签到日算法：`今天 = 配置时区的日期`；若当地时刻早于 `reset-time`，日期减一天。多台子服必须使用相同的 `timezone` 与 `reset-time`，否则会出现“这服能签、那服不能签”。

### `rewards.yml`

奖励字符串 DSL：

```text
money:1000                  Vault 金币
exp:50                      经验点
card:1                      补签卡
item:DIAMOND*3              原版物品（* 或 : 表示数量）
item:ce:namespace:id*1      CraftEngine 物品
item:base64:....            LiuLib 完整物品编码
cmd:console: say hi         控制台执行（默认 console）
cmd:player: menu            玩家身份执行
```

可用占位符：`{player}` `{uuid}` `{streak}` `{total}` `{date}`。

奖励组：

```yaml
groups:
  default:
    priority: 0
    permission: ''
    mode: calendar           # calendar = 按日期号；infinite = 按连续天数
    calendar:
      default: ['money:500']
      1: ['money:500']
      30: ['money:5000', 'item:DIAMOND*32', 'card:1']

  vip:
    priority: 100
    permission: 'liusignin.reward.vip'
    mode: infinite
    infinite:
      money: { base: 1000, per-day: 200, max: 10000 }
      exp: { base: 10, per-day: 5, max: 300 }
      extras: ['card:1']
      tiers:
        7: ['item:GOLD_INGOT*16']
        30: ['item:NETHERITE_INGOT*2']
```

- 组按 `priority` 从高到低匹配；有 `permission` 的组要求玩家拥有该权限。
- `calendar` 取 `日期号` 的奖励；未配置该日期时用 `default`，没有 `default` 则沿用最近的前一个档位。
- `infinite` 金币/经验按 `base + per-day × (streak-1)` 递增并受 `max` 限制；`tiers` 为达到天数后每天叠加。

### `menus.yml`

月历采用「左侧 7 列日期 + 右侧单列侧栏」：侧栏只保留签到统计、补签卡、今日奖励、排行、邮箱、翻月和关闭，日期格子不再塞长奖励文本，改用染料颜色区分状态（绿=已签、红=漏签、灰=未来、绿宝石=今天）。

物品支持 `material` 或 LiuLib `item` 规格，名称/lore 使用 MiniMessage，并统一关闭物品默认斜体。

`menus.yml` 带有 `menus-version`。插件升级检测到旧版结构时，会把旧文件备份为 `menus-v<旧版本>-backup.yml`，再写入新版默认布局；自定义过的旧配置可从备份中手动合并。

### `messages.yml`

MiniMessage 格式，`{player}`、`{streak}`、`{rewards}` 等占位符。修改后 `/signin reload` 生效。

---

## PlaceholderAPI

`%liusignin_<key>%`：

| key | 说明 |
| --- | --- |
| `signed` / `signed_today` | 今日是否已签（`true` / `false`） |
| `streak` | 连续天数 |
| `total` | 累计天数 |
| `best` | 历史最佳连续 |
| `cards` | 补签卡数量 |
| `today_count` | 今日签到人数（异步缓存） |
| `rank_streak` / `rank_total` | 排行榜名次，未加载为 0 |
| `month` | 当前签到月 `yyyy-MM` |
| `next_reward` | 今日/下次签到奖励预览 |
| `last_sign` | 上次签到日期 |

示例：`%liusignin_streak%`、`%liusignin_next_reward%`。

---

## 多服部署

1. 所有子服连接**同一个** MySQL 数据库，`database.table-prefix` 一致。
2. `settings.timezone` / `settings.reset-time` / `rewards.yml` 保持一致。
3. Redis（推荐）：各服连同一个 Redis；插件会发布 `serverId;uuid` 失效消息，收到后丢弃该玩家缓存。
4. 并发安全：签到是否成功只由 `liusignin_log (uuid, sign_date)` 主键决定，多服同时点也只有一条成功；玩家档案在同一事务内累加。
5. 无 Redis 时功能仍正常，只是他服修改要等缓存 TTL（120 秒）或玩家重新登录。

数据表（前缀默认 `liusignin_`）：

| 表 | 用途 |
| --- | --- |
| `liusignin_player` | 玩家档案：连签、最佳、累计、补签卡 |
| `liusignin_log` | 签到记录，`(uuid, sign_date)` 主键，兼作防重复闸门与发奖状态 |
| `liusignin_stats` | 每日签到人数 |
| `liusignin_mailbox` | 暂存奖励（物品 base64 / 金币 / 经验） |

---

## 开发者

### 事件

```java
@EventHandler
public void onSignin(PlayerSigninEvent event) {
    Player player = event.player();
    LocalDate date = event.date();
    int streak = event.streak();
    boolean makeup = event.makeup();
}
```

事件在主线程、数据库已写入之后、奖励发放之前触发，不可取消。

### 构建

```bash
mvn clean package
# 产物：target/LiuSignin-1.0.2.jar
```

单元测试覆盖奖励 DSL、月历/无限奖励计算、签到日重置逻辑：

```bash
mvn test
```

依赖均为 `provided`，jar 内只有本插件类与资源，不 shade LiuLib / Vault / PlaceholderAPI。

### 设计说明与已知限制

- 奖励先入库 `pending`，主线程发奖成功后标记 `granted`；服务器在两步之间崩溃时，下次进服按 `recovery.retry-pending` 补发。极端情况下可能重复发奖（用极小概率换不丢奖）。
- 签到冷却按子服内存计算，跨服不共享。
- 月历回看加载最近 400 天记录；更早的日期会显示为未签到。
- 仅支持 Paper，`folia-supported: false`。
- 修改表前缀后旧表不会自动迁移；需要手动迁移或保留原前缀。

---

## 目录结构

```text
src/main/java/com/liuxingyu/liusignin/
├── LiuSigninPlugin.java        # 启动、装配、reload
├── api/event/                  # PlayerSigninEvent、SignSource
├── command/                    # /signin 与 Tab 补全
├── config/                     # config.yml 快照
├── data/                       # MySQL 数据层与模型
├── hook/                       # Vault（反射）、PlaceholderAPI
├── listener/                   # 进服加载/自动签到/提醒/补偿
├── menu/                       # Sparrow 月历、补签、邮箱
├── reward/                     # 奖励 DSL、奖励组、奖励表
├── service/                    # 签到、发奖、邮箱
├── sync/                       # Redis 跨服失效
├── time/                       # 签到日计算
└── util/                       # 消息、占位符、调度
```
