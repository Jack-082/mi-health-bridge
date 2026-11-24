package io.github.mihealthbridge.sync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mihealthbridge.hc.HealthConnectWriter
import io.github.mihealthbridge.mi.MiBeacon
import io.github.mihealthbridge.mi.MjhScale
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 蓝牙直连体脂秤（目前适配如一 MJH-05）：秤被踩亮 → 系统托管的后台扫描唤醒应用 →
 * 连接并用米家 token 登录 → 读到稳定体重 → 写入 Health Connect。
 */
object ScaleBleSync {

    private const val WORK = "scale_ble_weigh"
    /** 同一次上秤只记录一次：成功后这段时间内的再次唤醒直接忽略。 */
    private const val COOLDOWN_MS = 120_000L

    fun log(context: Context, msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        Settings(context).appendLog("$ts  [蓝牙秤] $msg")
    }

    fun hasPermissions(context: Context): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun scanner(context: Context): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    private fun scanIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) // 扫描结果要写进 extras
        return PendingIntent.getBroadcast(context, 0, Intent(context, ScaleScanReceiver::class.java), flags)
    }

    /** 注册系统托管的后台扫描（只匹配这台秤的 MAC）。应用被杀后系统仍会唤醒。 */
    @SuppressLint("MissingPermission")
    fun startScan(context: Context): Boolean {
        val s = Settings(context)
        val mac = s.scaleMac
        if (!s.scaleBleEnabled || mac.isNullOrEmpty() || !hasPermissions(context)) return false
        val scanner = scanner(context) ?: return false
        runCatching { scanner.stopScan(scanIntent(context)) }
        val code = scanner.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build(),
            scanIntent(context),
        )
        return code == 0
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        if (!hasPermissions(context)) return
        runCatching { scanner(context)?.stopScan(scanIntent(context)) }
    }

    fun onScaleSeen(context: Context) {
        val s = Settings(context)
        if (!s.scaleBleEnabled) return
        if (System.currentTimeMillis() - s.scaleBleLastAt < COOLDOWN_MS) return
        val req = OneTimeWorkRequestBuilder<ScaleWeighWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** 读取 MAC 与 token（缺失时从米家云获取并保存）。 */
    fun ensureCredentials(context: Context, forceRefresh: Boolean = false): Boolean {
        val s = Settings(context)
        if (!forceRefresh && !s.scaleMac.isNullOrEmpty() && s.scaleToken?.length == 24) return true
        val cloud = MiHomeCloud(context)
        val scale = cloud.scales(cloud.listDevices(cloud.login())).firstOrNull { it.token?.length == 24 } ?: return false
        s.scaleMac = scale.mac
        s.scaleToken = scale.token
        return true
    }

    /** 一次完整的上秤：连接、登录、读到稳定体重并写入 Health Connect。 */
    @SuppressLint("MissingPermission")
    suspend fun weighIn(context: Context): String {
        val s = Settings(context)
        if (!ensureCredentials(context)) return "没有找到秤的 MAC 或 token"
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return "蓝牙未开启"
        val device = adapter.getRemoteDevice(s.scaleMac)
        val token = MiBeacon.unhex(s.scaleToken!!)

        val readings = ArrayList<MjhScale.Reading>()
        /** 本次收到的解密后消息，解析失败时写进日志，便于排查协议变化。 */
        val rawMessages = ArrayList<String>()
        fun dumpRaw(reason: String): String {
            log(context, "$reason；本次收到 ${rawMessages.size} 条消息：")
            rawMessages.take(40).forEach { log(context, "  $it") }
            return reason
        }
        val conn = MiBleConnection(context, device)
        try {
            conn.connect()
            try {
                conn.login(token)
            } catch (e: MiBleConnection.BadTokenError) {
                s.scaleToken = null // 下次从云端重新获取
                return "token 校验失败，已清除，下次称重时重新获取"
            }
            runCatching { conn.queryProperties((1..7).map { 2 to it }) }

            val start = System.currentTimeMillis()
            var pendingFirst: ByteArray? = null
            var stableAt = -1
            while (System.currentTimeMillis() - start < 30_000 && !conn.disconnected.isCompleted) {
                val msg = conn.nextMiotMessage(3_000) ?: continue
                rawMessages += MiBeacon.hex(msg)
                when (msg.size) {
                    14 -> pendingFirst = msg
                    8 -> {
                        val first = pendingFirst ?: continue
                        pendingFirst = null
                        val r = MjhScale.decode(first, msg) ?: continue
                        readings += r
                        val settled = MjhScale.settle(readings)
                        if (settled != null && stableAt < 0) stableAt = readings.size
                        // 稳定后：拿到阻抗、或再等 6 帧仍没有阻抗，就结束
                        if (settled != null && (settled.impedance > 0 || readings.size - stableAt >= 6)) break
                    }
                }
            }
        } finally {
            conn.close()
        }

        val result = MjhScale.settle(readings)
            ?: return dumpRaw("没有读到稳定体重（解析出 ${readings.size} 帧）")
        val kg = result.kg ?: return dumpRaw("未知的单位标志 ${result.unit}（原始值 ${result.raw}）")
        s.scaleBleLastAt = System.currentTimeMillis()

        val now = Instant.now()
        val record = WeightRecord(
            time = now,
            zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
            weight = Mass.kilograms(kg),
            metadata = Metadata(
                clientRecordId = "mi_ble_weight_${now.epochSecond}",
                clientRecordVersion = now.toEpochMilli(),
                device = Device(manufacturer = "Wole", model = "MJH-05", type = Device.TYPE_SCALE),
                recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
            ),
        )
        val written = HealthConnectWriter(context).write(listOf(record)) { log(context, it) }.written
        s.appendScaleBleHistory("${now.epochSecond},$kg,${result.raw},${result.unit},${result.impedance}")
        val summary = "%.2f kg（秤显示 %.1f%s）".format(kg, result.raw / 10.0, if (result.unit == 1) " 斤" else " kg") +
            (if (result.impedance > 0) "，阻抗 ${result.impedance} Ω" else "") +
            if (written > 0) "，已写入 Health Connect" else "，写入 Health Connect 失败（请检查体重写入权限）"
        s.scaleBleLastResult = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()) + "  " + summary
        return summary
    }
}

/** 系统托管扫描发现秤时的回调。 */
class ScaleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScaleBleSync.onScaleSeen(context.applicationContext)
    }
}

/** 开机 / 应用更新后重新注册后台扫描。 */
class ScaleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScaleBleSync.startScan(context.applicationContext)
    }
}

class ScaleWeighWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val msg = try {
            ScaleBleSync.weighIn(applicationContext)
        } catch (e: Exception) {
            "失败：${e.javaClass.simpleName} ${e.message}"
        }
        ScaleBleSync.log(applicationContext, msg)
        // 扫描可能在连接期间被系统停掉，结束后重新注册
        ScaleBleSync.startScan(applicationContext)
        return Result.success()
    }

    /** Android 11 及以下加急任务以前台服务运行，需要一条通知。 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "体脂秤同步", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("正在读取体脂秤")
            .setOngoing(true)
            .build()
        return ForegroundInfo(42, n)
    }

    companion object {
        private const val CHANNEL = "scale_ble"
    }
}
