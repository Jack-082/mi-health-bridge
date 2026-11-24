package io.github.mihealthbridge.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import io.github.mihealthbridge.sync.Settings

/**
 * 在小米官方登录页登录（支持验证码 / 短信二次验证），
 * 登录成功后从 account.xiaomi.com 的 Cookie 中取出 userId + passToken。
 * 本应用不接触账号密码。
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.setAcceptCookie(true)

        webView = WebView(this)
        cookies.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tryCapture()
            }
        }
        setContentView(webView)
        webView.loadUrl(LOGIN_URL)
    }

    private fun tryCapture() {
        if (done) return
        val raw = CookieManager.getInstance().getCookie("https://account.xiaomi.com") ?: return
        val map = raw.split(';').mapNotNull {
            val kv = it.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val userId = map["userId"].orEmpty()
        val passToken = map["passToken"].orEmpty()
        if (userId.isEmpty() || passToken.isEmpty()) return

        done = true
        Settings(this).apply {
            this.userId = userId
            this.passToken = passToken
            this.session = null
            this.needsRelogin = false
        }
        CookieManager.getInstance().removeAllCookies(null)
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=miothealth&_locale=zh_CN"
    }
}
