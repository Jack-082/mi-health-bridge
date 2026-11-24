package io.github.mihealthbridge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.mihealthbridge.hc.HealthConnectWriter
import io.github.mihealthbridge.sync.BleAuthProbe
import io.github.mihealthbridge.sync.BleGattProbe
import io.github.mihealthbridge.sync.BleScaleProbe
import io.github.mihealthbridge.sync.MiHomeProbe
import io.github.mihealthbridge.sync.ScaleBleSync
import io.github.mihealthbridge.sync.ScaleSetup
import io.github.mihealthbridge.sync.Settings
import io.github.mihealthbridge.sync.SyncReport
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
    val report: SyncReport?,
    val scaleEnabled: Boolean,
    val scaleModel: String,
    val scaleMember: String,
    val scaleMembers: List<String>,
    val scaleBle: Boolean,
    val scaleBleResult: String,
    val scaleKind: String,
    val scaleName: String,
    val scaleBroadcast: String,
)

/** 后台同步任务的状态。 */
internal enum class SyncActivity(val label: String?) {
    Idle(null),
    Queued("已排队，等待网络或当前同步结束…"),
    Running("同步中…"),
    RunningFull("全量重同步中…"),
}

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings

    // UI 状态
    private var refreshTick by mutableIntStateOf(0)
    private var grantedCount by mutableIntStateOf(0)
    private var hcStatus by mutableIntStateOf(HealthConnectClient.SDK_UNAVAILABLE)
    private var batteryIgnored by mutableStateOf(false)
    private var logExpanded by mutableStateOf(false)
    private var reportExpanded by mutableStateOf(false)
    private var scaleDiagExpanded by mutableStateOf(false)
    /** 体脂秤检测进行中时的阶段提示；null = 未在检测。 */
    private var detectPhase by mutableStateOf<String?>(null)
    /** 申请蓝牙权限后无论是否授予都要继续的操作（检测时云端部分不依赖蓝牙）。 */
    private var pendingAfterPermission: (() -> Unit)? = null
    private var showDisclaimer by mutableStateOf(false)
    private var diagnosing by mutableStateOf(false)
    private var bleProbing by mutableStateOf(false)
    private var pendingBleAction: (() -> Unit)? = null

    private val blePermissions: Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) pendingBleAction?.invoke()
            else if (pendingBleAction != null) toast("没有蓝牙权限，无法诊断")
            pendingBleAction = null
            pendingAfterPermission?.invoke()
            pendingAfterPermission = null
        }

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
        showDisclaimer = !settings.disclaimerAccepted
        SyncScheduler.ensurePeriodic(this)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { SyncScheduler.ensureFast(applicationContext) } }
        if (settings.scaleBleEnabled) runCatching { ScaleBleSync.startScan(applicationContext) }
        setContent { BridgeTheme { Surface(Modifier.fillMaxSize(), color = Palette.Tint) { Screen() } } }
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
        report = SyncReport.fromJson(settings.lastReport),
        scaleEnabled = settings.scaleEnabled,
        scaleModel = settings.scaleModel,
        scaleMember = settings.scaleMember,
        scaleMembers = settings.scaleMembersSeen,
        scaleBle = settings.scaleBleEnabled,
        scaleBleResult = settings.scaleBleLastResult,
        scaleKind = ScaleSetup.currentKind(settings),
        scaleName = settings.scaleName,
        scaleBroadcast = settings.scaleBroadcast,
    )

    private fun openLogin() = loginLauncher.launch(Intent(this, LoginActivity::class.java))

    private fun withBlePermission(action: () -> Unit) {
        if (bleProbing) return
        val missing = blePermissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingBleAction = action
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bleProbe() = withBlePermission { startBleProbe() }

    /** 开启/关闭蓝牙直连体脂秤。 */
    private fun setScaleBle(on: Boolean) {
        if (!on) {
            settings.scaleBleEnabled = false
            ScaleBleSync.stopScan(applicationContext)
            ScaleBleSync.log(applicationContext, "已关闭蓝牙直连")
            refresh()
            return
        }
        withBlePermission {
            bleProbing = true
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { ScaleBleSync.ensureCredentials(applicationContext, forceRefresh = true) }
                    .onFailure { ScaleBleSync.log(applicationContext, "获取秤的 token 失败：${it.message}") }
                    .getOrDefault(false)
                var started = false
                if (ok) {
                    settings.scaleBleEnabled = true
                    started = ScaleBleSync.startScan(applicationContext)
                    ScaleBleSync.log(applicationContext, if (started) "已开启：称重时自动连接读数" else "已开启，但后台扫描注册失败（蓝牙是否已打开？）")
                }
                launch(Dispatchers.Main) {
                    bleProbing = false
                    toast(when {
                        !ok -> "没有在米家账号里找到可直连的秤"
                        started -> "已开启：手机放在秤附近，站上秤即可自动记录"
                        else -> "已开启，但后台扫描注册失败，请确认蓝牙已打开"
                    })
                    refresh()
                }
            }
        }
    }

    /** 米家蓝牙认证诊断：登录后记录秤推送的加密数据并查询属性。 */
    private fun bleAuthProbe() = withBlePermission {
        bleProbing = true
        toast("请现在站上秤，一直站到读数稳定")
        lifecycleScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val log: (String) -> Unit = { settings.appendLog("${fmt.format(Date())}  $it") }
            runCatching { BleAuthProbe(applicationContext).probe(log) }
                .onFailure { log("米家蓝牙认证诊断失败：${it.javaClass.simpleName} ${it.message}") }
            launch(Dispatchers.Main) {
                bleProbing = false
                logExpanded = true
                toast("认证诊断完成，结果在「详细日志」里")
                refresh()
            }
        }
    }

    /** 蓝牙连接诊断：等秤醒来后连接，记录服务/特征与称重时的通知。 */
    private fun bleGattProbe() = withBlePermission {
        bleProbing = true
        toast("请先确认已强制停止米家 App，然后站上秤")
        lifecycleScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val log: (String) -> Unit = { settings.appendLog("${fmt.format(Date())}  $it") }
            runCatching { BleGattProbe(applicationContext).probe(log) }
                .onFailure { log("蓝牙连接诊断失败：${it.javaClass.simpleName} ${it.message}") }
            launch(Dispatchers.Main) {
                bleProbing = false
                logExpanded = true
                toast("蓝牙连接诊断完成，结果在「详细日志」里")
                refresh()
            }
        }
    }

    /** 蓝牙诊断：监听 60 秒体脂秤广播，结果写进详细日志。 */
    private fun startBleProbe() {
        bleProbing = true
        toast("蓝牙诊断开始：请在 60 秒内站上秤称一次")
        lifecycleScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val log: (String) -> Unit = { settings.appendLog("${fmt.format(Date())}  $it") }
            runCatching { BleScaleProbe(applicationContext).run(60, log) }
                .onFailure { log("蓝牙诊断失败：${it.javaClass.simpleName} ${it.message}") }
            launch(Dispatchers.Main) {
                bleProbing = false
                logExpanded = true
                toast("蓝牙诊断完成，结果在「详细日志」里")
                refresh()
            }
        }
    }

    /** 诊断米家体脂秤：结果写进详细日志，供用户复制反馈。 */
    private fun diagnoseScale() {
        if (diagnosing) return
        diagnosing = true
        toast("正在诊断米家体脂秤…")
        lifecycleScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val log: (String) -> Unit = { settings.appendLog("${fmt.format(Date())}  $it") }
            runCatching { MiHomeProbe(applicationContext).diagnose(log) }
                .onSuccess { model -> if (model != null) settings.scaleModel = model }
                .onFailure { log("诊断失败：${it.javaClass.simpleName} ${it.message}") }
            launch(Dispatchers.Main) {
                diagnosing = false
                logExpanded = true
                toast("诊断完成，结果在「详细日志」里")
                refresh()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** 例如 "0.1.0 (15)"：versionName + versionCode。 */
    private val versionLabel: String by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("")
    }

    private val syncWork by lazy {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(SyncScheduler.UNIQUE_NAMES))
    }

    /** 首次启动：阅读并同意免责声明后才能使用；不同意则退出。 */
    @Composable
    private fun DisclaimerDialog() {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            containerColor = Palette.Paper,
            title = { Text(Legal.DISCLAIMER_TITLE, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Legal.DISCLAIMER.forEach { Text("• $it", fontSize = 13.sp, lineHeight = 19.sp, color = Palette.Ink) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.disclaimerAccepted = true
                    showDisclaimer = false
                }) { Text("同意并继续", color = Palette.Navy, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { finish() }) { Text("不同意，退出", color = Palette.Muted) }
            },
        )
    }

    @Composable
    private fun FooterLink(text: String, onClick: () -> Unit) {
        Text(
            text, Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
            fontSize = 12.sp, color = Palette.Navy,
        )
    }

    @Composable
    private fun Screen() {
        if (showDisclaimer) DisclaimerDialog()
        // 每 3 秒刷新一次显示（同步在后台 Worker 中进行）
        LaunchedEffect(Unit) {
            while (true) { delay(3000); refreshTick++ }
        }
        val tick = refreshTick
        val s = remember(tick) { snapshot() }
        val totalPerms = HealthConnectWriter.PERMISSIONS.size
        val clipboard = LocalClipboardManager.current

        val work by syncWork.collectAsState(initial = emptyList())
        val running = work.filter { it.state == WorkInfo.State.RUNNING }
        val activity = when {
            running.any { SyncScheduler.TAG_FULL in it.tags } -> SyncActivity.RunningFull
            running.isNotEmpty() -> SyncActivity.Running
            work.any { SyncScheduler.TAG_MANUAL in it.tags && it.state == WorkInfo.State.ENQUEUED } -> SyncActivity.Queued
            else -> SyncActivity.Idle
        }
        val busy = activity != SyncActivity.Idle
        // 全量任务会排在当前同步之后执行，所以只在已有全量任务时才禁用该按钮
        val fullPending = work.any {
            SyncScheduler.TAG_FULL in it.tags && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING)
        }

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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题区
            Column(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 4.dp)) {
                Text("MiHealthBridge", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, letterSpacing = (-0.3).sp)
                Spacer(Modifier.height(4.dp))
                Text("小米运动健康 → Health Connect", fontSize = 13.sp, color = Palette.Muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LegendItem(accountStatus, "账号")
                    LegendItem(permStatus, "权限")
                    LegendItem(syncStatus, "同步")
                }
            }

            Section("小米账号", accountStatus) {
                if (s.loggedIn) {
                    StatRow("账号 ID", s.userId)
                    StatRow("状态", if (s.needsRelogin) "登录已过期" else "已登录")
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

            Section("Health Connect", permStatus) {
                when (hcStatus) {
                    HealthConnectClient.SDK_AVAILABLE -> {
                        StatRow("写入权限", "$grantedCount / $totalPerms")
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

            Section("同步", syncStatus) {
                StatRow("上次成功", if (s.lastSuccess > 0) fmt(s.lastSuccess) else "从未")
                StatRow("最近结果", s.lastResult.ifEmpty { "—" })
                activity.label?.let { label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Navy, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 13.sp, color = Palette.Navy)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("立即同步", Modifier.weight(1f), enabled = !busy) {
                        SyncScheduler.syncNow(this@MainActivity)
                        toast("已开始同步")
                    }
                    SecondaryButton("全量重同步", Modifier.weight(1f), enabled = !fullPending) {
                        SyncScheduler.syncNow(this@MainActivity, full = true)
                        toast("已开始全量重同步：回溯 ${s.initialDays} 天")
                    }
                }
                Spacer(Modifier.height(2.dp))
                FieldLabel("同步间隔")
                Segmented(
                    options = listOf(2 to "2 min", 5 to "5 min", 10 to "10 min", 0 to "15 min"),
                    selected = s.fastMinutes,
                ) { m -> SyncScheduler.setFastMode(this@MainActivity, m); refresh() }
                Caption("15 分钟兜底任务始终开启；更短间隔会额外调度单次任务。")
                FieldLabel("全量重同步回溯天数")
                Segmented(
                    options = listOf(1 to "1 d", 7 to "7 d", 30 to "30 d", 90 to "90 d"),
                    selected = s.initialDays,
                ) { d -> settings.initialDays = d; refresh() }
                Caption("全量重同步会重新拉取这段时间的数据，覆盖 Health Connect 中本应用写入的记录。")
                if (!batteryIgnored) {
                    SecondaryButton("关闭电池优化（后台同步更及时）", Modifier.fillMaxWidth()) { requestIgnoreBattery() }
                }
            }

            ScaleSection(s)

            val logLines = s.log.lines().filter { it.isNotBlank() }
            Section("日志") {
                // 模块一：最近一次同步，各类数据各自的结果，默认折叠
                val report = s.report
                ExpandableHeader(
                    title = "最近一次同步",
                    summary = report?.let { fmtShort(it.atMillis / 1000) } ?: "暂无",
                    expanded = reportExpanded,
                    onToggle = { reportExpanded = !reportExpanded },
                )
                if (report != null) Caption(reportSummary(report))
                if (reportExpanded && report != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        report.items.forEach { TypeResultRow(it) }
                    }
                }

                // 模块二：详细日志，默认折叠
                ExpandableHeader(
                    title = "详细日志",
                    summary = if (logLines.isEmpty()) "暂无" else "${logLines.size} 行",
                    expanded = logExpanded,
                    onToggle = { logExpanded = !logExpanded },
                )
                if (logExpanded) {
                    SecondaryButton("复制全部日志", Modifier.fillMaxWidth()) {
                        clipboard.setText(AnnotatedString(s.log))
                        toast("已复制 ${logLines.size} 行日志")
                    }
                    Box(Modifier.fillMaxWidth().background(Palette.Tint, RoundedCornerShape(8.dp)).padding(12.dp)) {
                        Text(
                            logLines.takeLast(60).reversed().joinToString("\n").ifEmpty { "暂无" },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Palette.Ink,
                        )
                    }
                }
            }

            Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 24.dp)) {
                Caption("仅向 Health Connect 写入数据，不读取；小米账号凭据加密保存在本机。")
                Caption("与小米公司及 Google 无关联；数据仅供个人参考，不能用于医疗诊断。")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FooterLink("免责声明") { LegalActivity.disclaimer(this@MainActivity) }
                    Text("·", fontSize = 12.sp, color = Palette.Faint)
                    FooterLink("开源许可") { LegalActivity.licenses(this@MainActivity) }
                }
                Caption("MiHealthBridge v$versionLabel", Palette.Faint)
            }
        }
    }

    /** 体脂秤检测：先申请蓝牙权限（拒绝也继续，只是跳过广播检测）。 */
    private fun detectScale() {
        if (detectPhase != null || bleProbing) return
        val missing = blePermissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startDetect() else {
            pendingAfterPermission = { startDetect() }
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startDetect() {
        detectPhase = "准备中…"
        lifecycleScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val log: (String) -> Unit = { settings.appendLog("${fmt.format(Date())}  [体脂秤检测] $it") }
            val kind = runCatching {
                ScaleSetup.detect(applicationContext, log) { p -> launch(Dispatchers.Main) { detectPhase = p } }
            }.onFailure { log("检测失败：${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            launch(Dispatchers.Main) {
                detectPhase = null
                toast(if (kind != null) "检测完成：${ScaleSetup.kindLabel(kind)}" else "检测失败，详见日志")
                refresh()
            }
        }
    }

    @Composable
    private fun ScaleSection(s: UiState) {
        val kind = s.scaleKind
        val enabled = when (kind) {
            ScaleSetup.Kind.BLE -> s.scaleBle
            ScaleSetup.Kind.CLOUD -> s.scaleEnabled
            else -> false
        }
        val status = when {
            detectPhase != null -> Status.Warn
            kind == ScaleSetup.Kind.BLE || kind == ScaleSetup.Kind.CLOUD ->
                if (!enabled) Status.None
                else if (kind == ScaleSetup.Kind.CLOUD && s.scaleMember.isEmpty() && s.scaleMembers.size > 1) Status.Warn
                else Status.Ok
            kind == ScaleSetup.Kind.UNSUPPORTED || kind == ScaleSetup.Kind.NONE -> Status.Warn
            else -> Status.None
        }
        Section("体脂秤", status) {
            val phase = detectPhase
            if (phase != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Navy, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(phase, fontSize = 13.sp, color = Palette.Navy)
                }
            }
            if (kind.isEmpty()) {
                Caption("自动找到米家账号里的体脂秤，识别型号、检测蓝牙广播，并选择合适的同步方式。检测时请把秤放在手机旁边，按提示踩一下秤。")
                PrimaryButton("检测体脂秤", Modifier.fillMaxWidth(), enabled = phase == null && s.loggedIn) { detectScale() }
                if (!s.loggedIn) Caption("请先登录小米账号。")
            } else {
                if (s.scaleName.isNotEmpty()) StatRow("名称", s.scaleName)
                if (s.scaleModel.isNotEmpty()) StatRow("型号", s.scaleModel)
                StatRow("同步方式", ScaleSetup.kindLabel(kind))
                if (s.scaleBroadcast.isNotEmpty()) StatRow("蓝牙广播", s.scaleBroadcast)

                when (kind) {
                    ScaleSetup.Kind.BLE, ScaleSetup.Kind.CLOUD -> {
                        Segmented(options = listOf(false to "停用", true to "启用"), selected = enabled) { on ->
                            if (kind == ScaleSetup.Kind.BLE) setScaleBle(on)
                            else {
                                settings.scaleEnabled = on
                                if (on) SyncScheduler.syncNow(this@MainActivity, full = true)
                                refresh()
                            }
                        }
                    }
                }
                if (kind == ScaleSetup.Kind.BLE && enabled) {
                    StatRow("最近一次", s.scaleBleResult.ifEmpty { "还没有称重记录" })
                    Caption("称重时手机需在秤附近并开着蓝牙，站到读数稳定即可，无需打开本应用。由本应用读取的称重不会出现在米家 App 里。")
                }
                if (kind == ScaleSetup.Kind.CLOUD && enabled) {
                    Caption("称重记录随「同步」定时拉取，写入体重、体脂率、体内水分、骨量、去脂体重、基础代谢。")
                    if (s.scaleMembers.isNotEmpty()) {
                        FieldLabel("只同步这位成员")
                        Segmented(
                            options = listOf("" to "自动") + s.scaleMembers.map { it to it },
                            selected = s.scaleMember,
                        ) { m ->
                            settings.scaleMember = m
                            SyncScheduler.syncNow(this@MainActivity, full = true)
                            refresh()
                        }
                        if (s.scaleMembers.size > 1 && s.scaleMember.isEmpty()) {
                            Caption("秤上有多位家庭成员，请选择你自己，否则不会写入任何称重数据。", Palette.Red)
                        }
                    }
                }
                if (kind == ScaleSetup.Kind.UNSUPPORTED) Caption("这台秤暂不支持。可展开下方「诊断工具」收集数据后反馈给开发者。", Palette.Red)
                if (kind == ScaleSetup.Kind.NONE) Caption("米家账号里没有找到体脂秤。绑定在小米运动健康的秤，体重会随「同步」自动写入。")
                SecondaryButton("重新检测", Modifier.fillMaxWidth(), enabled = phase == null && s.loggedIn) { detectScale() }
            }

            ExpandableHeader(
                title = "诊断工具",
                summary = "适配新型号时使用",
                expanded = scaleDiagExpanded,
                onToggle = { scaleDiagExpanded = !scaleDiagExpanded },
            )
            if (scaleDiagExpanded) {
                Caption("诊断会主动连接秤，请先在上方停用体脂秤同步，避免抢连接。结果写进「详细日志」。")
                val busy = bleProbing || diagnosing || phase != null
                SecondaryButton(if (diagnosing) "诊断中…" else "云端诊断（查找秤并检查云端数据）", Modifier.fillMaxWidth(), enabled = !busy && s.loggedIn) { diagnoseScale() }
                SecondaryButton("蓝牙广播诊断（60 秒内称一次重）", Modifier.fillMaxWidth(), enabled = !busy && s.loggedIn) { bleProbe() }
                SecondaryButton("蓝牙连接诊断（不认证）", Modifier.fillMaxWidth(), enabled = !busy && s.loggedIn) { bleGattProbe() }
                SecondaryButton("米家蓝牙认证诊断（点后立即站上秤）", Modifier.fillMaxWidth(), enabled = !busy && s.loggedIn) { bleAuthProbe() }
            }
        }
    }

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

    private fun fmtShort(sec: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(sec * 1000))

    /** 例如：已同步 6 · 无新数据 4 · 失败 1 · 范围 11-23 00:00 → 11-24 13:05 */
    private fun reportSummary(r: SyncReport): String {
        val order = listOf(
            SyncReport.State.Synced to "已同步", SyncReport.State.Partial to "部分失败",
            SyncReport.State.NoData to "无新数据", SyncReport.State.Failed to "失败",
            SyncReport.State.Unauthorized to "未授权",
        )
        val counts = order.mapNotNull { (state, name) ->
            r.items.count { it.state == state }.takeIf { it > 0 }?.let { "$name $it" }
        }
        val range = if (r.windowStartSec > 0) listOf("范围 ${fmtShort(r.windowStartSec)} → ${fmtShort(r.windowEndSec)}") else emptyList()
        return (counts + range).joinToString(" · ")
    }

    @Composable
    private fun TypeResultRow(item: SyncReport.TypeResult) {
        val (status, text) = when (item.state) {
            SyncReport.State.Synced -> Status.Ok to "已同步 ${item.written} 条"
            SyncReport.State.Partial -> Status.Warn to "${item.written} 条成功，${item.failed} 条失败"
            SyncReport.State.NoData -> Status.None to "无新数据"
            SyncReport.State.Failed -> Status.Error to "失败"
            SyncReport.State.Unauthorized -> Status.Warn to "未授权"
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(status.color)
                Spacer(Modifier.width(8.dp))
                Text(item.label, Modifier.weight(1f), fontSize = 13.sp, color = Palette.Ink)
                Text(text, fontSize = 13.sp, color = if (status == Status.None) Palette.Muted else Palette.Ink)
            }
            val reason = item.reason
            if (reason != null && item.state != SyncReport.State.NoData && item.state != SyncReport.State.Synced) {
                Text(
                    reason, Modifier.padding(start = 15.dp, top = 2.dp),
                    fontSize = 11.sp, lineHeight = 15.sp,
                    color = if (item.state == SyncReport.State.Failed) Palette.Red else Palette.Muted,
                )
            }
        }
    }
}
