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
import io.github.mihealthbridge.mi.MiBle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.UUID

/**
 * 米家蓝牙认证诊断：用米家云下发的设备 token 做 LOGIN（只登录，不重新绑定），
 * 然后记录秤主动推送的加密数据，并主动查询一批属性，解密后写进日志。
 */
class BleAuthProbe(private val context: Context) {

    private val settings = Settings(context)

    private class AuthError(msg: String) : Exception(msg)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // 兼容 Android 12 及以下的 GATT 旧接口
    suspend fun probe(log: (String) -> Unit) {
        log("—— 米家蓝牙认证诊断开始 ——")

        // 1) 云端：MAC + token
        val cloud = MiHomeProbe(context)
        val scale = cloud.scales(cloud.listDevices(cloud.login())).firstOrNull()
        if (scale == null) { log("米家账号里没有找到体脂秤"); return }
        val token = scale.token?.takeIf { it.length == 24 }?.let { MiBeacon.unhex(it) }
        log("秤：${scale.name} | MAC=${scale.mac} | token=" + (if (token != null) "已获取（12 字节）" else "无（${scale.token?.length ?: 0} 位）"))
        if (token == null) return
        settings.scaleMac = scale.mac

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { log("蓝牙未开启"); return }

        // 2) 等秤醒来
        log("等待秤出现（最多 90 秒）：请现在站上秤，并一直站到读数稳定")
        val found = CompletableDeferred<BluetoothDevice>()
        val scanCb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found.complete(r.device) }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IllegalStateException("扫描失败 $errorCode")) }
        }
        val scanner = adapter.bluetoothLeScanner ?: run { log("无法获取蓝牙扫描器"); return }
        scanner.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(scale.mac).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb,
        )
        val device = try { withTimeoutOrNull(90_000) { found.await() } } finally { runCatching { scanner.stopScan(scanCb) } }
        if (device == null) { log("90 秒内没有发现秤"); return }

        // 3) 连接
        val t0 = System.currentTimeMillis()
        fun ts() = "+%.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
        val queues = HashMap<UUID, Channel<ByteArray>>().apply { MiBle.NOTIFY.forEach { put(it, Channel(Channel.UNLIMITED)) } }
        var pending: CompletableDeferred<Int>? = null
        val connected = CompletableDeferred<Boolean>()
        val disconnected = CompletableDeferred<Unit>()

        fun onNotify(uuid: UUID, v: ByteArray) {
            queues[uuid]?.trySend(v)
        }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) connected.complete(true)
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("  ${ts()} 连接断开 status=$status")
                    connected.complete(false); disconnected.complete(Unit); pending?.complete(-1)
                    queues.values.forEach { it.close() }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status) }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pending?.complete(status) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(status) }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onNotify(c.uuid, value)
            @Deprecated("API < 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) onNotify(c.uuid, c.value ?: ByteArray(0))
            }
        }
        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)

        suspend fun op(name: String, start: () -> Boolean): Int {
            val d = CompletableDeferred<Int>().also { pending = it }
            if (!start()) throw AuthError("$name 未能发起")
            return withTimeoutOrNull(5_000) { d.await() } ?: throw AuthError("$name 超时")
        }
        lateinit var chars: Map<UUID, BluetoothGattCharacteristic>
        suspend fun write(uuid: UUID, data: ByteArray) {
            val c = chars[uuid] ?: throw AuthError("缺少特征 $uuid")
            val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            op("写 ${short(uuid)}") {
                if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
                else { c.writeType = type; c.value = data; gatt.writeCharacteristic(c) }
            }
        }
        suspend fun recv(uuid: UUID, timeoutMs: Long = 8_000): ByteArray =
            withTimeoutOrNull(timeoutMs) { queues.getValue(uuid).receiveCatching().getOrNull() }
                ?: throw AuthError(if (disconnected.isCompleted) "秤已断开连接" else "等待 ${short(uuid)} 超时")
        suspend fun expect(uuid: UUID, want: ByteArray, what: String) {
            val got = recv(uuid)
            if (!got.contentEquals(want)) throw AuthError("$what：期望 ${MiBeacon.hex(want)}，收到 ${MiBeacon.hex(got)}")
        }
        suspend fun writeParcel(uuid: UUID, data: ByteArray) {
            for (f in MiBle.parcelFrames(data)) { write(uuid, f); delay(30) }
        }
        suspend fun recvMulti(uuid: UUID): ByteArray {
            val first = recv(uuid)
            if (first.size < 6 || first[0].toInt() != 0 || first[1].toInt() != 0 || first[2].toInt() != 0) {
                throw AuthError("意外的首帧 ${MiBeacon.hex(first)}")
            }
            val n = (first[4].toInt() and 0xFF) or ((first[5].toInt() and 0xFF) shl 8)
            write(uuid, MiBle.RCV_RDY)
            var buf = ByteArray(0)
            repeat(n) { buf += recv(uuid).let { it.copyOfRange(2, it.size) } }
            write(uuid, MiBle.RCV_OK)
            return buf
        }

        try {
            if (withTimeoutOrNull(15_000) { connected.await() } != true) { log("连接失败"); return }
            log("  ${ts()} 已连接")
            runCatching { op("MTU") { gatt.requestMtu(247) } }
            if (op("发现服务") { gatt.discoverServices() } != BluetoothGatt.GATT_SUCCESS) { log("发现服务失败"); return }
            chars = gatt.getService(MiBle.SERVICE)?.characteristics?.associateBy { it.uuid }
                ?: run { log("秤上没有 FE95 服务"); return }
            for (uuid in MiBle.NOTIFY) {
                val c = chars[uuid] ?: continue
                val cccd = c.getDescriptor(CCCD) ?: continue
                gatt.setCharacteristicNotification(c, true)
                op("订阅 ${short(uuid)}") {
                    val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, v) == BluetoothGatt.GATT_SUCCESS
                    else { cccd.value = v; gatt.writeDescriptor(cccd) }
                }
            }
            log("  ${ts()} 已订阅，开始米家登录")

            // 4) LOGIN 握手
            val appRand = ByteArray(16).also { SecureRandom().nextBytes(it) }
            write(MiBle.UPNP, MiBle.CMD_LOGIN)
            write(MiBle.AVDTP, MiBle.CMD_SEND_KEY)
            expect(MiBle.AVDTP, MiBle.RCV_RDY, "发送随机数前")
            writeParcel(MiBle.AVDTP, appRand)
            expect(MiBle.AVDTP, MiBle.RCV_OK, "发送随机数后")
            val devRand = recvMulti(MiBle.AVDTP)
            if (devRand.size != 16) throw AuthError("设备随机数长度 ${devRand.size}")
            val remoteInfo = recvMulti(MiBle.AVDTP)
            val keys = MiBle.deriveKeys(token, appRand, devRand)
            if (!remoteInfo.contentEquals(io.github.mihealthbridge.mi.Hkdf.hmac(keys.devKey, devRand + appRand))) {
                throw AuthError("HMAC 校验失败：token 不对或已更换")
            }
            write(MiBle.AVDTP, MiBle.CMD_SEND_INFO)
            expect(MiBle.AVDTP, MiBle.RCV_RDY, "发送校验前")
            writeParcel(MiBle.AVDTP, io.github.mihealthbridge.mi.Hkdf.hmac(keys.appKey, appRand + devRand))
            expect(MiBle.AVDTP, MiBle.RCV_OK, "发送校验后")
            val result = recv(MiBle.UPNP)
            if (!result.contentEquals(MiBle.CFM_LOGIN_OK)) throw AuthError("登录被拒绝：${MiBeacon.hex(result)}")
            log("  ${ts()} ✅ 米家登录成功")

            // 5) 加密通道：收包、解密、记录
            var expected = 0
            var buf = ByteArray(0)
            var parcelType = 0
            var received = 0
            suspend fun pump(untilMs: Long) {
                val deadline = System.currentTimeMillis() + untilMs
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0 || disconnected.isCompleted) return
                    val data = withTimeoutOrNull(left) { queues.getValue(MiBle.CMTP).receiveCatching().getOrNull() } ?: return
                    if (data.size >= 6 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[5].toInt() == 0) {
                        expected = data[4].toInt() and 0xFF
                        parcelType = data[3].toInt() and 0xFF
                        buf = ByteArray(0)
                        write(MiBle.CMTP, MiBle.RCV_RDY)
                        continue
                    }
                    if (data.size >= 2 && data[1].toInt() == 0 && expected > 0) {
                        buf += data.copyOfRange(2, data.size)
                        if ((data[0].toInt() and 0xFF) >= expected) {
                            write(MiBle.CMTP, MiBle.RCV_OK)
                            expected = 0
                            val plain = MiBle.decrypt(keys, buf)
                            received++
                            if (plain == null) log("  ${ts()} 收到加密消息但解密失败：${MiBeacon.hex(buf)}")
                            else log("  ${ts()} 明文 ${MiBeacon.hex(plain)}${ascii(plain)}" + (MiBle.describe(plain)?.let { " → $it" } ?: ""))
                        }
                        continue
                    }
                    log("  ${ts()} 0x1B 其他帧 ${MiBeacon.hex(data)}")
                }
            }

            log("监听秤主动推送 25 秒（请保持站在秤上）")
            pump(25_000)

            // 6) 主动查询属性（只读）
            if (!disconnected.isCompleted) {
                val props = buildList {
                    for (p in 1..12) add(2 to p)
                    for (p in 1..3) add(3 to p)
                    for (p in 1..6) add(4 to p)
                    for (p in 1..3) add(5 to p)
                }
                var counter = 1
                for ((i, batch) in props.chunked(6).withIndex()) {
                    if (disconnected.isCompleted) break
                    val msg = MiBle.getProperties(100 + i, batch)
                    val payload = MiBle.encrypt(keys, counter++, msg)
                    val frames = MiBle.parcelFrames(payload)
                    try {
                        // 清掉 0x1A 上残留的应答
                        while (queues.getValue(MiBle.VEND1A).tryReceive().isSuccess) Unit
                        write(MiBle.VEND1A, byteArrayOf(0, 0, 0, parcelType.toByte(), frames.size.toByte(), 0))
                        expect(MiBle.VEND1A, MiBle.RCV_RDY, "查询属性 RDY")
                        for (f in frames) { write(MiBle.VEND1A, f); delay(20) }
                        expect(MiBle.VEND1A, MiBle.RCV_OK, "查询属性 OK")
                        log("  ${ts()} 已查询 " + batch.joinToString(",") { "${it.first}.${it.second}" })
                    } catch (e: AuthError) {
                        log("  ${ts()} 查询失败：${e.message}")
                    }
                    pump(3_000)
                }
                pump(10_000)
            }
            log("共收到加密消息 $received 条")
        } catch (e: AuthError) {
            log("  ${ts()} ❌ ${e.message}")
        } finally {
            runCatching { gatt.disconnect() }
            delay(300)
            runCatching { gatt.close() }
            log("—— 米家蓝牙认证诊断结束：请点「复制全部日志」发给开发者 ——")
        }
    }

    private fun short(u: UUID) = "0x" + u.toString().substring(4, 8)

    private fun ascii(v: ByteArray): String {
        val printable = v.count { it in 0x20..0x7E }
        return if (printable >= 6 && printable * 10 >= v.size * 6) " 文本:\"" + v.map { if (it in 0x20..0x7E) it.toInt().toChar() else '.' }.joinToString("") + "\"" else ""
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
