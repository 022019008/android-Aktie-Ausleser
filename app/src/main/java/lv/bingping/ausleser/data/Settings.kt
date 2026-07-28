package lv.bingping.ausleser.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置（SharedPreferences）：自建 K 线数据源 Aktie_datasource 的地址与轮询开关。
 *
 * 本地测试时手机与服务器同连一个局域网 WiFi（如 MG02），
 * 服务器 IP / 端口由设置页（SettingsActivity）手工配置。
 */
object Settings {

    private const val PREFS_NAME = "ausleser_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_POLL_ENABLED = "poll_enabled"

    /** 服务器端口缺省值（与服务端 AKTIE_PORT 缺省一致）。 */
    const val DEFAULT_PORT = 8000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已配置的服务器 IP；未配置时为空串。 */
    fun getServerIp(ctx: Context): String =
        prefs(ctx).getString(KEY_SERVER_IP, "").orEmpty()

    fun getServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SERVER_PORT, DEFAULT_PORT)

    /** 保存服务器地址（IP 去除首尾空白）。 */
    fun setServerConfig(ctx: Context, ip: String, port: Int) {
        prefs(ctx).edit()
            .putString(KEY_SERVER_IP, ip.trim())
            .putInt(KEY_SERVER_PORT, port)
            .apply()
    }

    /** 服务地址，形如 http://192.168.1.100:8000 。 */
    fun getBaseUrl(ctx: Context): String =
        "http://${getServerIp(ctx)}:${getServerPort(ctx)}"

    /** 服务器地址是否已配置（IP 非空）。 */
    fun isConfigured(ctx: Context): Boolean = getServerIp(ctx).isNotEmpty()

    /** 盘中轮询开关，缺省开启。 */
    fun isPollEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_POLL_ENABLED, true)

    fun setPollEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_POLL_ENABLED, enabled).apply()
    }
}
