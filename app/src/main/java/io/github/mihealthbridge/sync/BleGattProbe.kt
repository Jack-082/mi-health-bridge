package io.github.mihealthbridge.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.github.mihealthbridge.mi.MiBeacon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 蓝牙连接诊断：等秤醒来后连接它，列出全部服务/特征，读取可读特征、订阅全部通知，
 * 把称重过程中收到的原始数据记进日志。只读取和订阅，不向秤写任何指令。
 */
class BleGattProbe(private val context: Context) {

    private val settings = Settings(context)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // 兼容 Android 12 及以下的 GATT 旧接口
    suspend fun run(log: (String) -> Unit) {
        log("—— 蓝牙连接诊断开始 ——")
        val mac = settings.scaleMac ?: run {
            val cloud = MiHomeProbe(context)
            cloud.scales(cloud.listDevices(cloud.login())).firstOrNull()?.mac?.also { settings.scaleMac = it }
        }
        if (mac.isNullOrEmpty()) { log("没有找到秤的 MAC 地址"); return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { log("蓝牙未开启"); return }

        // 1) 等秤醒来（它只在被踩亮时广播）
        log("等待秤 $mac 出现（最多 90 秒）：请现在站上秤")
        val found = CompletableDeferred<BluetoothDevice>()
        val scanCb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found.complete(r.device) }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IllegalStateException("扫描失败 $errorCode")) }
        }
        val scanner = adapter.bluetoothLeScanner ?: run { log("无法获取蓝牙扫描器"); return }
        scanner.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb,
        )
        val device = try {
            withTimeoutOrNull(90_000) { found.await() }
        } finally {
            runCatching { scanner.stopScan(scanCb) }
        }
        if (device == null) { log("90 秒内没有发现秤的广播"); return }
        log("发现秤，开始连接")

        // 2) 连接并按顺序执行 GATT 操作
        val t0 = System.currentTimeMillis()
        fun ts() = "+%.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
        var pending: CompletableDeferred<Int>? = null
        val connected = CompletableDeferred<Boolean>()
        val disconnected = CompletableDeferred<Unit>()
        var notifyLines = 0

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                log("  ${ts()} 连接状态 status=$status state=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) connected.complete(true)
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected.complete(false)
                    disconnected.complete(Unit)
                    pending?.complete(-1)
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status) }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                log("  ${ts()} MTU=$mtu")
                pending?.complete(status)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                log("  ${ts()} 读 ${short(c.uuid)} status=$status = ${MiBeacon.hex(value)}${ascii(value)}")
                pending?.complete(status)
            }
            @Deprecated("API < 33")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT >= 33) return
                val v = c.value ?: ByteArray(0)
                log("  ${ts()} 读 ${short(c.uuid)} status=$status = ${MiBeacon.hex(v)}${ascii(v)}")
                pending?.complete(status)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (notifyLines++ < 200) log("  ${ts()} 通知 ${short(c.uuid)} = ${MiBeacon.hex(value)}${ascii(value)}")
            }
            @Deprecated("API < 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                val v = c.value ?: ByteArray(0)
                if (notifyLines++ < 200) log("  ${ts()} 通知 ${short(c.uuid)} = ${MiBeacon.hex(v)}${ascii(v)}")
            }
        }

        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        try {
            if (withTimeoutOrNull(15_000) { connected.await() } != true) {
                log("连接失败（秤可能已被米家 App 连上，请先强制停止米家 App）")
                return
            }
            suspend fun op(name: String, start: () -> Boolean): Int? {
                val d = CompletableDeferred<Int>().also { pending = it }
                if (!start()) { log("  ${ts()} $name 未能发起"); return null }
                val r = withTimeoutOrNull(5_000) { d.await() }
                if (r == null) log("  ${ts()} $name 超时")
                return r
            }
            op("请求 MTU") { gatt.requestMtu(247) }
            if (op("发现服务") { gatt.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
                log("发现服务失败")
                return
            }

            // 3) 列出全部服务与特征
            val chars = ArrayList<BluetoothGattCharacteristic>()
            for (svc in gatt.services) {
                log("  服务 ${short(svc.uuid)}")
                for (c in svc.characteristics) {
                    chars += c
                    log("    特征 ${short(c.uuid)} 属性=${props(c.properties)}")
                }
            }

            // 4) 读取可读特征（米家认证服务 FE95 下的跳过）
            for (c in chars) {
                if (c.service.uuid == FE95_SERVICE) continue
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) continue
                op("读 ${short(c.uuid)}") { gatt.readCharacteristic(c) }
            }

            // 5) 订阅全部通知 / 指示
            for (c in chars) {
                val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (!notify && !indicate) continue
                val cccd = c.getDescriptor(CCCD) ?: continue
                gatt.setCharacteristicNotification(c, true)
                val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val r = op("订阅 ${short(c.uuid)}") {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        cccd.value = value
                        gatt.writeDescriptor(cccd)
                    }
                }
                log("  ${ts()} 订阅 ${short(c.uuid)}：${if (r == BluetoothGatt.GATT_SUCCESS) "成功" else "失败 $r"}")
            }

            // 6) 保持连接，记录称重过程中的通知
            log("已订阅，保持连接 60 秒：请继续站在秤上直到读数稳定，再下秤")
            withTimeoutOrNull(60_000) { disconnected.await() }?.let { log("  ${ts()} 秤主动断开了连接") }
            log("共收到通知 $notifyLines 条")
        } finally {
            runCatching { gatt.disconnect() }
            delay(300)
            runCatching { gatt.close() }
            log("—— 蓝牙连接诊断结束：请点「复制全部日志」发给开发者 ——")
        }
    }

    private fun short(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) "0x" + s.substring(4, 8) else s
    }

    private fun props(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("读")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("写")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("无响应写")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("通知")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("指示")
    }.joinToString("/")

    /** 若内容是可打印文本，附上文本形式。 */
    private fun ascii(v: ByteArray): String =
        if (v.isNotEmpty() && v.all { it in 0x20..0x7E }) " \"${String(v, Charsets.US_ASCII)}\"" else ""

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val FE95_SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    }
}
