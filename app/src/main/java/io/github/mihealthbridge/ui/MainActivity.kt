package io.github.mihealthbridge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import io.github.mihealthbridge.hc.HealthConnectWriter
import io.github.mihealthbridge.sync.Settings
import io.github.mihealthbridge.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次重组用到的设置快照（Settings 不是 Compose 状态，按 refreshTick 重新读取）。 */
internal data class UiState(
    val loggedIn: Boolean,
    val userId: String,
    val needsRelogin: Boolean,
    val lastSuccess: Long,
    val lastResult: String,
    val fastMinutes: Int,
    val initialDays: Int,
    val log: String,
)

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings

    // UI 状态
    private var refreshTick by mutableIntStateOf(0)
    private var grantedCount by mutableIntStateOf(0)
    private var hcStatus by mutableIntStateOf(HealthConnectClient.SDK_UNAVAILABLE)
    private var batteryIgnored by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
            if (settings.isLoggedIn) SyncScheduler.syncNow(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        SyncScheduler.ensurePeriodic(this)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { SyncScheduler.ensureFast(applicationContext) } }
        setContent { BridgeTheme { Surface(Modifier.fillMaxSize(), color = Palette.Paper) { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        hcStatus = HealthConnectWriter.sdkStatus(this)
        batteryIgnored = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE) {
            lifecycleScope.launch {
                grantedCount = runCatching {
                    HealthConnectWriter(this@MainActivity).grantedPermissions()
                        .count { it in HealthConnectWriter.PERMISSIONS }
                }.getOrDefault(0)
            }
        }
        refreshTick++
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun openHealthConnectInstall() {
        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private fun snapshot() = UiState(
        loggedIn = settings.isLoggedIn,
        userId = settings.userId.orEmpty(),
        needsRelogin = settings.needsRelogin,
        lastSuccess = settings.lastSuccessMillis,
        lastResult = settings.lastResult,
        fastMinutes = settings.fastIntervalMinutes,
        initialDays = settings.initialDays,
        log = settings.log,
    )

    private fun openLogin() = loginLauncher.launch(Intent(this, LoginActivity::class.java))

    private val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
    }

    @Composable
    private fun Screen() {
        // 每 3 秒刷新一次显示（同步在后台 Worker 中进行）
        LaunchedEffect(Unit) {
            while (true) { delay(3000); refreshTick++ }
        }
        val tick = refreshTick
        val s = remember(tick) { snapshot() }
        val totalPerms = HealthConnectWriter.PERMISSIONS.size

        val accountStatus = when {
            !s.loggedIn -> Status.None
            s.needsRelogin -> Status.Error
            else -> Status.Ok
        }
        val permStatus = when {
            hcStatus != HealthConnectClient.SDK_AVAILABLE -> Status.Error
            grantedCount >= totalPerms -> Status.Ok
            grantedCount > 0 -> Status.Warn
            else -> Status.None
        }
        val syncStatus = when {
            s.lastResult.isEmpty() -> Status.None
            s.lastResult.startsWith("同步成功") -> Status.Ok
            else -> Status.Error
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 标题区
            Column(Modifier.padding(top = 28.dp, bottom = 16.dp)) {
                Text("MiHealthBridge", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, letterSpacing = (-0.3).sp)
                Spacer(Modifier.height(4.dp))
                Text("小米运动健康 → Health Connect", fontSize = 13.sp, color = Palette.Muted)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LegendItem(accountStatus, "账号")
                    LegendItem(permStatus, "权限")
                    LegendItem(syncStatus, "同步")
                }
            }
            HorizontalDivider(thickness = 1.5.dp, color = Palette.Ink)

            Panel("a", "小米账号") {
                if (s.loggedIn) {
                    StatTable {
                        StatRow("账号 ID", s.userId, accountStatus)
                        StatRow("状态", if (s.needsRelogin) "登录已过期" else "已登录")
                    }
                    if (s.needsRelogin) Caption("登录已过期，请重新登录后继续同步。", Palette.Red)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("重新登录", Modifier.weight(1f)) { openLogin() }
                        SecondaryButton("退出", Modifier.weight(1f)) { settings.logout(); refresh() }
                    }
                } else {
                    Caption("在小米官方页面登录，与手机上「小米运动健康」使用同一账号。本应用不接触账号密码。")
                    PrimaryButton("登录小米账号", Modifier.fillMaxWidth()) { openLogin() }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Palette.Rule)

            Panel("b", "Health Connect") {
                when (hcStatus) {
                    HealthConnectClient.SDK_AVAILABLE -> {
                        StatTable {
                            StatRow("写入权限", "$grantedCount / $totalPerms", permStatus)
                        }
                        PrimaryButton("授权写入", Modifier.fillMaxWidth()) {
                            permissionLauncher.launch(HealthConnectWriter.PERMISSIONS)
                        }
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        Caption("需要安装或更新 Health Connect。")
                        PrimaryButton("去安装", Modifier.fillMaxWidth()) { openHealthConnectInstall() }
                    }
                    else -> Caption("此设备不支持 Health Connect（需 Android 9+，Android 13 及以下需安装 Health Connect 应用）。", Palette.Red)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Palette.Rule)

            Panel("c", "同步") {
                StatTable {
                    StatRow("上次成功", if (s.lastSuccess > 0) fmt(s.lastSuccess) else "从未")
                    StatRow("最近结果", s.lastResult.ifEmpty { "—" }, syncStatus)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("立即同步", Modifier.weight(1f)) { SyncScheduler.syncNow(this@MainActivity) }
                    SecondaryButton("全量重同步", Modifier.weight(1f)) {
                        settings.resetSyncState()
                        SyncScheduler.syncNow(this@MainActivity)
                    }
                }
                FieldLabel("同步间隔")
                Segmented(
                    options = listOf(2 to "2 min", 5 to "5 min", 10 to "10 min", 0 to "15 min"),
                    selected = s.fastMinutes,
                ) { m -> SyncScheduler.setFastMode(this@MainActivity, m); refresh() }
                Caption("15 分钟兜底任务始终开启；更短间隔会额外调度单次任务。")
                FieldLabel("首次 / 全量同步回溯天数")
                Segmented(
                    options = listOf(1 to "1 d", 7 to "7 d", 30 to "30 d", 90 to "90 d"),
                    selected = s.initialDays,
                ) { d -> settings.initialDays = d; refresh() }
                if (!batteryIgnored) {
                    SecondaryButton("关闭电池优化（后台同步更及时）", Modifier.fillMaxWidth()) { requestIgnoreBattery() }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Palette.Rule)

            Panel("d", "日志") {
                Box(Modifier.fillMaxWidth().background(Palette.Tint).padding(12.dp)) {
                    Text(
                        s.log.lines().takeLast(60).reversed().joinToString("\n").ifEmpty { "暂无" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Palette.Ink,
                    )
                }
            }

            HorizontalDivider(thickness = 1.5.dp, color = Palette.Ink)
            Column(Modifier.padding(top = 10.dp, bottom = 28.dp)) {
                Caption("仅向 Health Connect 写入数据，不读取；小米账号凭据加密保存在本机。")
                Spacer(Modifier.height(2.dp))
                Caption("MiHealthBridge v$versionName", Palette.Faint)
            }
        }
    }

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
}
