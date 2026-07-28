package lv.bingping.ausleser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import lv.bingping.ausleser.data.DatasourceApi
import lv.bingping.ausleser.data.Settings
import lv.bingping.ausleser.service.PollService
import java.util.concurrent.Executors

/**
 * 服务器设置页：手工配置自建 K 线数据源 Aktie_datasource 的地址
 * （本地测试：手机与服务器同连一个局域网 WiFi，如 MG02）与盘中轮询开关。
 *
 * “测试连接”按当前输入值（无需先保存）后台请求 /health 并回主线程提示；
 * “保存”写入 [Settings] 并按开关启停 [PollService]。
 */
class SettingsActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.settings_toolbar)
            .setNavigationOnClickListener { finish() }

        val etIp = findViewById<TextInputEditText>(R.id.et_server_ip)
        val etPort = findViewById<TextInputEditText>(R.id.et_server_port)
        val switchPoll = findViewById<SwitchMaterial>(R.id.switch_poll)

        etIp.setText(Settings.getServerIp(this))
        etPort.setText(Settings.getServerPort(this).toString())
        switchPoll.isChecked = Settings.isPollEnabled(this)

        findViewById<Button>(R.id.btn_test_connection).setOnClickListener {
            testConnection(
                etIp.text?.toString()?.trim().orEmpty(),
                parsePort(etPort.text?.toString())
            )
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val ip = etIp.text?.toString()?.trim().orEmpty()
            if (ip.isEmpty()) {
                Toast.makeText(this, R.string.server_ip_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val enabled = switchPoll.isChecked
            Settings.setServerConfig(this, ip, parsePort(etPort.text?.toString()))
            Settings.setPollEnabled(this, enabled)
            if (enabled) PollService.start(this) else PollService.stop(this)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 后台对输入地址做健康检查（不依赖已保存配置），结果回主线程提示。 */
    private fun testConnection(ip: String, port: Int) {
        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.server_ip_empty, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.testing_connection, Toast.LENGTH_SHORT).show()
        val baseUrl = "http://$ip:$port"
        executor.execute {
            val ok = DatasourceApi.health(baseUrl)
            mainHandler.post {
                if (!isDestroyed) {
                    Toast.makeText(
                        this,
                        if (ok) R.string.connection_ok else R.string.connection_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 端口解析：非法 / 越界时回退缺省值。 */
    private fun parsePort(text: String?): Int =
        text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Settings.DEFAULT_PORT

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
