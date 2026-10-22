# 开发者 API

调用方在 `plugin.yml` 中声明依赖：

```yaml
depend: [LiuSignin]
```

## 获取 API

```java
LiuSigninApi api = Bukkit.getServicesManager().load(LiuSigninApi.class);
if (api == null) {
    return;
}
```

插件启用时注册，停用后自动注销。

## 接口概览

### 查询

| 方法 | 说明 |
|---|---|
| `serverId()` | 当前子服 ID（每次启动生成，用于日志/排障） |
| `cachedProfile(UUID)` | 只读缓存；未加载返回 `null`，不触发数据库查询 |
| `profile(UUID)` | 异步查 MySQL，返回 `CompletableFuture<Profile>` |
| `profile(UUID, boolean refresh)` | `refresh=true` 绕过缓存 |
| `isSignedToday(UUID)` | 缓存中是否已签今天；未加载返回 `false` |
| `isSigned(UUID, LocalDate)` | 查询指定日期是否已签，异步 |

`Profile` 字段：`uniqueId`、`name`、`lastSignDate`、`streak`、`bestStreak`、`total`、`makeupCards`、`signedToday`。

### 操作

| 方法 | 说明 |
|---|---|
| `sign(Player)` | 以 `SignSource.API` 签到，返回 `CompletableFuture<SigninResult>` |
| `makeup(Player, LocalDate)` | 补签，返回 `CompletableFuture<MakeupResult>` |
| `addMakeupCards(UUID, int)` | 增加补签卡，返回变更后数量 |
| `takeMakeupCards(UUID, int)` | 扣除补签卡；不足返回 `false` |
| `setMakeupCards(UUID, int)` | 直接设置补签卡数量 |
| `openMenu(Player)` | 打开月历（自动切回玩家线程） |
| `openMakeupMenu(Player, LocalDate)` | 打开补签确认界面 |
| `openMailbox(Player)` | 打开签到邮箱 |

### 结果状态

`SigninStatus`：`SUCCESS`、`ALREADY_SIGNED`、`COOLDOWN`、`NO_PERMISSION`、`ERROR`。

`MakeupStatus`：`SUCCESS`、`DISABLED`、`NO_PERMISSION`、`NO_CARD`、`ALREADY_SIGNED`、`OUT_OF_RANGE`、
`FUTURE`、`ERROR`。

`SigninResult`：状态、日期、连续/累计天数、奖励文本列表、是否当天第一个。
`MakeupResult`：状态、日期、连续/累计天数、奖励文本列表、剩余补签卡。

## 示例

```java
LiuSigninApi api = Bukkit.getServicesManager().load(LiuSigninApi.class);
if (api == null) {
    return;
}

api.profile(player.getUniqueId()).thenAccept(profile -> {
    int streak = profile.streak();
    int cards = profile.makeupCards();
});

api.sign(player).thenAccept(result -> {
    if (result.status() == LiuSigninApi.SigninStatus.SUCCESS) {
        player.sendMessage("签到成功，连续 " + result.streak() + " 天");
    }
});

api.addMakeupCards(player.getUniqueId(), 1);
api.openMenu(player);
```

## 线程约定

- `profile` / `isSigned` / 补签卡方法返回 `CompletableFuture`，不要在主线程阻塞等待。
- `sign` / `makeup` 会操作玩家背包与 Vault，**必须在玩家所在线程调用**；`PlayerSigninEvent` 仍会正常触发，
  来源为 `SignSource.API` / `SignSource.MAKEUP`。
- 打开界面的方法内部自动切回玩家线程。

## 事件

```java
@EventHandler
public void onSignin(PlayerSigninEvent event) {
    Player player = event.player();
    LocalDate date = event.date();
    int streak = event.streak();
    int total = event.total();
    boolean makeup = event.source() == SignSource.MAKEUP;
}
```

- 包：`com.liuxingyu.liusignin.api.event`
- `PlayerSigninEvent`：数据库已写入、奖励发放之前触发，主线程同步，不可取消。
- `SignSource`：`COMMAND`、`GUI`、`AUTO`、`MAKEUP`、`API`。

## 补签卡与事件

`addMakeupCards` / `takeMakeupCards` / `setMakeupCards` 只返回数量，不会触发签到事件；需要通知其他插件时
请在调用方自行处理。
