package io.github.mihealthbridge.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Health Connect 权限页里“隐私政策”入口。 */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(56, 72, 56, 56)
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            setLineSpacing(0f, 1.35f)
            text = """
                MiHealthBridge 隐私说明

                • 本应用只向 Health Connect 写入数据，不读取其中的任何数据。
                • 数据来源：你本人小米账号下「小米运动健康」云端的健康数据。
                • 小米账号凭据（userId / passToken / 会话）仅加密保存在本机，
                  只发送给小米官方服务器（account.xiaomi.com、hlth.io.mi.com）。
                • 不包含任何统计、广告或第三方服务器。
                • 卸载应用即删除全部本地数据；已写入 Health Connect 的数据可在
                  Health Connect 设置中按应用删除。
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
