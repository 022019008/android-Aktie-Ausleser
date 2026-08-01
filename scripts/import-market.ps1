<#
.SYNOPSIS
  将 market.db 的数据一次性导入模拟器上 app 的数据库（ausleser.db）。
  - t_eft  -> 成员表 t_selber_select_member 的 “ETF” 分组（code+name）
  - t_k_5m -> t_k_5m；t_k_day -> t_k_day
  采用 pull -> ATTACH+INSERT -> push 方式，使用 app 自身连接之外的主机 sqlite3 操作；
  操作前备份原库，操作后在设备上二次校验行数。幂等（INSERT OR IGNORE）。
.NOTES
  需要：app 已至少运行一次（DB 已迁移到含 K 线表的版本）；运行前 app 会被假定已 force-stop。
#>
param(
    [string]$Pkg      = 'lv.bingping.ausleser',
    [string]$MarketDb = 'D:\Eigen\Git\android-Aktie-Ausleser\market.db',
    [string]$Sdk      = 'C:\Users\gnybo\AppData\Local\Android\Sdk',
    [string]$Sqlite   = 'D:\ProgramFiles\sqlite-tools\sqlite3.exe'
)
$ErrorActionPreference = 'Stop'
$adb  = Join-Path $Sdk 'platform-tools\adb.exe'
$work = Join-Path $env:TEMP 'ausleser_import'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$localDb = Join-Path $work 'ausleser.db'
$bak     = Join-Path $work 'ausleser.db.bak'

function Sql($db, $script) {
    ($script | & $Sqlite $db -separator '|' 2>$null) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -ne '' }
}
function PullDb($out) {
    $p = Start-Process -FilePath $adb -NoNewWindow -Wait -PassThru `
        -ArgumentList @('exec-out','run-as',$Pkg,'cat','databases/ausleser.db') `
        -RedirectStandardOutput $out
    if ($p.ExitCode -ne 0) { throw "pull failed (exit $($p.ExitCode))" }
}

Write-Host "[1/6] pull app db ..."
PullDb $localDb
Copy-Item $localDb $bak -Force
Write-Host ("      pulled {0} bytes (backup -> {1})" -f (Get-Item $localDb).Length, $bak)

Write-Host "[2/6] verify schema ..."
$tables = (Sql $localDb '.tables') -join ' '
foreach ($t in 't_selber_select_group','t_selber_select_member','t_k_5m','t_k_day') {
    if ($tables -notmatch "\b$t\b") { throw "pulled db missing table $t (run the app once to migrate first)" }
}

Write-Host "[3/6] import from market.db ..."
$mdb = $MarketDb -replace '\\','/'
$import = @"
PRAGMA synchronous=OFF;
PRAGMA temp_store=MEMORY;
ATTACH '$mdb' AS m;
BEGIN;
INSERT OR IGNORE INTO t_selber_select_member(group_id, code, name, added_at)
  SELECT (SELECT id FROM t_selber_select_group WHERE name='ETF'), code, name, CAST(strftime('%s','now') AS INTEGER)
  FROM m.t_eft;
INSERT OR IGNORE INTO t_k_5m(code,timestamp,open,high,low,close,volume,amount,adjust)
  SELECT code,timestamp,open,high,low,close,volume,amount,adjust FROM m.t_k_5m;
INSERT OR IGNORE INTO t_k_day(code,timestamp,open,high,low,close,volume,amount,adjust)
  SELECT code,timestamp,open,high,low,close,volume,amount,adjust FROM m.t_k_day;
COMMIT;
DETACH m;
"@
$import | & $Sqlite $localDb 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "import SQL failed" }

Write-Host "[4/6] verify counts on modified file ..."
$cEtf = (Sql $localDb "SELECT count(*) FROM t_selber_select_member WHERE group_id=(SELECT id FROM t_selber_select_group WHERE name='ETF');") | Select-Object -First 1
$c5   = (Sql $localDb "SELECT count(*) FROM t_k_5m;") | Select-Object -First 1
$cD   = (Sql $localDb "SELECT count(*) FROM t_k_day;") | Select-Object -First 1
Write-Host ("      ETF members={0}  t_k_5m={1}  t_k_day={2}  (file {3} bytes)" -f $cEtf, $c5, $cD, (Get-Item $localDb).Length)
if ([int]$c5 -lt 100000 -or [int]$cD -lt 10000 -or [int]$cEtf -lt 1) { throw "counts look wrong" }

Write-Host "[5/6] push back into app db dir ..."
& $adb push $localDb /data/local/tmp/ausleser.db | Out-Null
& $adb shell "run-as $Pkg cp /data/local/tmp/ausleser.db databases/ausleser.db"
& $adb shell "run-as $Pkg chmod 660 databases/ausleser.db"
& $adb shell "run-as $Pkg rm -f databases/ausleser.db-journal databases/ausleser.db-wal databases/ausleser.db-shm"
& $adb shell "rm -f /data/local/tmp/ausleser.db"   # shell 用户才能删 /data/local/tmp

Write-Host "[6/6] verify on device ..."
$verify = Join-Path $work 'verify.db'
PullDb $verify
$vE = (Sql $verify "SELECT count(*) FROM t_selber_select_member WHERE group_id=(SELECT id FROM t_selber_select_group WHERE name='ETF');") | Select-Object -First 1
$v5 = (Sql $verify "SELECT count(*) FROM t_k_5m;") | Select-Object -First 1
$vD = (Sql $verify "SELECT count(*) FROM t_k_day;") | Select-Object -First 1
Write-Host ("      device: ETF={0}  t_k_5m={1}  t_k_day={2}" -f $vE, $v5, $vD)
Write-Host "DONE."
