# 命令与权限

主命令：`/signin`，别名 `/sign`、`/qd`、`/qiandao`。

## 玩家命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/signin` | 打开签到月历 | `liusignin.gui` |
| `/signin claim` | 直接签到（`sign` / `checkin` 同义） | `liusignin.sign` |
| `/signin menu` | 打开月历（`gui` / `calendar` 同义） | `liusignin.gui` |
| `/signin makeup [yyyy-MM-dd]` | 补签，缺省为昨天；也支持 `昨天` | `liusignin.makeup` |
| `/signin mail [all]` | 打开邮箱；`all` 一键领取 | `liusignin.mail` |
| `/signin top [streak\|total]` | 查看连续 / 累计排行，缺省连续 | `liusignin.leaderboard` |
| `/signin info [玩家]` | 查看签到档案，缺省为自己 | 无 |
| `/signin help` | 帮助 | 无 |

说明：

- 签到受 `settings.cooldown-ms` 防连点限制，拥有 `liusignin.bypass.cooldown` 可跳过。
- 补签范围由 `makeup.max-days` 决定，可跨月；需要消耗补签卡（`makeup.cards-per-makeup` 张/次）。
- 补签日期支持 `yyyy-MM-dd`，以及 `昨天` / `yesterday`；缺省为昨天。
- 邮箱里的奖励来自背包装不下的物品与 Vault 不可用时的金币。

## 管理命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/signin reload` | 重载配置（数据库名需重启才切换） | `liusignin.admin` |
| `/signin card give <玩家> <数量>` | 发放补签卡 | `liusignin.admin` |
| `/signin card take <玩家> <数量>` | 扣除补签卡 | `liusignin.admin` |
| `/signin card set <玩家> <数量>` | 设置补签卡数量 | `liusignin.admin` |
| `/signin reset <玩家>` | 清空档案、签到记录、邮箱 | `liusignin.admin` |
| `/signin setstreak <玩家> <天数>` | 设置连续签到天数 | `liusignin.admin` |
| `/signin settotal <玩家> <天数>` | 设置累计签到天数 | `liusignin.admin` |
| `/signin purge [天数]` | 清理早于 N 天的日志与统计（默认取 `database.log-retain-days`） | `liusignin.admin` |
| `/signin dbtest` | 测试 MySQL 连接 | `liusignin.admin` |

管理命令参数中的玩家支持在线玩家名；离线玩家需要能被服务器解析（`Bukkit.getOfflinePlayer`）。

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `liusignin.sign` | true | 允许签到 |
| `liusignin.gui` | true | 打开签到界面 |
| `liusignin.makeup` | true | 使用补签 |
| `liusignin.mail` | true | 打开签到邮箱 |
| `liusignin.leaderboard` | true | 查看排行 |
| `liusignin.reward.vip` | false | 匹配 VIP 奖励组（示例权限，按 `rewards.yml` 实际配置） |
| `liusignin.bypass.cooldown` | op | 跳过签到冷却 |
| `liusignin.admin` | op | 管理命令 |
| `liusignin.*` | op | 全部权限（含 admin） |

## 示例

```text
/signin
/signin claim
/signin makeup 2026-10-01
/signin mail all
/signin top total
/signin info Steve
/signin card give Steve 3
/signin purge 30
/signin dbtest
```
