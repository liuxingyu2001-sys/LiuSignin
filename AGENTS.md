# LiuSignin 开发约定

## 构建与验证

```bash
cd /home/liu/plugins/LiuSignin
mvn clean package     # 产物 target/LiuSignin-1.0.0.jar
mvn test              # 纯逻辑单元测试（奖励 DSL / 时间 / 奖励组）
```

- Java 21，Paper API `1.21.11-R0.1-SNAPSHOT`。
- 不 shade 任何依赖；LiuLib / Vault / PlaceholderAPI 全部 `provided`。
- 修改后至少跑一次 `mvn clean package`，确认 jar 内包含 4 个 yml 与 `plugin.yml`。

## 依赖边界

- **LiuLib 是硬依赖**：MySQL、Redis、Sparrow UI、物品解析都走 `LiuLib.api()`。
- 不在本插件里直接创建 HikariCP / Jedis；不要新增 SQLite 回退。
- GUI 只用 Sparrow（`LiuLib.sparrow()`），不要写原版 Inventory 兜底。
- Vault 通过反射访问，避免未安装 Vault 时类加载失败；不要直接 import `net.milkbowl.vault.**`。
- PlaceholderAPI 只在检测到插件后实例化 expansion。

## 数据与并发

- 权威数据在 MySQL；内存缓存只用于展示。
- 签到成功与否由 `liusignin_log (uuid, sign_date)` 主键裁决，禁止改成“先查缓存再写”。
- 玩家档案累加与日志写入必须在同一事务内（见 `SigninStore.beginSign/beginMakeup`）。
- 任何写操作完成后调用 `plugin.sync().publishInvalidate(uuid)` 通知其它子服。
- 表名统一带 `database.table-prefix`，使用反引号拼接；前缀已在 `SigninConfig.sanitizePrefix` 过滤。

## 线程

- 玩家背包、Vault、命令、事件、Sparrow 界面：主线程/玩家线程。
- MySQL、Redis、邮箱读取：异步。
- 跨线程回调统一用 `Schedulers.onPlayer / global / async`；玩家离线时不要遗留未完成的 `CompletableFuture`。

## 配置

- `config.yml` 只放运行参数；奖励表、菜单、消息分别放 `rewards.yml`、`menus.yml`、`messages.yml`。
- 所有配置解析都要有代码内默认值，配置缺段不能让插件启动失败。
- 新增消息键必须同时写入 `messages.yml`；`Messages.send` 对缺失键会告警并跳过。

## 发布

- 未经用户明确要求，不复制 jar 到服务器、不提交、不推送。
- 版本号变更遵循用户指示；默认 `1.0.0`。
