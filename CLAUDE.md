# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

"Ausleser" — an Android stock watchlist (自选) app. Single-module (`:app`), Kotlin, traditional View system (AppCompat + Material, **no Compose**). Package `lv.bingping.ausleser`, minSdk 30 / target 36.

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

**Persistence — `data/DbHelper.kt` is the only data layer.** A hand-written `SQLiteOpenHelper` over `ausleser.db` (no Room, no repositories). All UI classes take a `DbHelper` and call its query/insert/update/delete methods, which return plain data classes (`SelectGroup`, `SelectStock`). Schema-change procedure: bump `DB_VERSION` and add an incremental `if (oldVersion < N)` branch in `onUpgrade` (existing pattern: v2 added stocks, v3 added K-line tables, v4 added `t_k_30m` and wiped legacy seed K-line rows). On first launch `DbHelper.installIfNeeded()` (called before the constructor in `MainActivity`) copies a pre-populated seed DB from `assets/databases/ausleser.db` if present — never overwriting an existing DB, and silently falling back to the empty `onCreate` flow if the asset is absent (see `app/src/main/assets/databases/README.md` for seed-DB requirements, notably `PRAGMA user_version` == `DB_VERSION`).

**Main screen (MainActivity.kt)** is a three-layer UI wired together in code, not navigation: top toolbar → collapsible sub-bar of group `Chip`s → stock list for the selected group. Selection state is just `selectedGroupId`; `refreshGroups()` rebuilds chips preserving selection, `reloadStocks()` re-queries the list and toggles the empty state.

**Stock search has no data source yet.** The hardcoded sample catalog (`StockCatalog.kt`) was deliberately removed; `ui/AddStockBottomSheet.kt` keeps the search-sheet shell but shows an empty candidate list until a real source is wired into its `doAfterTextChanged` handler (marked with a TODO there). The `Stock` model and `StockSearchAdapter` are kept as the shape for that integration.

**Swipe-reveal is a custom two-part mechanism, not a library:**
- `ui/SwipeRevealLayout.kt` — FrameLayout with a child-order contract: child 0 = row content, child 1 = action area laid out just off the right edge; `setOffset()` slides both. Layout XML for list items must respect this order.
- `ui/SwipeRevealCallback.kt` — `ItemTouchHelper.Callback` in *reveal-only* mode: `getSwipeThreshold` returns 2f so `onSwiped` never fires (deletion happens via the revealed button), and `openVH` enforces one open row at a time. `MainActivity` closes the open row on scroll and on touches outside it. Don't "simplify" the offset bookkeeping in `onChildDraw`/`clearView` — the ordering (set `openVH` before `super.clearView`) is what keeps a revealed row from being snapped shut by ItemTouchHelper's own rebound animation.

**Bottom sheets** (`AddStockBottomSheet`, `GroupManageBottomSheet`) are plain classes wrapping `BottomSheetDialog`, constructed with `(context, dbHelper, …, onChangedCallback)`; they mutate the DB directly and notify the host via the callback. The per-row "sync" button is a deliberate no-op placeholder awaiting a real quote data source.

**K-line tables `t_k_5m` / `t_k_30m` / `t_k_day`** are populated at runtime, not by the seed DB: `data/KLineSync.kt` runs on a background thread when `KLineActivity` opens (once per page lifecycle). First download per stock: ~2 years of 5m and 30m bars + ~5 years of daily bars; later opens fetch only the missing tail. All stored bars are forward-adjusted (`adjust='qfq'`); each tail fetch overlaps recent stored bars and `KLineSync.detectAdjustChange` compares their closes — if prices were recomputed (ex-dividend event) the stock's rows are wiped and re-downloaded. 30m is fetched directly from the network (EastMoney `klt=30`, forward-adjusted), independent of the 5m bars. Network access is `data/EastMoneyKlineApi.kt` (EastMoney primary `fqt=1` + Tencent `qfq` fallback, blocking calls — background thread only). The seed DB deliberately ships **empty** K-line tables (watchlist groups/items only); `scripts/import-market.ps1` is a deprecated relic and the former Tongdaxin seed importer was removed when K-line moved to runtime sync.
