package lv.bingping.ausleser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import lv.bingping.ausleser.util.AppLog
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 应用数据库帮助类（SQLite）。
 *
 * 初始数据（默认分组等）不在代码中内置，而由 assets 预置种子库提供
 * （见 [installIfNeeded] 与 assets/databases/README.md）；
 * [onCreate] 仅在缺少种子库时兜底建立空表结构。
 *
 * 所有数据库操作均经 [AppLog.db] 输出日志（TAG: AusleserDb），
 * 可用 `adb logcat -s AusleserDb:*` 过滤查看。
 */
class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        AppLog.db("onCreate: 新建库 $DB_NAME v$DB_VERSION（分组表/自选表/K线表）")
        createGroupTable(db)
        createStockTable(db)
        createKTables(db, K_TABLES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        AppLog.db("onUpgrade: $DB_NAME 版本升级 v$oldVersion -> v$newVersion")
        if (oldVersion < 2) createStockTable(db)
        if (oldVersion < 3) createKTables(db, listOf(TABLE_K_5M, TABLE_K_DAY))
        if (oldVersion < 4) {
            // v4：新增 30 分钟表；K 线一律改由 app 运行时网络同步（前复权），
            // 旧种子库带来的不复权历史整体作废，清空后由 KLineSync 重建
            createKTables(db, listOf(TABLE_K_30M))
            for (table in listOf(TABLE_K_5M, TABLE_K_DAY)) {
                db.execSQL("DELETE FROM $table")
            }
            AppLog.db("onUpgrade: 已清空种子库遗留 K 线（bfq 作废，改由网络同步 qfq）")
        }
    }

    private fun createGroupTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SELECT_GROUP (
                $COLUMN_ID   INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createStockTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SELECT_STOCK (
                $COLUMN_ID    INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_GROUP_ID INTEGER NOT NULL,
                $COL_CODE     TEXT NOT NULL,
                $COLUMN_NAME  TEXT NOT NULL,
                $COL_ADDED_AT INTEGER NOT NULL DEFAULT 0,
                UNIQUE($COL_GROUP_ID, $COL_CODE)
            )
            """.trimIndent()
        )
    }

    /** 创建 K 线表（timestamp 为 Unix 秒，adjust 存复权类型：种子库时代为 'bfq'，v4 起应用同步写入 'qfq'）。 */
    private fun createKTables(db: SQLiteDatabase, tables: List<String>) {
        for (table in tables) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $table (
                    $COL_CODE      TEXT NOT NULL,
                    $COL_TIMESTAMP INTEGER NOT NULL,
                    $COL_OPEN      REAL NOT NULL,
                    $COL_HIGH      REAL NOT NULL,
                    $COL_LOW       REAL NOT NULL,
                    $COL_CLOSE     REAL NOT NULL,
                    $COL_VOLUME    REAL NOT NULL,
                    $COL_AMOUNT    REAL NOT NULL,
                    $COL_ADJUST    TEXT NOT NULL,
                    PRIMARY KEY($COL_CODE, $COL_TIMESTAMP)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_${table}_code_time ON $table($COL_CODE, $COL_TIMESTAMP)"
            )
        }
    }

    /** 查询全部自选分组（按 id 升序）。 */
    fun querySelectGroups(): List<SelectGroup> {
        val start = SystemClock.elapsedRealtime()
        val groups = mutableListOf<SelectGroup>()
        readableDatabase.query(
            TABLE_SELECT_GROUP,
            arrayOf(COLUMN_ID, COLUMN_NAME),
            null, null, null, null,
            "$COLUMN_ID ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(COLUMN_NAME)
            while (cursor.moveToNext()) {
                groups.add(
                    SelectGroup(
                        id = cursor.getLong(idIndex),
                        name = cursor.getString(nameIndex)
                    )
                )
            }
        }
        AppLog.db("querySelectGroups() -> ${groups.size} 个分组，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return groups
    }

    /** 新增一个自选分组，返回新行 id（失败返回 -1）。 */
    fun insertSelectGroup(name: String): Long {
        val rowId = writableDatabase.insert(
            TABLE_SELECT_GROUP,
            null,
            ContentValues().apply { put(COLUMN_NAME, name) }
        )
        AppLog.db("insertSelectGroup(name=$name) -> rowId=$rowId")
        return rowId
    }

    /** 修改指定分组的名称。 */
    fun updateSelectGroupName(id: Long, name: String): Int {
        val rows = writableDatabase.update(
            TABLE_SELECT_GROUP,
            ContentValues().apply { put(COLUMN_NAME, name) },
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )
        AppLog.db("updateSelectGroupName(id=$id, name=$name) -> 影响 $rows 行")
        return rows
    }

    /** 删除指定分组。 */
    fun deleteSelectGroup(id: Long): Int {
        val rows = writableDatabase.delete(
            TABLE_SELECT_GROUP,
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )
        AppLog.db("deleteSelectGroup(id=$id) -> 影响 $rows 行")
        return rows
    }

    /** 查询指定分组下全部自选股票（按加入时间升序）。 */
    fun queryStocks(groupId: Long): List<SelectStock> {
        val start = SystemClock.elapsedRealtime()
        val list = mutableListOf<SelectStock>()
        readableDatabase.query(
            TABLE_SELECT_STOCK,
            arrayOf(COLUMN_ID, COL_GROUP_ID, COL_CODE, COLUMN_NAME),
            "$COL_GROUP_ID=?",
            arrayOf(groupId.toString()),
            null, null,
            "$COL_ADDED_AT ASC, $COLUMN_ID ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val gidIndex = cursor.getColumnIndexOrThrow(COL_GROUP_ID)
            val codeIndex = cursor.getColumnIndexOrThrow(COL_CODE)
            val nameIndex = cursor.getColumnIndexOrThrow(COLUMN_NAME)
            while (cursor.moveToNext()) {
                list.add(
                    SelectStock(
                        id = cursor.getLong(idIndex),
                        groupId = cursor.getLong(gidIndex),
                        code = cursor.getString(codeIndex),
                        name = cursor.getString(nameIndex)
                    )
                )
            }
        }
        AppLog.db("queryStocks(groupId=$groupId) -> ${list.size} 只股票，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return list
    }

    /** 查询指定分组已包含的股票代码集合（用于搜索弹层标记“已添加”）。 */
    fun queryStockCodes(groupId: Long): Set<String> {
        val codes = queryStocks(groupId).map { it.code }.toSet()
        AppLog.db("queryStockCodes(groupId=$groupId) -> ${codes.size} 个代码")
        return codes
    }

    /** 将股票加入指定分组；同组同代码已存在时忽略，返回 -1。 */
    fun insertStock(groupId: Long, code: String, name: String): Long {
        val rowId = writableDatabase.insertWithOnConflict(
            TABLE_SELECT_STOCK,
            null,
            ContentValues().apply {
                put(COL_GROUP_ID, groupId)
                put(COL_CODE, code)
                put(COLUMN_NAME, name)
                put(COL_ADDED_AT, System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        AppLog.db(
            "insertStock(groupId=$groupId, code=$code, name=$name) -> " +
                if (rowId == -1L) "忽略（同组同代码已存在）" else "rowId=$rowId"
        )
        return rowId
    }

    /** 从自选移除指定股票行。 */
    fun deleteStock(id: Long): Int {
        val rows = writableDatabase.delete(
            TABLE_SELECT_STOCK,
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )
        AppLog.db("deleteStock(id=$id) -> 影响 $rows 行")
        return rows
    }

    /** 查询全部自选股票（跨分组，按加入时间升序）；轮询服务据此遍历同步。 */
    fun queryAllSelectStocks(): List<SelectStock> {
        val list = mutableListOf<SelectStock>()
        readableDatabase.query(
            TABLE_SELECT_STOCK,
            arrayOf(COLUMN_ID, COL_GROUP_ID, COL_CODE, COLUMN_NAME),
            null, null, null, null,
            "$COL_ADDED_AT ASC, $COLUMN_ID ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val gidIndex = cursor.getColumnIndexOrThrow(COL_GROUP_ID)
            val codeIndex = cursor.getColumnIndexOrThrow(COL_CODE)
            val nameIndex = cursor.getColumnIndexOrThrow(COLUMN_NAME)
            while (cursor.moveToNext()) {
                list.add(
                    SelectStock(
                        id = cursor.getLong(idIndex),
                        groupId = cursor.getLong(gidIndex),
                        code = cursor.getString(codeIndex),
                        name = cursor.getString(nameIndex)
                    )
                )
            }
        }
        AppLog.db("queryAllSelectStocks() -> ${list.size} 只股票")
        return list
    }

    /**
     * 查询指定代码的 K 线（按时间升序，最多取最新 [limit] 条）。
     *
     * 注：三张 K 线表（[TABLE_K_5M] / [TABLE_K_30M] / [TABLE_K_DAY]）均由
     * [KLineSync] 在打开 K 线页时从网络同步（前复权，adjust='qfq'）。
     */
    fun queryKBars(table: String, code: String, limit: Int = MAX_K_BARS): List<KBar> {
        requireKTable(table)
        val start = SystemClock.elapsedRealtime()
        val out = mutableListOf<KBar>()
        readableDatabase.query(
            table,
            K_BAR_COLUMNS,
            "$COL_CODE=?",
            arrayOf(code),
            null, null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        ).use { cursor -> out.addAll(readBars(cursor)) }
        out.reverse()
        AppLog.db("queryKBars(table=$table, code=$code, limit=$limit) -> ${out.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return out
    }

    /** 查询指定代码自 [sinceTs]（含）起的 K 线（按时间升序），供同步时取重叠区做复权检测。 */
    fun queryKBarsSince(table: String, code: String, sinceTs: Long): List<KBar> {
        requireKTable(table)
        val start = SystemClock.elapsedRealtime()
        val out = mutableListOf<KBar>()
        readableDatabase.query(
            table,
            K_BAR_COLUMNS,
            "$COL_CODE=? AND $COL_TIMESTAMP>=?",
            arrayOf(code, sinceTs.toString()),
            null, null,
            "$COL_TIMESTAMP ASC",
            null
        ).use { cursor -> out.addAll(readBars(cursor)) }
        AppLog.db("queryKBarsSince(table=$table, code=$code, sinceTs=$sinceTs) -> ${out.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return out
    }

    /** 统计指定代码在某 K 线表的存量（行数与时间范围）；空表返回全 0 摘要。 */
    fun kBarSummary(table: String, code: String): KBarSummary {
        requireKTable(table)
        val start = SystemClock.elapsedRealtime()
        var summary = KBarSummary(0, 0L, 0L)
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN($COL_TIMESTAMP), MAX($COL_TIMESTAMP) FROM $table WHERE $COL_CODE=?",
            arrayOf(code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                summary = KBarSummary(
                    count = cursor.getInt(0),
                    minTimestamp = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                    maxTimestamp = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                )
            }
        }
        AppLog.db("kBarSummary(table=$table, code=$code) -> $summary，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return summary
    }

    /** 指定代码已存档的最新时间戳（轮询增量同步水位）；无存档返回 0。 */
    fun maxKTimestamp(table: String, code: String): Long {
        requireKTable(table)
        readableDatabase.rawQuery(
            "SELECT MAX($COL_TIMESTAMP) FROM $table WHERE $COL_CODE=?",
            arrayOf(code)
        ).use { cursor ->
            val ts = if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            AppLog.db("maxKTimestamp(table=$table, code=$code) -> $ts")
            return ts
        }
    }

    /** 指定代码在某 K 线表是否存在非前复权（adjust<>'qfq'）行（种子库 bfq 遗留）。 */
    fun hasNonQfqBars(table: String, code: String): Boolean {
        requireKTable(table)
        val found = readableDatabase.query(
            table,
            arrayOf(COL_CODE),
            "$COL_CODE=? AND $COL_ADJUST<>'qfq'",
            arrayOf(code),
            null, null, null,
            "1"
        ).use { cursor -> cursor.moveToFirst() }
        AppLog.db("hasNonQfqBars(table=$table, code=$code) -> $found")
        return found
    }

    /** 删除指定代码在某 K 线表的全部行，返回影响行数。 */
    fun deleteKBars(table: String, code: String): Int {
        requireKTable(table)
        val rows = writableDatabase.delete(table, "$COL_CODE=?", arrayOf(code))
        AppLog.db("deleteKBars(table=$table, code=$code) -> 影响 $rows 行")
        return rows
    }

    /**
     * 批量写入 K 线（单事务，INSERT OR REPLACE 幂等可重跑），[adjust] 记录复权类型。
     * 返回写入条数（空列表返回 0）。
     */
    fun upsertKBars(table: String, code: String, bars: List<KBar>, adjust: String): Int {
        requireKTable(table)
        if (bars.isEmpty()) return 0
        val start = SystemClock.elapsedRealtime()
        val db = writableDatabase
        var written = 0
        db.beginTransaction()
        try {
            for (bar in bars) {
                val rowId = db.insertWithOnConflict(
                    table,
                    null,
                    ContentValues().apply {
                        put(COL_CODE, code)
                        put(COL_TIMESTAMP, bar.timestamp)
                        put(COL_OPEN, bar.open)
                        put(COL_HIGH, bar.high)
                        put(COL_LOW, bar.low)
                        put(COL_CLOSE, bar.close)
                        put(COL_VOLUME, bar.volume)
                        put(COL_AMOUNT, bar.amount)
                        put(COL_ADJUST, adjust)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (rowId != -1L) written++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AppLog.db("upsertKBars(table=$table, code=$code, bars=${bars.size}, adjust=$adjust) -> 写入 $written 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
        return written
    }

    /** 从 K_BAR_COLUMNS 投影的游标顺序读出全部 bar（不改变游标位置语义，调用方负责 use/close）。 */
    private fun readBars(cursor: android.database.Cursor): List<KBar> {
        val tsIndex = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
        val openIndex = cursor.getColumnIndexOrThrow(COL_OPEN)
        val highIndex = cursor.getColumnIndexOrThrow(COL_HIGH)
        val lowIndex = cursor.getColumnIndexOrThrow(COL_LOW)
        val closeIndex = cursor.getColumnIndexOrThrow(COL_CLOSE)
        val volumeIndex = cursor.getColumnIndexOrThrow(COL_VOLUME)
        val amountIndex = cursor.getColumnIndexOrThrow(COL_AMOUNT)
        val out = ArrayList<KBar>(cursor.count)
        while (cursor.moveToNext()) {
            out.add(
                KBar(
                    timestamp = cursor.getLong(tsIndex),
                    open = cursor.getDouble(openIndex),
                    high = cursor.getDouble(highIndex),
                    low = cursor.getDouble(lowIndex),
                    close = cursor.getDouble(closeIndex),
                    volume = cursor.getDouble(volumeIndex),
                    amount = cursor.getDouble(amountIndex)
                )
            )
        }
        return out
    }

    private fun requireKTable(table: String) =
        require(table in K_TABLES) { "未知K线表: $table" }

    companion object {
        const val DB_NAME = "ausleser.db"
        const val DB_VERSION = 4

        /** 单次 K 线查询上限（2 年 5 分钟约 2.3 万根，留有余量）。 */
        const val MAX_K_BARS = 25_000

        /** assets 中预置种子库路径；未放置该文件时首次启动走 [onCreate] 空库新建流程。 */
        private const val ASSET_DB_PATH = "databases/$DB_NAME"

        const val TABLE_SELECT_GROUP = "t_selber_select_group"
        const val TABLE_SELECT_STOCK = "t_selber_select_stock"
        const val TABLE_K_5M = "t_k_5m"
        const val TABLE_K_30M = "t_k_30m"
        const val TABLE_K_DAY = "t_k_day"

        /** 全部 K 线表（schema 相同）。 */
        private val K_TABLES = listOf(TABLE_K_5M, TABLE_K_30M, TABLE_K_DAY)

        /** K 线查询投影列（顺序须与 [readBars] 的取列逻辑一致）。 */
        private val K_BAR_COLUMNS =
            arrayOf(COL_TIMESTAMP, COL_OPEN, COL_HIGH, COL_LOW, COL_CLOSE, COL_VOLUME, COL_AMOUNT)

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COL_GROUP_ID = "group_id"
        const val COL_CODE = "code"
        const val COL_ADDED_AT = "added_at"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_OPEN = "open"
        const val COL_HIGH = "high"
        const val COL_LOW = "low"
        const val COL_CLOSE = "close"
        const val COL_VOLUME = "volume"
        const val COL_AMOUNT = "amount"
        const val COL_ADJUST = "adjust"

        /**
         * 首次启动时将 assets 打包的预置种子库安装到内部存储（仅当库文件尚不存在时）。
         *
         * 必须在构造 [DbHelper] 之前调用；已存在库时直接返回，绝不覆盖用户数据。
         * 种子库须与当前 schema 一致且已设置 `PRAGMA user_version = [DB_VERSION]`，
         * 否则 SQLiteOpenHelper 打开时会重跑 onCreate/onUpgrade。
         * 若 assets 未放置种子库（如开发环境未构建），则静默回退到空库新建流程。
         */
        fun installIfNeeded(context: Context) {
            val outFile = context.getDatabasePath(DB_NAME)
            if (outFile.exists()) {
                AppLog.db("installIfNeeded: 库已存在，跳过种子库安装（${outFile.absolutePath}）")
                return
            }
            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.parentFile, "$DB_NAME.tmp")
            try {
                context.assets.open(ASSET_DB_PATH).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
            } catch (e: FileNotFoundException) {
                // 未打包种子库：由 SQLiteOpenHelper 首次打开时走 onCreate 新建
                AppLog.db("installIfNeeded: assets 未打包种子库（$ASSET_DB_PATH），回退 onCreate 空库新建")
                tmp.delete()
                return
            } catch (e: IOException) {
                // 拷贝中断（磁盘满等）：清掉半成品，向外抛，避免留下坏库
                AppLog.dbError("installIfNeeded: 拷贝种子库中断，已清理临时文件", e)
                tmp.delete()
                throw e
            }
            // 同目录 rename 为原子替换，避免拷一半的库被当作正式库打开
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            AppLog.db("installIfNeeded: 已从 assets 安装种子库 -> ${outFile.absolutePath}")
        }
    }
}

/** 某 K 线表中指定代码的存量概貌（供 [KLineSync] 决策）；空表时三项皆为 0。 */
data class KBarSummary(
    val count: Int,
    val minTimestamp: Long,
    val maxTimestamp: Long
)
