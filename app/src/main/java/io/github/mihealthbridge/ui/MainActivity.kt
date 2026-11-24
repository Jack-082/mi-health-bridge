package io.github.mihealthbridge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        SyncScheduler.ensurePeriodic(this)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { SyncScheduler.ensureFastBlocking(applicationContext) } }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
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

    @Composable
    private fun Screen() {
        // 每 3 秒刷新一次显示（同步在后台 Worker 中进行）
        LaunchedEffect(Unit) {
            while (true) { delay(3000); refreshTick++ }
        }
        val tick = refreshTick // 读取状态以便定时重组（settings 不是 Compose 状态）

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("小米手环 → Health Connect", style = MaterialTheme.typography.headlineSmall)
            if (tick < 0) Text("")

            Section("1. 小米账号（小米运动健康）") {
                if (settings.isLoggedIn) {
                    Text("已登录：${settings.userId}")
                    if (settings.needsRelogin) Text("⚠ 登录已过期，请重新登录", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java)) }) { Text("重新登录") }
                        OutlinedButton(onClick = { settings.logout(); refresh() }) { Text("退出") }
                    }
                } else {
                    Text("在小米官方页面登录，与手机上「小米运动健康」使用同一账号。")
                    Button(onClick = { loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java)) }) { Text("登录小米账号") }
                }
            }

            Section("2. Health Connect") {
                when (hcStatus) {
                    HealthConnectClient.SDK_AVAILABLE -> {
                        Text("已授权 $grantedCount / ${HealthConnectWriter.PERMISSIONS.size} 项写入权限")
                        Button(onClick = { permissionLauncher.launch(HealthConnectWriter.PERMISSIONS) }) { Text("授权写入") }
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        Text("需要安装或更新 Health Connect")
                        Button(onClick = { openHealthConnectInstall() }) { Text("去安装") }
                    }
                    else -> Text("此设备不支持 Health Connect（需 Android 9+，Android 13 及以下需安装 Health Connect 应用）")
                }
            }

            Section("3. 同步") {
                val last = settings.lastSuccessMillis
                Text("上次成功：" + if (last > 0) fmt(last) else "从未")
                Text("最近结果：${settings.lastResult.ifEmpty { "—" }}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { SyncScheduler.syncNow(this@MainActivity) }) { Text("立即同步") }
                    OutlinedButton(onClick = {
                        settings.resetSyncState()
                        SyncScheduler.syncNow(this@MainActivity)
                    }) { Text("全量重同步") }
                }
                Text("同步频率（15 分钟兜底任务始终开启）", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "15分钟", 2 to "2分钟", 5 to "5分钟", 10 to "10分钟").forEach { (m, label) ->
                        FilterChip(
                            selected = settings.fastIntervalMinutes == m,
                            onClick = { SyncScheduler.setFastMode(this@MainActivity, m); refresh() },
                            label = { Text(label) },
                        )
                    }
                }
                Text("首次/全量同步回溯天数", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 7, 30, 90).forEach { d ->
                        FilterChip(
                            selected = settings.initialDays == d,
                            onClick = { settings.initialDays = d; refresh() },
                            label = { Text("${d}天") },
                        )
                    }
                }
                if (!batteryIgnored) {
                    OutlinedButton(onClick = { requestIgnoreBattery() }) { Text("关闭电池优化（后台同步更及时）") }
                }
            }

            Section("日志") {
                Text(
                    settings.log.lines().takeLast(60).reversed().joinToString("\n").ifEmpty { "暂无" },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
}
