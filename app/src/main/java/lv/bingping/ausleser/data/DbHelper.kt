package lv.bingping.ausleser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 应用数据库帮助类（SQLite）。
 *
 * 目前包含自选分组表：
 *   t_selber_select_group(id, name)
 * 首次建库时插入两个默认分组："我的自选"、"ETF"。
 */
class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createGroupTable(db)
        // 默认分组
        insertGroup(db, DEFAULT_GROUP_MY_SELECT)
        insertGroup(db, DEFAULT_GROUP_ETF)
        createStockTable(db)
        createKTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createStockTable(db)
        if (oldVersion < 3) createKTables(db)
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

    /** 创建 5 分钟 / 日 K 线表（schema 与 market.db 对齐：timestamp 为 Unix 秒，adjust 存复权类型）。 */
    private fun createKTables(db: SQLiteDatabase) {
        for (table in listOf(TABLE_K_5M, TABLE_K_DAY)) {
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

    private fun insertGroup(db: SQLiteDatabase, name: String) {
        db.insert(
            TABLE_SELECT_GROUP,
            null,
            ContentValues().apply { put(COLUMN_NAME, name) }
        )
    }

    /** 查询全部自选分组（按 id 升序）。 */
    fun querySelectGroups(): List<SelectGroup> {
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
        return groups
    }

    /** 新增一个自选分组，返回新行 id（失败返回 -1）。 */
    fun insertSelectGroup(name: String): Long =
        writableDatabase.insert(
            TABLE_SELECT_GROUP,
            null,
            ContentValues().apply { put(COLUMN_NAME, name) }
        )

    /** 修改指定分组的名称。 */
    fun updateSelectGroupName(id: Long, name: String): Int =
        writableDatabase.update(
            TABLE_SELECT_GROUP,
            ContentValues().apply { put(COLUMN_NAME, name) },
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )

    /** 删除指定分组。 */
    fun deleteSelectGroup(id: Long): Int =
        writableDatabase.delete(
            TABLE_SELECT_GROUP,
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )

    /** 查询指定分组下全部自选股票（按加入时间升序）。 */
    fun queryStocks(groupId: Long): List<SelectStock> {
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
        return list
    }

    /** 查询指定分组已包含的股票代码集合（用于搜索弹层标记“已添加”）。 */
    fun queryStockCodes(groupId: Long): Set<String> =
        queryStocks(groupId).map { it.code }.toSet()

    /** 将股票加入指定分组；同组同代码已存在时忽略，返回 -1。 */
    fun insertStock(groupId: Long, code: String, name: String): Long =
        writableDatabase.insertWithOnConflict(
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

    /** 从自选移除指定股票行。 */
    fun deleteStock(id: Long): Int =
        writableDatabase.delete(
            TABLE_SELECT_STOCK,
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )

    companion object {
        const val DB_NAME = "ausleser.db"
        const val DB_VERSION = 3

        const val TABLE_SELECT_GROUP = "t_selber_select_group"
        const val TABLE_SELECT_STOCK = "t_selber_select_stock"
        const val TABLE_K_5M = "t_k_5m"
        const val TABLE_K_DAY = "t_k_day"
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

        const val DEFAULT_GROUP_MY_SELECT = "我的自选"
        const val DEFAULT_GROUP_ETF = "ETF"
    }
}
