package io.github.mihealthbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext
import kotlinx.coroutines.Dispatchers

/** 免责声明文本：首次启动弹窗与“免责声明”页面共用。 */
object Legal {
    const val DISCLAIMER_TITLE = "免责声明"

    val DISCLAIMER = listOf(
        "MiHealthBridge 是个人开发的开源工具，与小米公司（Xiaomi）及 Google 没有任何关联，未获得其授权或认可。",
        "“小米”“Xiaomi”“Mi”及相关标识是小米公司的商标，Health Connect 是 Google LLC 的商标，本应用中仅用于说明兼容性。",
        "本应用通过小米非官方、未公开的云端接口读取你本人账号下的健康数据。小米可能随时修改或关闭该接口；使用此类接口也可能不符合小米的用户协议，由此产生的账号限制等后果由使用者自行承担。",
        "同步到 Health Connect 的数据仅供个人参考，不能作为医疗诊断或治疗的依据。",
        "本应用没有服务器，不收集任何个人信息。小米账号凭据仅加密保存在本机，只发送给小米官方服务器。",
        "本软件按“原样”提供，不附带任何明示或暗示的保证，详见开源许可中的 Apache License 2.0。",
    )
}

/** “免责声明”与“开源许可”页面。 */
class LegalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val licenses = intent.getStringExtra(EXTRA_PAGE) == PAGE_LICENSES
        setContent {
            BridgeTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Tint) {
                    if (licenses) LicensesScreen() else DisclaimerScreen()
                }
            }
        }
    }

    @Composable
    private fun PageHeader(title: String) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ 返回", Modifier.clip(RoundedCornerShape(6.dp)).clickable { finish() }.padding(vertical = 6.dp, horizontal = 4.dp),
                fontSize = 14.sp, color = Palette.Navy,
            )
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
    }

    @Composable
    private fun DisclaimerScreen() {
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PageHeader(Legal.DISCLAIMER_TITLE) }
            item {
                Section("使用前请阅读") {
                    Legal.DISCLAIMER.forEach { Text("• $it", fontSize = 14.sp, lineHeight = 21.sp, color = Palette.Ink) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun LicensesScreen() {
        var libraries by remember { mutableStateOf<List<Library>?>(null) }
        var loadError by remember { mutableStateOf<String?>(null) }
        val license = remember { readAsset("LICENSE") }
        val notice = remember { readAsset("NOTICE") }

        LaunchedEffect(Unit) {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    Libs.Builder().withContext(applicationContext).build().libraries
                        .sortedBy { it.name.lowercase() }
                }
            }.onSuccess { libraries = it }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
        }

        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PageHeader("开源许可") }
            item {
                Section("MiHealthBridge") {
                    Caption("本应用以 Apache License 2.0 开源。小米健康云的登录与签名协议移植自 HealthMi（Apache-2.0）和 mi-fitness-mcp-cn（MIT），声明见 NOTICE。")
                    ExpandableText("NOTICE", notice)
                    ExpandableText("Apache License 2.0（LICENSE）", license)
                }
            }
            item {
                Text(
                    when {
                        loadError != null -> "第三方库列表加载失败：$loadError"
                        libraries == null -> "正在加载第三方库…"
                        else -> "第三方库（${libraries!!.size} 个）"
                    },
                    Modifier.padding(start = 4.dp, top = 8.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.Muted,
                )
            }
            items(libraries.orEmpty(), key = { it.uniqueId }) { LibraryCard(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun LibraryCard(lib: Library) {
        var expanded by rememberSaveable(lib.uniqueId) { mutableStateOf(false) }
        val licenseNames = lib.licenses.joinToString("、") { it.name }.ifEmpty { "未注明许可证" }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Paper)
                .border(1.dp, Palette.Rule, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lib.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Ink)
                lib.artifactVersion?.let { Text(it, fontSize = 12.sp, color = Palette.Muted) }
            }
            Text(lib.uniqueId, fontSize = 11.sp, color = Palette.Faint)
            Text(licenseNames, fontSize = 12.sp, color = Palette.Navy)
            if (expanded) {
                lib.website?.let { Caption(it) }
                lib.licenses.forEach { l ->
                    val text = l.licenseContent?.takeIf { it.isNotBlank() } ?: l.url ?: l.name
                    LicenseBody(text)
                }
            }
        }
    }

    @Composable
    private fun ExpandableText(title: String, body: String) {
        var expanded by rememberSaveable(title) { mutableStateOf(false) }
        ExpandableHeader(title, "", expanded) { expanded = !expanded }
        if (expanded) LicenseBody(body)
    }

    @Composable
    private fun LicenseBody(text: String) {
        Text(
            text,
            Modifier.fillMaxWidth().background(Palette.Tint, RoundedCornerShape(8.dp)).padding(10.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, color = Palette.Ink,
        )
    }

    private fun readAsset(name: String): String =
        runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("（未找到 $name）")

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val PAGE_LICENSES = "licenses"
        private const val PAGE_DISCLAIMER = "disclaimer"

        fun licenses(context: Context) =
            context.startActivity(Intent(context, LegalActivity::class.java).putExtra(EXTRA_PAGE, PAGE_LICENSES))

        fun disclaimer(context: Context) =
            context.startActivity(Intent(context, LegalActivity::class.java).putExtra(EXTRA_PAGE, PAGE_DISCLAIMER))
    }
}
