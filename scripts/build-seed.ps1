<#
.SYNOPSIS
  构建打包用预置种子库（app/src/main/assets/databases/ausleser.db）。

  内容（v4 约定，详见 assets/databases/README.md）：
  - 分组：我的自选（11 只初始自选，清单硬编码在 [3/5] 步）、ETF；
  - ETF 分组：Akties-Auswahl\market.db 的 t_eft 表全部 ETF（37 只）；
  - K 线表（t_k_5m / t_k_30m / t_k_60m / t_k_day）只建表不装数据——
    K 线一律由 app 运行时从网络同步（data/KLineSync.kt）；
  - PRAGMA user_version = 6（= DbHelper.DB_VERSION）。

  幂等：重跑直接覆盖旧种子库。
.NOTES
  前置：sqlite3.exe（默认 D:\ProgramFiles\sqlite-tools\sqlite3.exe）。
#>
param(
    [string]$MarketDb = 'D:\Eigen\Git\Akties-Auswahl\market.db',
    [string]$Out      = (Join-Path $PSScriptRoot '..\app\src\main\assets\databases\ausleser.db'),
    [string]$Sqlite   = 'D:\ProgramFiles\sqlite-tools\sqlite3.exe'
)
$ErrorActionPreference = 'Stop'

Write-Host "[1/5] 前置校验 ..."
foreach ($p in @($MarketDb, $Sqlite)) {
    if (-not (Test-Path $p)) { throw "路径不存在: $p" }
}
$srcCount = [int](("SELECT COUNT(*) FROM t_eft;" | & $Sqlite $MarketDb) | Select-Object -First 1)
if ($srcCount -eq 0) { throw "market.db 的 t_eft 为空，无 ETF 可导入" }
Write-Host ("      market.db t_eft 共 {0} 只 ETF" -f $srcCount)

# 干净重建：删旧库及其可能的残留边车文件
Remove-Item $Out -Force -ErrorAction SilentlyContinue
foreach ($junk in @("$Out-journal", "$Out-wal", "$Out-shm")) {
    Remove-Item $junk -Force -ErrorAction SilentlyContinue
}
$OutDir = Split-Path $Out -Parent
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

Write-Host "[2/5] 建库建表（schema 与 DbHelper.onCreate 一致） ..."
$schema = @"
PRAGMA journal_mode=DELETE;
CREATE TABLE t_selber_select_group (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL
);
CREATE TABLE t_selber_select_stock (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL,
    code     TEXT NOT NULL,
    name     TEXT NOT NULL,
    added_at INTEGER NOT NULL DEFAULT 0,
    UNIQUE(group_id, code)
);
CREATE TABLE t_k_5m (
    code TEXT NOT NULL, timestamp INTEGER NOT NULL,
    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
    volume REAL NOT NULL, amount REAL NOT NULL, adjust TEXT NOT NULL,
    is_realtime INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(code, timestamp)
);
CREATE INDEX idx_t_k_5m_code_time ON t_k_5m(code, timestamp);
CREATE TABLE t_k_30m (
    code TEXT NOT NULL, timestamp INTEGER NOT NULL,
    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
    volume REAL NOT NULL, amount REAL NOT NULL, adjust TEXT NOT NULL,
    is_realtime INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(code, timestamp)
);
CREATE INDEX idx_t_k_30m_code_time ON t_k_30m(code, timestamp);
CREATE TABLE t_k_60m (
    code TEXT NOT NULL, timestamp INTEGER NOT NULL,
    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
    volume REAL NOT NULL, amount REAL NOT NULL, adjust TEXT NOT NULL,
    is_realtime INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(code, timestamp)
);
CREATE INDEX idx_t_k_60m_code_time ON t_k_60m(code, timestamp);
CREATE TABLE t_k_day (
    code TEXT NOT NULL, timestamp INTEGER NOT NULL,
    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
    volume REAL NOT NULL, amount REAL NOT NULL, adjust TEXT NOT NULL,
    is_realtime INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(code, timestamp)
);
CREATE INDEX idx_t_k_day_code_time ON t_k_day(code, timestamp);
"@
$schema | & $Sqlite $Out
if ($LASTEXITCODE -ne 0) { throw "建表失败 (exit $LASTEXITCODE)" }

