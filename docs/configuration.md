# 配置说明

插件配置位于 `plugins/LiuSignin/`（启用共享配置目录时位于该目录）：

- `config.yml`：运行参数与数据库
- `rewards.yml`：奖励 DSL 与奖励组
- `menus.yml`：Sparrow UI 布局
- `messages.yml`：提示文本

修改后 `/signin reload` 生效；`database.name` 与连接参数需要重启，见 [安装与升级](installation.md)。

## 1. config.yml

### 共享配置

```yaml
shared-config-path: ''    # 留空自动检测 /mc/shared/liusignin-config
shared-config-watch: true # 共享目录/本服 bootstrap 变化时自动重载
```

`shared-config-path` 只从本服本地 `config.yml` 读取，详细规则见 [安装与升级](installation.md#5-共享配置目录)。

### settings

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
    once-per-day: true        # true = 同一签到日跨子服只提醒一次
  cooldown-ms: 1500           # 防连点间隔
  leaderboard-refresh-seconds: 30
  mailbox:
    auto-claim-on-join: true
    claim-batch: 45
  debug: false                # 调试日志
```

签到日算法：`今天 = 配置时区的日期`；若当地时刻早于 `reset-time`，日期减一天。多台子服必须使用相同的
`timezone` 与 `reset-time`，否则会出现“这服能签、那服不能签”。

### database

```yaml
database:
  name: 'liusignin'              # 本插件自己的库
  table-prefix: 'liusignin_'     # 多服必须一致
  auto-create-database: true     # 库不存在时自动创建
  pool-size: 0                   # 0 = 跟随 LiuLib
  connection-timeout-ms: 0       # 0 = 跟随 LiuLib
  migrate-from-liulib: true      # 首次升级时迁移旧 liulib 库的表
  log-retain-days: 180           # /signin purge 默认保留天数，0 = 不限制
```

连接地址与账号仍来自 LiuLib，详见 [存储与数据库](storage.md)。

### makeup

```yaml
makeup:
  enabled: true
  max-days: 7               # 最多补最近 N 天（不含今天），可跨月
  cards-per-makeup: 1       # 每次消耗补签卡
  keep-streak: true         # 补“昨天”等紧邻日期时续接连续天数
  reward-from-date: true    # 按补签日期发奖；false = 按今天档位
```

补签范围只由 `max-days` 决定，不限制自然月。若希望只能补本月，需要在 `SigninService.makeup` 另外加月份校验。

### recovery / rewards

```yaml
recovery:
  retry-pending: true   # 进服补发“已入库未发奖”的记录
rewards:
  fallback: []          # 奖励组算不出奖励时的兜底 DSL
```

## 2. rewards.yml

### 奖励字符串 DSL

```text
money:1000                  Vault 金币
exp:50                      经验点
card:1                      补签卡
item:DIAMOND*3              原版物品（* 或 : 表示数量）
item:ce:namespace:id*1      CraftEngine 物品
item:base64:....            LiuLib 完整物品编码（CE/NBT/数据组件全保真）
item:nbt:....               LiuLib 完整物品编码（旧写法，等价 base64）
item:item:base64:....       带 item: 前缀的完整物品规格（兼容旧配置）
item:lib:gift:spring_2026   物品库引用（需安装 liuitemlib）
cmd:console: say hi         控制台执行（默认 console）
cmd:player: menu            玩家身份执行
```

可用占位符：`{player}` `{uuid}` `{streak}` `{total}` `{date}`。

### 奖励组

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
- `calendar` 取日期号对应奖励；未配置该日期时用 `default`，没有 `default` 则沿用最近的前一个档位。
- `infinite` 金币/经验按 `base + per-day × (streak-1)` 递增并受 `max` 限制；`tiers` 为达到天数后每天叠加。
- 奖励先入库 `pending`，发奖成功后标记 `granted`；两步之间崩溃时下次进服按 `recovery.retry-pending` 补发。

## 3. menus.yml

月历采用「左侧 7 列日期 + 右侧单列侧栏」：侧栏保留签到统计、补签卡、今日奖励、排行、邮箱、翻月和关闭；
日期格子用染料颜色区分状态（绿=已签、红=漏签、灰=未来、绿宝石=今天）。

```yaml
menus-version: 2

signin:
  title: '...'
  size: 54
  items:
    day:
      material: GRAY_DYE     # 或 item: 'ce:namespace:id'
      name: '...'
      lore: ['...']
```

- 物品支持 `material` 或 LiuLib `item` 规格；`item` 优先级更高。
- 名称/lore 使用 MiniMessage，并统一关闭物品默认斜体。
- `item` / `material` / `background` 都支持 LiuLib 完整规格：`DIAMOND`、`minecraft:diamond`、`DIAMOND*3`、
  `ce:命名空间:id`、`base64:...`、`nbt:...`、`item:base64:...`，以及 `lib:<物品库ID>`（需安装 liuitemlib）。
  插件先交给 LiuLib 解析，失败再按原版材质名兜底。
- 升级检测到旧 `menus-version` 时，会把旧文件备份为 `menus-v<旧版本>-backup.yml`，再写入新版默认布局；
  自定义内容可从备份中手动合并。

## 4. messages.yml

MiniMessage 格式，常用占位符：`{player}`、`{streak}`、`{total}`、`{rewards}`、`{date}`、`{days}`、`{cost}`。

主要键：

| 键 | 用途 |
|---|---|
| `prefix` | 所有消息的前缀 |
| `signed` / `already-signed` / `cooldown` | 签到结果 |
| `broadcast` / `first-broadcast` | 全服广播 |
| `reminder` / `reminder-hover` / `reminder-quotes` | 进服提醒 |
| `makeup-*` | 补签相关提示 |
| `mail-*` / `mailbox-*` | 邮箱相关提示 |
| `top-*` / `info-*` | 排行与档案 |
| `admin-*` | 管理命令提示 |
| `db-not-ready` | 数据库不可用 |

进服提醒：

- `reminder`：多行主模板，支持 `{intro}`、`{progress}`、`{quote}`；
- `reminder-quotes`：随机鼓励文案列表；
- `reminder-hover`：鼠标悬停提示。

升级插件时会自动补全缺失的 `reminder-quotes`，并把旧版默认单行 `reminder` 升级为多行模板；手动改过的
`reminder` 不会被覆盖。

修改后 `/signin reload` 生效。
