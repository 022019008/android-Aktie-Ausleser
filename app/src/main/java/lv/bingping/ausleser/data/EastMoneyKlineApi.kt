package lv.bingping.ausleser.data

import android.os.SystemClock
import lv.bingping.ausleser.util.AppLog
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * K 线网络数据源：主源东方财富（push2his，前复权），备源腾讯（ifzq.gtimg.cn）。
 * 支持三个周期，全部返回前复权（qfq）数据：
 *  - 5 分钟 [fetch5m]、30 分钟 [fetch30m]、日线 [fetchDay]。
 *
 * K 线统一由 [KLineSync] 在打开 K 线页时拉取入库（t_k_5m / t_k_30m / t_k_day），
 * 东财接口被限流/不可达时自动切换腾讯备源。
 * 阻塞式调用，必须在后台线程执行；请求/响应/失败经 [AppLog] 输出（TAG: AusleserNet）。
 */
object EastMoneyKlineApi {

    private const val KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
    private const val FIELDS1 = "f1,f2,f3,f4,f5,f6"

    /** f51=时间 f52=开 f53=收 f54=高 f55=低 f56=成交量(手) f57=成交额(元) f58=振幅。 */
    private const val FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58"

    /** 东财 klt 周期值：5/30 分钟、101=日线。 */
    private const val KLT_5M = 5
    private const val KLT_30M = 30
    private const val KLT_DAY = 101

    /** 腾讯备源；param=代码,周期,起始,结束,条数,复权。 */
    private const val TENCENT_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"

    /** 腾讯备源周期值，响应键优先取前缀 qfq 版本（如 qfqday）。 */
    private const val TENCENT_DAY = "day"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 浏览器 UA，避免被接口按 Dalvik/curl 指纹拒绝。 */
    private const val UA_BROWSER =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 日线时间戳归一化偏移：日期型 bar 记为当日 15:00（北京时间，与种子库/KBar 约定一致）。 */
    private const val DAY_BAR_OFFSET_SEC = 15L * 3600

    /** 东财偶发掐断连接（WAF/限流），分钟线无备源，失败重试一次。 */
    private const val EASTMONEY_RETRY = 1

    /**
     * 拉取 5 分钟 K 线（最近 [limit] 根，升序，前复权）；两个源都失败时抛 [IOException]。
     * 注：东财该端点 5 分钟深度约 2000+ 根（约一个半月），limit 超过可得深度时按实际返回。
     */
    fun fetch5m(code: String, limit: Int = 320): List<KBar> =
        fetchKline(code, KLT_5M, null, limit)

    /** 拉取 30 分钟 K 线（最近 [limit] 根，升序，前复权）；东财失败重试后仍失败抛 [IOException]。 */
    fun fetch30m(code: String, limit: Int): List<KBar> =
        fetchKline(code, KLT_30M, null, limit)

    /** 拉取日 K 线（最近 [limit] 根，升序，前复权）；东财失败时切腾讯备源，均失败抛 [IOException]。 */
    fun fetchDay(code: String, limit: Int): List<KBar> =
        fetchKline(code, KLT_DAY, TENCENT_DAY, limit)

    /**
     * 东财主源（失败重试 [EASTMONEY_RETRY] 次）→ 腾讯备源（仅 [tencentPeriod] 非空时，
     * 腾讯 fqkline 只支持日/周/月级别，分钟级无备源）。
     */
    private fun fetchKline(code: String, klt: Int, tencentPeriod: String?, limit: Int): List<KBar> {
        var lastError: IOException? = null
        repeat(EASTMONEY_RETRY + 1) { attempt ->
            try {
                return fetchEastMoney(code, klt, limit)
            } catch (e: IOException) {
                lastError = e
                AppLog.net("kline 东财主源失败(第 ${attempt + 1} 次): code=$code, klt=$klt, ${e.message}")
            }
        }
        if (tencentPeriod != null) {
            AppLog.net("kline 切换腾讯备源: code=$code, $tencentPeriod")
            return fetchTencent(code, tencentPeriod, limit)
        }
        throw lastError ?: IOException("kline 失败: code=$code, klt=$klt")
    }

