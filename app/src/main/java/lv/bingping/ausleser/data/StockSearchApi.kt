package lv.bingping.ausleser.data

import android.os.SystemClock
import lv.bingping.ausleser.util.AppLog
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 东方财富搜索联想接口（searchapi.eastmoney.com），支持 代码 / 名称 / 拼音首字母。
 *
 * [search] 为阻塞式调用，必须在后台线程执行；结果映射为 [Stock] 列表。
 * 仅保留 A 股（沪深主板/创业板/科创板/北A）与基金/ETF 类别，过滤指数等品种。
 * 请求、响应与失败均经 [AppLog] 输出网络日志（TAG: AusleserNet）。
 */
object StockSearchApi {

    private const val SUGGEST_URL = "https://searchapi.eastmoney.com/api/suggest/get"

    /** 东方财富公开联想接口 token（与网页搜索框同源）。 */
    private const val TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * 保留的证券类别（东财联想接口 Classify 字段取值）：
     *  - "AStock" 沪深 A 股（主板 + 创业板）
     *  - "23"     科创板（688/689；接口对科创板返回数字类别，SecurityType=25）
     *  - "NEEQ"   北A（8/4/920 代码段，SecurityType=27）
     *  - "Fund"   基金/ETF
     * 指数、债券、港股、美股等不进候选。
     */
    private val KEEP_CLASSIFY = setOf("AStock", "23", "NEEQ", "Fund")

    /**
     * 按关键字搜索候选股票。
     *
     * @param query 代码 / 名称 / 拼音首字母
     * @return 候选列表（无匹配时为空）；网络或解析失败抛 [IOException]
     */
    fun search(query: String, count: Int = 10): List<Stock> {
        val start = SystemClock.elapsedRealtime()
        val url = SUGGEST_URL +
            "?input=" + URLEncoder.encode(query, "UTF-8") +
            "&type=14&token=$TOKEN&count=$count"
        AppLog.net("search 请求: query=$query")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = parse(body)
            AppLog.net("search 响应: query=$query -> ${list.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
            return list
        } catch (e: IOException) {
            AppLog.netError("search 失败: query=$query, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw e
        } catch (e: Exception) {
            AppLog.netError("search 解析失败: query=$query, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw IOException(e)
        } finally {
            conn.disconnect()
        }
    }

    /** 解析联想接口 JSON；无匹配时 Data 为 null，返回空列表。 */
    private fun parse(body: String): List<Stock> {
        val table = JSONObject(body).optJSONObject("QuotationCodeTable") ?: return emptyList()
        val data = table.optJSONArray("Data") ?: return emptyList()
        val out = ArrayList<Stock>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optString("Classify") !in KEEP_CLASSIFY) continue
            val code = item.optString("Code")
            val name = item.optString("Name")
            if (code.isEmpty() || name.isEmpty()) continue
            out.add(Stock(code = code, name = name, pinyinInitials = item.optString("PinYin")))
        }
        return out
    }
}
