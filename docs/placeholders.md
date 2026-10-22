# PlaceholderAPI

需要安装 PlaceholderAPI。标识为 `liusignin`，变量格式 `%liusignin_<key>%`。

| key | 说明 |
|---|---|
| `signed` / `signed_today` | 今日是否已签（`true` / `false`） |
| `streak` | 连续签到天数 |
| `total` | 累计签到天数 |
| `best` | 历史最佳连续 |
| `cards` | 补签卡数量 |
| `today_count` | 今日签到人数（异步缓存） |
| `rank_streak` / `rank_total` | 排行榜名次，未加载时为 `0` |
| `month` | 当前签到月 `yyyy-MM` |
| `next_reward` | 今日/下次签到奖励预览 |
| `last_sign` | 上次签到日期 |

## 示例

```text
%liusignin_streak%
%liusignin_total%
%liusignin_cards%
%liusignin_signed_today%
%liusignin_next_reward%
%liusignin_rank_streak%
```

TAB / 计分板：

```yaml
- '&6连续签到 &f%liusignin_streak% &7天'
- '&7补签卡 &f%liusignin_cards%'
```

## 注意

- 变量读取本服内存缓存；跨服修改要等缓存过期（120 秒）或玩家重新登录。
- `today_count`、`rank_*` 是异步/定时刷新的值，刚启动时可能为 `0`。
- 未安装 PlaceholderAPI 时不会注册变量，但插件其余功能不受影响。