Write-Host "[3/5] 写入分组与自选（我的自选硬编码清单 + ATTACH 源库导入 ETF） ..."
# 我的自选初始清单：按此顺序入库（added_at 同为 0，列表按 id 升序即按此清单顺序显示）
$seed = @"
ATTACH '$($MarketDb -replace '\\','/')' AS src;
BEGIN;
INSERT INTO t_selber_select_group(id, name) VALUES (1, '我的自选'), (2, 'ETF');
INSERT INTO t_selber_select_stock(group_id, code, name, added_at) VALUES
(1, '601888', '中国中免', 0),
(1, '000617', '中油资本', 0),
(1, '688472', '阿特斯', 0),
(1, '300869', '康泰医学', 0),
(1, '300725', '药石科技', 0),
(1, '301358', '湖南裕能', 0),
(1, '513180', '恒生科技ETF华夏', 0),
(1, '515120', '创新药ETF广发', 0),
(1, '588150', '科创50ETF南方', 0),
(1, '560580', '电力ETF南方', 0),
(1, '688209', '英集芯', 0);
INSERT INTO t_selber_select_stock(group_id, code, name, added_at)
SELECT 2, code, name, 0 FROM src.t_eft ORDER BY code;
COMMIT;
DETACH src;
PRAGMA user_version = 6;
VACUUM;
"@
$seed | & $Sqlite $Out
if ($LASTEXITCODE -ne 0) { throw "种子数据写入失败 (exit $LASTEXITCODE)" }

Write-Host "[4/5] 校验 ..."
$groups  = [int](("SELECT COUNT(*) FROM t_selber_select_group;" | & $Sqlite $Out) | Select-Object -First 1)
$stocks  = [int](("SELECT COUNT(*) FROM t_selber_select_stock;" | & $Sqlite $Out) | Select-Object -First 1)
$mine    = [int](("SELECT COUNT(*) FROM t_selber_select_stock WHERE group_id=1;" | & $Sqlite $Out) | Select-Object -First 1)
$k5      = [int](("SELECT COUNT(*) FROM t_k_5m;"  | & $Sqlite $Out) | Select-Object -First 1)
$k30     = [int](("SELECT COUNT(*) FROM t_k_30m;" | & $Sqlite $Out) | Select-Object -First 1)
$k60     = [int](("SELECT COUNT(*) FROM t_k_60m;" | & $Sqlite $Out) | Select-Object -First 1)
$kd      = [int](("SELECT COUNT(*) FROM t_k_day;" | & $Sqlite $Out) | Select-Object -First 1)
$uv      = (("PRAGMA user_version;" | & $Sqlite $Out) | Select-Object -First 1)
$integ   = (("PRAGMA integrity_check;" | & $Sqlite $Out) | Select-Object -First 1)
if ($groups -ne 2)        { throw "分组数 $groups != 2" }
if ($mine -ne 11)         { throw "我的自选行数 $mine != 11" }
if ($stocks -ne ($srcCount + 11)) { throw "自选总行数 $stocks != 源 ETF $srcCount + 我的自选 11" }
if ($k5 + $k30 + $k60 + $kd -ne 0) { throw "K 线表必须为空（5m=$k5, 30m=$k30, 60m=$k60, day=$kd）" }
if ($uv -ne '6')          { throw "user_version=$uv, 应为 6" }
if ($integ -ne 'ok')      { throw "integrity_check=$integ" }
foreach ($junk in @("$Out-journal", "$Out-wal", "$Out-shm")) {
    if (Test-Path $junk) { throw "残留边车文件 $junk" }
}

Write-Host "[5/5] 完成"
Write-Host ("      种子库: {0} ({1:N0} 字节)" -f (Resolve-Path $Out), (Get-Item $Out).Length)
Write-Host ("      分组: 我的自选({0} 只) + ETF({1} 只)，我的自选清单:" -f $mine, $srcCount)
"SELECT code, name FROM t_selber_select_stock WHERE group_id=1 ORDER BY id;" | & $Sqlite $Out |
    ForEach-Object { Write-Host "        $_" }