    private fun fetchEastMoney(code: String, klt: Int, limit: Int): List<KBar> {
        val start = SystemClock.elapsedRealtime()
        // 沪市（5/6 开头）secid 前缀 1，深市（0/1/2/3 开头）前缀 0
        val secid = (if (code.startsWith("5") || code.startsWith("6")) "1." else "0.") + code
        val url = "$KLINE_URL?secid=$secid&fields1=$FIELDS1&fields2=$FIELDS2&klt=$klt&fqt=1&end=20500101&lmt=$limit"
        AppLog.net("kline 请求: code=$code, klt=$klt, limit=$limit")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA_BROWSER)
        }
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = parse(body)
            AppLog.net("kline 响应: code=$code, klt=$klt -> ${list.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
            return list
        } catch (e: IOException) {
            AppLog.netError("kline 失败: code=$code, klt=$klt, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw e
        } catch (e: Exception) {
            AppLog.netError("kline 解析失败: code=$code, klt=$klt, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw IOException(e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析东财 klines 数组："时间,开,收,高,低,量,额,振幅"。
     * 分钟线时间为 "yyyy-MM-dd HH:mm"；日线为 "yyyy-MM-dd"，归一化为当日 15:00。
     */
    private fun parse(body: String): List<KBar> {
        val data = JSONObject(body).optJSONObject("data") ?: return emptyList()
        val klines = data.optJSONArray("klines") ?: return emptyList()
        val out = ArrayList<KBar>(klines.length())
        for (i in 0 until klines.length()) {
            val f = klines.optString(i).split(",")
            if (f.size < 7) continue
            val ts = parseTimestamp(f[0]) ?: continue
            out.add(
                KBar(
                    timestamp = ts,
                    open = f[1].toDoubleOrNull() ?: continue,
                    close = f[2].toDoubleOrNull() ?: continue,
                    high = f[3].toDoubleOrNull() ?: continue,
                    low = f[4].toDoubleOrNull() ?: continue,
                    volume = f[5].toDoubleOrNull() ?: 0.0,
                    amount = f[6].toDoubleOrNull() ?: 0.0
                )
            )
        }
        return out
    }

    /** 腾讯备源（fqkline 仅支持日/周/月级别，此处 [period] 固定 day）；无成交额字段，置 0。 */
    private fun fetchTencent(code: String, period: String, limit: Int): List<KBar> {
        val start = SystemClock.elapsedRealtime()
        val symbol = (if (code.startsWith("5") || code.startsWith("6")) "sh" else "sz") + code
        // param=代码,周期,起始,结束,条数,复权（起始/结束留空，条数在第 5 段）
        val url = "$TENCENT_URL?param=$symbol,$period,,,$limit,qfq"
        AppLog.net("kline 请求(腾讯): code=$code, $period, limit=$limit")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA_BROWSER)
        }
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONObject("data")?.optJSONObject(symbol)
            val arr = data?.optJSONArray("qfq$period") ?: data?.optJSONArray(period)
            if (arr == null || arr.length() == 0) throw IOException("腾讯备源无数据")
            val out = ArrayList<KBar>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                val ts = parseTimestamp(row.optString(0)) ?: continue
                out.add(
                    KBar(
                        timestamp = ts,
                        open = row.optDouble(1),
                        close = row.optDouble(2),
                        high = row.optDouble(3),
                        low = row.optDouble(4),
                        volume = row.optDouble(5),
                        amount = 0.0
                    )
                )
            }
            AppLog.net("kline 响应(腾讯): code=$code, $period -> ${out.size} 条，耗时 ${SystemClock.elapsedRealtime() - start}ms")
            return out
        } catch (e: IOException) {
            AppLog.netError("kline 腾讯备源失败: code=$code, $period, 耗时 ${SystemClock.elapsedRealtime() - start}ms", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * K 线时间串 → Unix 秒（北京时间语义）：
     * 分钟线为 bar 结束时刻 "yyyy-MM-dd HH:mm"；日线仅 "yyyy-MM-dd"，记为当日 15:00 收盘。
     */
    private fun parseTimestamp(text: String): Long? {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        return if (text.length <= 10) {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = zone }
                .parse(text)?.time?.div(1000)?.plus(DAY_BAR_OFFSET_SEC)
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { timeZone = zone }
                .parse(text)?.time?.div(1000)
        }
    }
}
