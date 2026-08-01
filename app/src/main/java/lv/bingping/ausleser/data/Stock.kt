package lv.bingping.ausleser.data

/** 可搜索的成员目录项（来自成员宇宙，未与分组绑定）。 */
data class Member(
    val code: String,
    val name: String,
    /** 名称拼音首字母（大写，数字/字母原样保留），用于拼音首字母搜索。 */
    val pinyinInitials: String
)

/** 已加入某自选分组的成员，对应表 t_selber_select_member。 */
data class SelectMember(
    val id: Long,
    val groupId: Long,
    val code: String,
    val name: String
)
