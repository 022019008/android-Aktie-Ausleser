package lv.bingping.ausleser.util

import android.util.Log

/**
 * 应用统一日志工具，按操作来源划分 TAG：
 *
 *  - 数据库操作：[db] / [dbError]，TAG = [TAG_DB]
 *  - 网络操作：[net] / [netError]，TAG = [TAG_NET]
 *
 * 过滤示例：`adb logcat -s AusleserDb:* AusleserNet:*`
 *
 * 网络日志覆盖 K 线同步（EastMoneyKlineApi / KLineSync）的请求、响应与失败；
 * 搜索数据源接入后同样统一经 [net] / [netError] 输出。
 */
object AppLog {

    const val TAG_DB = "AusleserDb"
    const val TAG_NET = "AusleserNet"

    /** 记录一条数据库操作日志（方法名、参数、结果）。 */
    fun db(msg: String) {
        Log.d(TAG_DB, msg)
    }

    /** 记录数据库操作失败。 */
    fun dbError(msg: String, t: Throwable) {
        Log.e(TAG_DB, msg, t)
    }

    /** 记录一条网络操作日志（请求、响应、占位点击等）。 */
    fun net(msg: String) {
        Log.d(TAG_NET, msg)
    }

    /** 记录网络操作失败。 */
    fun netError(msg: String, t: Throwable) {
        Log.e(TAG_NET, msg, t)
    }
}
