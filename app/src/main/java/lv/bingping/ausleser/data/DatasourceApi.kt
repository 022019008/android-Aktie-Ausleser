package lv.bingping.ausleser.data

import android.content.Context
import android.os.SystemClock
import lv.bingping.ausleser.util.AppLog
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自建 K 线数据源 Aktie_datasource 的 HTTP 客户端。
 *
 * 服务部署于局域网（v1 无鉴权），地址取自 [Settings]。
 * 全部为阻塞式调用，必须在后台线程执行；失败抛 [IOException]。
 * 请求、响应与失败均经 [AppLog] 输出网络日志（TAG: AusleserNet）。
 *
 * 量纲约定：服务端 volume 单位为股，本库为手
 * （1 手 = 100 股）→ 解析时 ÷100。
 * bars 行序：[timestamp, open, high, low, close, volume, amount]，与 [KBar] 一致。
 */
object DatasourceApi {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    /** GET /health 健康检查；HTTP 200 视为可达。[baseUrl] 形如 http://192.168.1.100:8000 。 */
    fun health(baseUrl: String): Boolean =
        try {
            sendStatus("GET", "$baseUrl/health") == HttpURLConnection.HTTP_OK
        } catch (e: IOException) {
            AppLog.netError("health 失败: $baseUrl", e)
            false
        }

    /**
     * GET /api/kline/{code}?freq=&limit= 取最近 [limit] 根 K 线（升序）。
     *
     * @param freq 频率：5m / 30m / 60m / day
     */
    fun fetchKline(ctx: Context, code: String, freq: String, limit: Int): List<KBar> {
        val url = "${Settings.getBaseUrl(ctx)}/api/kline/$code?freq=$freq&limit=$limit"
        return fetchBars(url, "code=$code freq=$freq limit=$limit")
    }

    /**
     * GET /api/kline/{code}?freq=&since= 增量取 timestamp>[since] 的全部 bar（升序）。
     */
    fun fetchKlineSince(ctx: Context, code: String, freq: String, since: Long): List<KBar> {
        val url = "${Settings.getBaseUrl(ctx)}/api/kline/$code?freq=$freq&since=$since"
        return fetchBars(url, "code=$code freq=$freq since=$since")
    }

    /**
     * POST /api/stocks 加入跟踪；新股由服务器后台全量回填，
     * 已激活旧股秒回（幂等，可重复调用）。
     */
    fun registerStock(ctx: Context, code: String, name: String): Boolean {
        val url = "${Settings.getBaseUrl(ctx)}/api/stocks"
        val body = JSONObject().put("code", code).put("name", name)
        val status = sendStatus("POST", url, body)
        val ok = status in 200..299
        if (!ok) AppLog.net("registerStock 异常状态: code=$code, HTTP $status")
        return ok
    }

    /**
     * DELETE /api/stocks/{code} 停用跟踪（服务端保留历史数据）。
     * 该股从未跟踪（404）也视为成功。
     */
    fun unregisterStock(ctx: Context, code: String): Boolean {
        val url = "${Settings.getBaseUrl(ctx)}/api/stocks/$code"
        val status = sendStatus("DELETE", url)
        val ok = status in 200..299 || status == HttpURLConnection.HTTP_NOT_FOUND
        if (!ok) AppLog.net("unregisterStock 异常状态: code=$code, HTTP $status")
        return ok
    }

    // ---------------------------------------------------------------- 内部实现

    /** 取 K 线并解析；网络/解析失败统一抛 [IOException]。 */
    private fun fetchBars(url: String, tag: String): List<KBar> {
        val start = SystemClock.elapsedRealtime()
        AppLog.net("kline 请求(数据源): $tag")
        return try {
            val list = parseBars(getBody(url))
            AppLog.net("kline 响应(数据源): $tag -> ${list.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
            list
        } catch (e: IOException) {
            AppLog.netError("kline 失败(数据源): $tag, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw e
        } catch (e: Exception) {
            AppLog.netError("kline 解析失败(数据源): $tag, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw IOException(e)
        }
    }

    /** GET 取响应体；非 200 抛 [IOException]。 */
    private fun getBody(url: String): String {
        val conn = openConnection(url, "GET")
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 bars 二维数组 → [KBar] 列表；volume 股→手（÷100）。 */
    private fun parseBars(body: String): List<KBar> {
        val arr = JSONObject(body).optJSONArray("bars") ?: return emptyList()
        val out = ArrayList<KBar>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 7) continue
            out.add(
                KBar(
                    timestamp = row.getLong(0),
                    open = row.getDouble(1),
                    high = row.getDouble(2),
                    low = row.getDouble(3),
                    close = row.getDouble(4),
                    volume = row.getDouble(5) / 100.0, // 股 -> 手（本库量纲）
                    amount = row.getDouble(6)
                )
            )
        }
        return out
    }

    /** 发送请求并返回 HTTP 状态码；网络错误抛 [IOException]。 */
    private fun sendStatus(method: String, url: String, body: JSONObject? = null): Int {
        val conn = openConnection(url, method)
        try {
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** 建立连接并套用统一超时配置。 */
    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = method
        }
}
