package io.github.mihealthbridge.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.mihealthbridge.mi.MiBeacon
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙诊断：监听一段时间的蓝牙广播，记录米家体脂秤发出的所有广播内容。
 * 米家加密广播（FE95）用云端取到的广播密钥解密后列出各个对象，供判断体重放在哪里。
 */
class BleScaleProbe(private val context: Context) {

    private val settings = Settings(context)

    @SuppressLint("MissingPermission")
    suspend fun run(seconds: Int, log: (String) -> Unit) {
        log("—— 蓝牙诊断开始（$seconds 秒，请在此期间称一次重）——")

        // 1) 从米家云取秤的 MAC 与广播密钥
        val cloud = MiHomeProbe(context)
        val hs = cloud.login()
        val scale = cloud.scales(cloud.listDevices(hs)).firstOrNull()
        if (scale == null) {
            log("米家账号里没有找到体脂秤")
            return
        }
        val mac = scale.mac
        log("秤：${scale.name} | model=${scale.model} | MAC=${mac.ifEmpty { "未知" }}")
        val keyHex = try {
            cloud.beaconKey(hs, scale.did)
        } catch (e: Exception) {
            log("获取广播密钥失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
        val key = keyHex?.takeIf { it.length == 32 && it.all { c -> c.isLetterOrDigit() } }?.let { MiBeacon.unhex(it) }
        log("广播密钥：" + when {
            keyHex == null -> "无"
            key == null -> "长度 ${keyHex.length / 2} 字节（不是 16 字节，无法用于 v4/v5 解密）"
            else -> "已获取（16 字节）"
        })
        if (key != null) {
            settings.scaleBeaconKey = keyHex
            settings.scaleMac = mac
        }

        // 2) 扫描
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("蓝牙未开启")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { log("无法获取蓝牙扫描器"); return }

        val seenPayloads = ConcurrentHashMap.newKeySet<String>()
        val otherMiBeacons = ConcurrentHashMap<String, String>() // 地址 → productId
        val nearby = ConcurrentHashMap<String, String>() // 地址 → 名称/RSSI
        var scaleLines = 0
        var scaleHits = 0

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) = log("扫描失败：errorCode=$errorCode")

            fun handle(r: ScanResult) {
                val record = r.scanRecord ?: return
                val addr = r.device.address.uppercase()
                val fe95 = record.getServiceData(FE95)
                val frame = fe95?.let { MiBeacon.parse(it, if (addr == mac) mac else null, key) }
                val isScale = addr == mac || (frame?.mac != null && frame.mac == mac)
                if (!isScale) {
                    if (frame != null) otherMiBeacons[addr] = "0x%04X".format(frame.productId)
                    if (r.rssi > -70) nearby[addr] = "${record.deviceName ?: "无名称"} rssi=${r.rssi}"
                    return
                }
                scaleHits++
                val raw = MiBeacon.hex(trim(record.bytes))
                if (!seenPayloads.add(raw) || scaleLines >= 80) return
                scaleLines++
                val sb = StringBuilder("[秤广播] rssi=${r.rssi} name=${record.deviceName ?: "无"} raw=$raw")
                record.serviceUuids?.let { sb.append(" uuids=").append(it.joinToString(",")) }
                record.manufacturerSpecificData?.let { m ->
                    for (i in 0 until m.size()) sb.append(" mfr[0x%04X]=".format(m.keyAt(i))).append(MiBeacon.hex(m.valueAt(i)))
                }
                record.serviceData?.forEach { (u, v) -> if (u != FE95) sb.append(" svc[$u]=").append(MiBeacon.hex(v)) }
                log(sb.toString())
                if (frame != null) {
                    val objs = frame.objects?.joinToString("; ") { (t, v) -> "obj 0x%04X=%s".format(t, MiBeacon.hex(v)) }
                    log("  MiBeacon v${frame.version} pid=0x%04X cnt=%d 加密=%s 对象=%s%s".format(
                        frame.productId, frame.frameCounter, frame.encrypted, objs ?: "—",
                        frame.note?.let { "（$it）" } ?: "",
                    ))
                }
            }
        }

        val settingsLowLatency = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settingsLowLatency, callback)
        try {
            for (left in seconds downTo 1) {
                if (left % 15 == 0 && left != seconds) log("  …还剩 $left 秒，已收到秤广播 $scaleHits 次")
                delay(1000)
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        // 3) 汇总
        log("收到秤的广播 $scaleHits 次，不同内容 ${seenPayloads.size} 种")
        if (scaleHits == 0) {
            log("没有收到 MAC=$mac 的广播。附近信号较强的设备：")
            nearby.entries.take(15).forEach { (a, d) -> log("  $a $d") }
            if (otherMiBeacons.isNotEmpty()) {
                log("附近的米家广播（FE95）：" + otherMiBeacons.entries.take(15).joinToString { "${it.key} pid=${it.value}" })
            }
        }
        log("—— 蓝牙诊断结束：请点「复制全部日志」发给开发者 ——")
    }

    /** scanRecord.bytes 末尾会补 0，按 AD 结构截到最后一个有效段。 */
    private fun trim(bytes: ByteArray): ByteArray {
        var p = 0
        while (p < bytes.size) {
            val len = bytes[p].toInt() and 0xFF
            if (len == 0) break
            p += 1 + len
        }
        return bytes.copyOfRange(0, minOf(p, bytes.size))
    }

    companion object {
        private val FE95: ParcelUuid = ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    }
}
