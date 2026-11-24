package io.github.mihealthbridge.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.mihealthbridge.mi.MiBeacon
import io.github.mihealthbridge.mi.MiCloudClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 体脂秤首次检测：从米家账号找到秤 → 识别型号并选择同步方式 → 扫描它的蓝牙广播 → 应用设置。
 */
object ScaleSetup {

    /** 同步方式。 */
    object Kind {
        const val BLE = "ble"           // 蓝牙直连（称重时手机连接秤读数）
        const val CLOUD = "cloud"       // 云端（米家/运动健康云里有称重记录）
        const val UNSUPPORTED = "unsupported"
        const val NONE = "none"         // 米家账号里没有秤
    }

    /** 已适配蓝牙直连的型号。 */
    private val BLE_MODELS = setOf("wole.scales.mjh05")

    fun kindLabel(kind: String) = when (kind) {
        Kind.BLE -> "蓝牙直连"
        Kind.CLOUD -> "云端同步"
        Kind.UNSUPPORTED -> "暂不支持"
        Kind.NONE -> "未找到秤"
        else -> "未检测"
    }

    /** 当前生效的方式（兼容检测功能上线前的旧设置）。 */
    fun currentKind(s: Settings): String = s.scaleKind.ifEmpty {
        when {
            s.scaleBleEnabled -> Kind.BLE
            s.scaleEnabled && s.scaleModel.isNotEmpty() -> Kind.CLOUD
            else -> ""
        }
    }

    suspend fun detect(context: Context, log: (String) -> Unit, phase: (String) -> Unit): String {
        val s = Settings(context)
        log("—— 体脂秤检测开始 ——")

        // 1) 米家账号里的秤
        phase("正在读取米家设备列表…")
        val cloud = MiHomeProbe(context)
        val hs = cloud.login()
        val scales = cloud.scales(cloud.listDevices(hs))
        scales.forEach { log("米家里的秤：${it.name} | ${it.model} | MAC=${it.mac}") }
        val scale = scales.firstOrNull() ?: run {
            s.scaleKind = Kind.NONE
            log("米家账号里没有体脂秤。绑定在小米运动健康的秤，体重会随定时同步自动写入。")
            return Kind.NONE
        }
        s.scaleName = scale.name
        s.scaleModel = scale.model
        s.scaleMac = scale.mac
        if (scale.token?.length == 24) s.scaleToken = scale.token

        // 2) 选择同步方式
        var kind = Kind.UNSUPPORTED
        if (scale.model in BLE_MODELS && scale.token?.length == 24) {
            kind = Kind.BLE
        } else {
            phase("正在查询云端称重记录…")
            val health = MiCloudClient()
            for (direct in listOf(true, false)) {
                val n = runCatching {
                    if (direct) cloud.fetchScale(hs, scale.model, Long.MAX_VALUE).size
                    else health.fetchScaleData(health.login(s.userId!!, s.passToken!!).also {
                        s.passToken = it.passToken
                    }, scale.model, Long.MAX_VALUE).size
                }.getOrDefault(0)
                log("云端称重记录（${if (direct) "米家云" else "运动健康代理"}）：$n 条")
                if (n > 0) { kind = Kind.CLOUD; s.scaleDirect = direct; break }
            }
        }
        log("同步方式：${kindLabel(kind)}")

        // 3) 蓝牙广播
        if (scale.mac.isNotEmpty() && ScaleBleSync.hasPermissions(context)) {
            phase("请踩一下秤把它唤醒，正在扫描蓝牙广播（最多 30 秒）…")
            val summary = scanBroadcast(context, scale.mac)
            s.scaleBroadcast = summary ?: "30 秒内未收到"
            log("蓝牙广播：${s.scaleBroadcast}")
        } else {
            s.scaleBroadcast = "未检测（缺少蓝牙权限）"
        }

        // 4) 应用
        s.scaleKind = kind
        when (kind) {
            Kind.BLE -> {
                s.scaleEnabled = false
                s.scaleBleEnabled = true
                val ok = ScaleBleSync.startScan(context)
                log(if (ok) "已开启蓝牙直连：站上秤即可自动记录" else "已选择蓝牙直连，但后台扫描注册失败（蓝牙是否已打开？）")
            }
            Kind.CLOUD -> {
                s.scaleBleEnabled = false
                ScaleBleSync.stopScan(context)
                s.scaleEnabled = true
                s.scaleProbeAt = 0L
                SyncScheduler.syncNow(context, full = true)
                log("已开启云端同步：随定时同步拉取称重记录")
            }
            else -> {
                s.scaleEnabled = false
                s.scaleBleEnabled = false
                log("这台秤暂不支持。可展开「诊断工具」收集数据后反馈给开发者。")
            }
        }
        log("—— 体脂秤检测结束 ——")
        return kind
    }

    /** 扫描最多 30 秒，返回广播摘要；没收到返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun scanBroadcast(context: Context, mac: String): String? {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return "蓝牙未开启"
        val found = CompletableDeferred<ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found.complete(r) }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        val r = try { withTimeoutOrNull(30_000) { found.await() } } finally { runCatching { scanner.stopScan(cb) } }
            ?: return null
        val record = r.scanRecord
        val fe95 = record?.getServiceData(ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb"))
        val frame = fe95?.let { MiBeacon.parse(it, mac, null) }
        return buildString {
            append("名称 ${record?.deviceName ?: "无"}，信号 ${r.rssi} dBm")
            if (frame != null) {
                append("，米家广播 v${frame.version} pid=0x%04X".format(frame.productId))
                append(if (frame.hasObject) "（含数据）" else "（仅在线通告，不含体重）")
            } else {
                append("，非米家广播")
            }
        }
    }
}
