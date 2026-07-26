package lv.bingping.ausleser.data

/** 可搜索的股票目录项（来自股票宇宙，未与分组绑定）。 */
data class Stock(
    val code: String,
    val name: String,
    /** 名称拼音首字母（大写，数字/字母原样保留），用于拼音首字母搜索。 */
    val pinyinInitials: String
)

/** 已加入某自选分组的股票，对应表 t_selber_select_stock。 */
data class SelectStock(
    val id: Long,
    val groupId: Long,
    val code: String,
    val name: String
)
