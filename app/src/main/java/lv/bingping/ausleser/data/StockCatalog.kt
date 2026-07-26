package lv.bingping.ausleser.data

/**
 * 股票目录（搜索宇宙）。
 *
 * 当前内置一份样例数据，用于演示“代码 / 名称 / 拼音首字母”三种搜索。
 * 后续接入真实行情数据源时，只需替换 [ALL] 的来源，搜索与列表逻辑无需改动。
 */
object StockCatalog {

    val ALL: List<Stock> = listOf(
        Stock("600519", "贵州茅台", "GZMT"),
        Stock("000858", "五粮液", "WLY"),
        Stock("601318", "中国平安", "ZGPA"),
        Stock("600036", "招商银行", "ZSYH"),
        Stock("000333", "美的集团", "MDJT"),
        Stock("600276", "恒瑞医药", "HRYY"),
        Stock("002594", "比亚迪", "BYD"),
        Stock("300750", "宁德时代", "NDSD"),
        Stock("601899", "紫金矿业", "ZJKY"),
        Stock("600900", "长江电力", "CJDL"),
        Stock("000001", "平安银行", "PAYH"),
        Stock("601012", "隆基绿能", "LJLN"),
        Stock("002475", "立讯精密", "LXJM"),
        Stock("600887", "伊利股份", "YLGF"),
        Stock("000725", "京东方A", "JDFA"),
        Stock("601888", "中国中免", "ZGZM"),
        Stock("300059", "东方财富", "DFCF"),
        Stock("002714", "牧原股份", "MYGF"),
        Stock("600309", "万华化学", "WHHX"),
        Stock("601166", "兴业银行", "XYYH"),
        Stock("510300", "沪深300ETF", "HS300ETF"),
        Stock("510500", "中证500ETF", "ZZ500ETF"),
        Stock("518880", "黄金ETF", "HJETF"),
        Stock("513100", "纳指ETF", "NZETF"),
        Stock("159915", "创业板ETF", "CYBETF"),
        Stock("512880", "证券ETF", "ZQETF"),
        Stock("512690", "酒ETF", "JETF"),
        Stock("515790", "光伏ETF", "GFETF")
    )

    /**
     * 按 代码 / 名称 / 拼音首字母 过滤。空查询返回全部（作为初始候选）。
     */
    fun search(query: String): List<Stock> {
        val q = query.trim()
        if (q.isEmpty()) return ALL
        val qu = q.uppercase()
        return ALL.filter { stock ->
            stock.code.contains(qu) ||
                stock.name.contains(q, ignoreCase = true) ||
                stock.pinyinInitials.uppercase().contains(qu)
        }
    }
}
