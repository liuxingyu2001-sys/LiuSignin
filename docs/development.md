# 开发与验证

## 构建

```bash
cd /home/liu/plugins/LiuSignin
mvn clean package
# 产物：target/LiuSignin-1.0.6.jar
```

- Java 21，Paper API `1.21.11-R0.1-SNAPSHOT`。
- 依赖全部 `provided`，jar 内只有本插件类与资源，不 shade LiuLib / Vault / PlaceholderAPI。
- 修改资源后确认 jar 内含 4 个 yml（`config.yml`、`rewards.yml`、`menus.yml`、`messages.yml`）与 `plugin.yml`。

单元测试覆盖奖励 DSL、月历/无限奖励计算、签到日重置逻辑：

```bash
mvn test
```

## 目录结构

```text
src/main/java/com/liuxingyu/liusignin/
├── LiuSigninPlugin.java        # 启动、装配、reload、配置合并
├── api/                        # LiuSigninApi 与事件
├── command/                    # /signin 与 Tab 补全
├── config/                     # SigninConfig、菜单/奖励配置
├── data/                       # MySQL 数据层（SigninStore / SigninDatabase）
├── hook/                       # Vault（反射）、PlaceholderAPI、物品库
├── listener/                   # 进服加载/自动签到/提醒/补偿
├── menu/                       # Sparrow 月历、补签、邮箱
├── reward/                     # 奖励 DSL、奖励组、奖励表
├── service/                    # 签到、发奖、邮箱
├── sync/                       # Redis 跨服失效
├── time/                       # 签到日计算
└── util/                       # 消息、占位符、调度
```

## 开发约定

- LiuLib 是硬依赖：MySQL 连接凭据、Redis、Sparrow UI、物品解析都走 `LiuLib.api()`。
- 不直接 `new HikariCP` / `Jedis`；用 LiuLib 的 `MySql` 类持有本插件自己的连接池（凭据取 LiuLib `mysql` 段，
  库名/前缀由本插件 `config.yml: database` 决定），不要把表写回 LiuLib 的 `liulib` 库；不新增 SQLite 回退。
- GUI 只用 Sparrow（`LiuLib.sparrow()`），不要写原版 Inventory 兜底。
- Vault 通过反射访问；PlaceholderAPI 只在检测到插件后实例化 expansion。
- 表名统一带 `database.table-prefix`，使用反引号拼接；库名由 `database.name` 决定。
- 签到成功与否由 `liusignin_log (uuid, sign_date)` 主键裁决，禁止改成“先查缓存再写”。
- 玩家档案累加与日志写入必须在同一事务内（`SigninStore.beginSign/beginMakeup`）。
- 任何写操作完成后调用 `plugin.sync().publishInvalidate(uuid)` 通知其它子服。
- 新增消息键必须同时写入 `messages.yml`；`Messages.send` 对缺失键会告警并跳过。
- `menus.yml` 结构变更要同步提升 `menus-version`，并写好旧版备份/迁移。

## 实测记录

测试环境：Leaf 1.21.11、Java 25、LiuLib 1.0.5、临时 MariaDB（127.0.0.1:3307）。

- 启动日志：`MySQL 已连接: 127.0.0.1:3307/liusignin（表前缀 liusignin_）`。
- 旧库 `liulib.liusignin_player` 预置 1 行：迁移日志显示 `已迁移 ...（1 行）`；`SHOW TABLES FROM liusignin`
  得到 5 张表，旧行保留。
- 游戏内点击签到成功，数据写入新库；`/signin dbtest` 返回“数据库连接正常”。
- 再次重启：`未发现需要迁移的旧表（liulib 库）`，行数不变（迁移幂等）。
- `mvn test` 通过。
- 测试服已恢复原配置（LiuLib MySQL 关闭），LiuSignin 按设计停用。

## 已知限制

- 仅支持 Paper，`folia-supported: false`；不支持 SQLite。
- 奖励在“入库”和“标记发放”之间崩溃时，下次进服按 `recovery.retry-pending` 补发，极端情况下可能重复发奖
  （用极小概率换不丢奖）。
- 签到冷却按子服内存计算，跨服不共享。
- 月历回看加载最近 400 天记录；更早的日期会显示为未签到。
- 修改表前缀后旧表不会自动迁移；需要手动迁移或保留原前缀。
- 修改 `database.name` 需要重启服务器；reload 只提示，不会切换连接。
