package lv.bingping.ausleser.data

/**
 * 自选分组数据模型，对应数据库表 t_selber_select_group(id, name)。
 */
data class SelectGroup(
    val id: Long,
    val name: String
)
