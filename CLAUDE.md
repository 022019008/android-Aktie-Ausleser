# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

"Ausleser" — an Android member watchlist (自选) app. Single-module (`:app`), Kotlin, traditional View system (AppCompat + Material, **no Compose**). Package `lv.bingping.ausleser`, minSdk 30 / target 36.

All user-facing strings are Chinese (`app/src/main/res/values/strings.xml`, the only locale) and code comments/KDoc are Chinese — match that style.

## Commands

```bash
./gradlew :app:assembleDebug                 # build debug APK
./gradlew :app:installDebug                  # install on connected device/emulator
./gradlew :app:testDebugUnitTest             # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "lv.bingping.ausleser.ExampleUnitTest"   # single test
./gradlew :app:connectedDebugAndroidTest     # instrumented tests (needs device/emulator)
./gradlew :app:lintDebug                     # lint
```

Build notes:
- AGP 9.x with **built-in Kotlin compilation** — there is intentionally no `org.jetbrains.kotlin.android` plugin and no `kotlin {}` block in the build files. Do not add them.
- Dependencies are pinned in the version catalog `gradle/libs.versions.toml`; Java 11 source/target; Gradle daemon runs on an auto-provisioned JDK 21 toolchain; configuration cache is on.

## Architecture

**Persistence — `data/DbHelper.kt` is the only data layer.** A hand-written `SQLiteOpenHelper` over `ausleser.db` (no Room, no repositories). All UI classes take a `DbHelper` and call its query/insert/update/delete methods, which return plain data classes (`SelectGroup`, `SelectMember`). Schema-change procedure: bump `DB_VERSION` and add an incremental `if (oldVersion < N)` branch in `onUpgrade` (existing pattern: v2 added members, v3 added K-line tables, v4 added `t_k_30m` and wiped legacy seed K-line rows, v5 added `t_k_60m`, v7 renamed the legacy member table). On first launch `DbHelper.installIfNeeded()` (called before the constructor in `MainActivity`) copies a pre-populated seed DB from `assets/databases/ausleser.db` if present — never overwriting an existing DB, and silently falling back to the empty `onCreate` flow if the asset is absent (see `app/src/main/assets/databases/README.md` for seed-DB requirements, notably `PRAGMA user_version` == `DB_VERSION`).

**Main screen (MainActivity.kt)** is a three-layer UI wired together in code, not navigation: top toolbar → collapsible sub-bar of group `Chip`s → member list for the selected group. Selection state is just `selectedGroupId`; `refreshGroups()` rebuilds chips preserving selection, `reloadMembers()` re-queries the list and toggles the empty state.

**Member search** uses the Eastmoney suggestion endpoint through `MemberSearchApi`; `ui/AddMemberBottomSheet.kt` debounces input and displays candidates using `MemberSearchAdapter`.

**Swipe-reveal is a custom two-part mechanism, not a library:**
- `ui/SwipeRevealLayout.kt` — FrameLayout with a child-order contract: child 0 = row content, child 1 = action area laid out just off the right edge; `setOffset()` slides both. Layout XML for list items must respect this order.
- `ui/SwipeRevealCallback.kt` — `ItemTouchHelper.Callback` in *reveal-only* mode: `getSwipeThreshold` returns 2f so `onSwiped` never fires (deletion happens via the revealed button), and `openVH` enforces one open row at a time. `MainActivity` closes the open row on scroll and on touches outside it. Don't "simplify" the offset bookkeeping in `onChildDraw`/`clearView` — the ordering (set `openVH` before `super.clearView`) is what keeps a revealed row from being snapped shut by ItemTouchHelper's own rebound animation.

**Bottom sheets** (`AddMemberBottomSheet`, `GroupManageBottomSheet`) are plain classes wrapping `BottomSheetDialog`, constructed with `(context, dbHelper, …, onChangedCallback)`; they mutate the DB directly and notify the host via the callback. The swipe-reveal action area exposes only the per-row delete button. There is no background polling; K-line sync happens only on entering `KLineActivity`.

**K-line tables `t_k_5m` / `t_k_30m` / `t_k_60m` / `t_k_day`** are populated at runtime, not by the seed DB: `data/KLineSync.kt` runs on a background thread when `KLineActivity` opens (once per page lifecycle). First download per member: ~2 years of 5m and 30m bars + ~5 years of 60m and daily bars; later syncs fetch only the missing tail. All stored bars are forward-adjusted (`adjust='qfq'`); each tail fetch overlaps recent stored bars and `KLineSync.detectAdjustChange` compares their closes — if prices were recomputed (ex-dividend event) the member's rows are wiped and re-downloaded. All four frequencies are fetched from the self-hosted data source Aktie_datasource via `data/DatasourceApi.kt` (`freq=5m/30m/60m/day`, blocking calls — background thread only); 30m/60m come from the server's own tables, they are not aggregated from 5m. Only after the history sync has succeeded and been persisted does `KLineSync.syncRealtime` run (skipped when history failed outright) — it checks each freq for a today-gap (`needsRealtime`: no today bars, or intraday-stale >5 min) and if missing fetches today's bars from the server's realtime endpoint (`GET /api/kline/{code}/realtime` — AkShare current-day data, not persisted server-side) and upserts them locally; it is best-effort (failures only log and fall back to the history view), and finalized same-day values later overwrite them by primary key during history syncs. A process-wide read-write lock in `KLineSync` (`syncMember` = read lock, `syncRealtime` = write lock) guarantees realtime requests never overlap any history request in flight. The chart overlays Chan-theory (缠论) bi and zhongshu computed by the pure functions in `data/Chan.kt` (K-line inclusion merge → fractals → new-rule bi → ≥3-bi zhongshu with extension), rendered by `KLineChartView.setChanOverlay` — own-level bi + own-level zhongshu plus **sub-level zhongshu** (day chart ← 30m, 60m chart ← 30m, 30m chart ← 5m, week chart ← day; 5m has none). Weekly bars are not synced: `KLineActivity` synthesizes them in memory from daily via `KLineSynth.toWeekly`. The seed DB deliberately ships **empty** K-line tables (watchlist groups/items only); `scripts/import-market.ps1` is a deprecated relic and the former Tongdaxin seed importer was removed when K-line moved to runtime sync.
