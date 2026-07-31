# 预置种子库

把带初始数据的 `ausleser.db` 放在本目录，打包 APK 时会一并带入；
首次启动时由 `DbHelper.installIfNeeded()` 拷贝到应用内部存储
（`/data/data/lv.bingping.ausleser/databases/ausleser.db`）。

**未放置 `ausleser.db` 时应用仍可运行**——走 `DbHelper.onCreate` 兜底流程，
但仅建空表、不含任何初始分组（默认分组等初始数据一律由种子库提供，
代码中不再内置）。

## 种子库内容约定（v4 起）

- **只携带自选数据**：`t_selber_select_group`（默认分组含 `我的自选` 与 `ETF`）
  与 `t_selber_select_stock`（初始自选行）；
- **K 线表必须为空**：`t_k_5m` / `t_k_30m` / `t_k_60m` / `t_k_day` 只建表不装数据——
  K 线一律由 app 运行时从网络同步（`data/KLineSync.kt`，前复权，
  首次下载 5m/30m 两年、60m/日线五年，之后增量补尾、除权除息自动重建）；
- `adjust` 列语义：应用同步写入 `'qfq'`（前复权）；同步时发现非 `'qfq'`
  行（历史遗留）会清掉该股重建，故种子库不应再写入任何 K 线行。
- `is_realtime` 列语义（v6）：0=历史定稿 / 1=盘中实时。实时补齐写 1、
  历史同步按主键覆盖时归 0，复权检测只取历史行（`DbHelper.queryKBarsSince`）。

## 种子库要求

- schema 与 `DbHelper` 当前定义完全一致（t_selber_select_group /
  t_selber_select_stock / t_k_5m / t_k_30m / t_k_60m / t_k_day 及索引）；
- 必须设置 `PRAGMA user_version = 6`（= `DbHelper.DB_VERSION`），
  否则 SQLiteOpenHelper 打开时会重跑 onCreate 直接崩溃
  （旧 v3/v4/v5 种子仍可安装：打开时 onUpgrade 会自动补建缺失的 K 线表、
  补加 is_realtime 列，v3 种子另会清空 bfq 遗留 K 线）；
- 只放 `ausleser.db` 本体，不能带 `-wal` / `-shm` / `-journal` 残留
  （构建完先 force-stop 应用或 `PRAGMA wal_checkpoint(TRUNCATE)`）。

## 构建方式

**方式 A（推荐，从运行中的 app 提取）**：模拟器装 debug 包 → 手动建好分组
与自选（或经搜索添加）→ force-stop 应用 → pull：

```bash
adb exec-out run-as lv.bingping.ausleser cat databases/ausleser.db > ausleser.db
```

pull 下来的库若已同步过 K 线，按下文「清空 K 线」一节处理后再打包。

**方式 B（从零建空库）**：用 sqlite3 从零建库（schema 照抄 `DbHelper.onCreate`，
K 线表含 v6 的 `is_realtime INTEGER NOT NULL DEFAULT 0` 列），
插入默认分组与自选行，执行 `PRAGMA user_version = 6;`，默认分组需包含
`我的自选` 与 `ETF`。

**方式 C（已废弃，留档）**：`scripts/import-market.ps1` 曾用于从 market.db
导入 K 线与 ETF 自选；market.db 现为空占位文件，K 线改由运行时网络同步后
该流程不再需要（通达信导入脚本 import-tdx.ps1 已随之删除）。

**方式 D（现行推荐：脚本重建）**：`scripts/build-seed.ps1` 从
`D:\Eigen\Git\Akties-Auswahl\market.db` 的 `t_eft` 表读取全部 ETF（37 只），
从零重建种子库——分组为 `我的自选`（脚本内硬编码 11 只初始自选）与 `ETF`，
K 线表只建表不装数据，
自动设 `user_version = 5` 并自校验（分组数 / 行数 / 空 K 线 / integrity）。
幂等可重跑：

```powershell
scripts\build-seed.ps1                        # 缺省路径
scripts\build-seed.ps1 -MarketDb <path> -Out <path> -Sqlite <path>
```

## 清空 K 线（把任意来源的库改造为合规种子）

```bash
sqlite3 ausleser.db <<'SQL'
BEGIN;
DELETE FROM t_k_5m;
DELETE FROM t_k_30m;
DELETE FROM t_k_60m;
DELETE FROM t_k_day;
COMMIT;
PRAGMA user_version = 6;
VACUUM;
SQL
```

（库中缺 `t_k_30m` / `t_k_60m` 时先按 `DbHelper.createKTables` 的 schema 建表+索引。）

## 调试提示

`installIfNeeded` 见库即退、绝不覆盖——修改种子库后要在真机上看到效果，
需先卸载应用或清除应用数据。
