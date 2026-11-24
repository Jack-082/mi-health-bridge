package io.github.mihealthbridge.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import io.github.mihealthbridge.mi.Hkdf
import io.github.mihealthbridge.mi.MiBeacon
import io.github.mihealthbridge.mi.MiBle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.UUID

/**
 * 一条米家蓝牙连接：连接 → 订阅 → LOGIN（只登录，不重新绑定）→ 读取 mi.miot.ble 推送。
 * 握手流程移植自 ha-xiaomi-s400（Apache-2.0）。用完调用 [close]。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // 兼容 Android 12 及以下的 GATT 旧接口
class MiBleConnection(private val context: Context, private val device: BluetoothDevice) {

    class BleError(msg: String) : Exception(msg)
    class BadTokenError : Exception("HMAC 校验失败：token 不对或已更换")

    private val queues = HashMap<UUID, Channel<ByteArray>>().apply {
        (MiBle.NOTIFY + MIOT_NOTIFY).forEach { put(it, Channel(Channel.UNLIMITED)) }
    }
    private var pending: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Boolean>()
    val disconnected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var chars: Map<UUID, BluetoothGattCharacteristic> = emptyMap()
    var keys: MiBle.Keys? = null
        private set

    private fun onNotify(uuid: UUID, v: ByteArray) { queues[uuid]?.trySend(v) }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) connected.complete(true)
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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

    private suspend fun op(name: String, start: (BluetoothGatt) -> Boolean): Int {
        val g = gatt ?: throw BleError("未连接")
        val d = CompletableDeferred<Int>().also { pending = it }
        if (!start(g)) throw BleError("$name 未能发起")
        return withTimeoutOrNull(5_000) { d.await() } ?: throw BleError("$name 超时")
    }

    private suspend fun write(uuid: UUID, data: ByteArray) {
        val c = chars[uuid] ?: throw BleError("缺少特征 $uuid")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        op("写入") { g ->
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
            else { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }
    }

    suspend fun recv(uuid: UUID, timeoutMs: Long = 8_000): ByteArray? =
        withTimeoutOrNull(timeoutMs) { queues.getValue(uuid).receiveCatching().getOrNull() }

    private suspend fun recvOrThrow(uuid: UUID): ByteArray =
        recv(uuid) ?: throw BleError(if (disconnected.isCompleted) "秤已断开连接" else "等待应答超时")

    private suspend fun expect(uuid: UUID, want: ByteArray, what: String) {
        val got = recvOrThrow(uuid)
        if (!got.contentEquals(want)) throw BleError("$what：期望 ${MiBeacon.hex(want)}，收到 ${MiBeacon.hex(got)}")
    }

    private suspend fun writeParcel(uuid: UUID, data: ByteArray) {
        for (f in MiBle.parcelFrames(data)) { write(uuid, f); delay(30) }
    }

    private suspend fun recvMulti(uuid: UUID): ByteArray {
        val first = recvOrThrow(uuid)
        if (first.size < 6 || first[0].toInt() != 0 || first[1].toInt() != 0 || first[2].toInt() != 0) {
            throw BleError("意外的首帧 ${MiBeacon.hex(first)}")
        }
        val n = (first[4].toInt() and 0xFF) or ((first[5].toInt() and 0xFF) shl 8)
        write(uuid, MiBle.RCV_RDY)
        var buf = ByteArray(0)
        repeat(n) { buf += recvOrThrow(uuid).let { it.copyOfRange(2, it.size) } }
        write(uuid, MiBle.RCV_OK)
        return buf
    }

    /** 连接、发现服务并订阅全部需要的通知。 */
    suspend fun connect() {
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(15_000) { connected.await() } != true) throw BleError("连接失败")
        runCatching { op("MTU") { it.requestMtu(247) } }
        if (op("发现服务") { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) throw BleError("发现服务失败")
        val g = gatt!!
        chars = g.services.flatMap { it.characteristics }.associateBy { it.uuid }
        for (uuid in MiBle.NOTIFY + MIOT_NOTIFY) {
            val c = chars[uuid] ?: continue
            val cccd = c.getDescriptor(CCCD) ?: continue
            g.setCharacteristicNotification(c, true)
            op("订阅") {
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) it.writeDescriptor(cccd, v) == BluetoothGatt.GATT_SUCCESS
                else { cccd.value = v; it.writeDescriptor(cccd) }
            }
        }
    }

    /** 米家 LOGIN 握手。token 为米家云下发的 12 字节设备 token。 */
    suspend fun login(token: ByteArray) {
        val appRand = ByteArray(16).also { SecureRandom().nextBytes(it) }
        write(MiBle.UPNP, MiBle.CMD_LOGIN)
        write(MiBle.AVDTP, MiBle.CMD_SEND_KEY)
        expect(MiBle.AVDTP, MiBle.RCV_RDY, "发送随机数前")
        writeParcel(MiBle.AVDTP, appRand)
        expect(MiBle.AVDTP, MiBle.RCV_OK, "发送随机数后")
        val devRand = recvMulti(MiBle.AVDTP)
        if (devRand.size != 16) throw BleError("设备随机数长度 ${devRand.size}")
        val remoteInfo = recvMulti(MiBle.AVDTP)
        val k = MiBle.deriveKeys(token, appRand, devRand)
        if (!remoteInfo.contentEquals(Hkdf.hmac(k.devKey, devRand + appRand))) throw BadTokenError()
        write(MiBle.AVDTP, MiBle.CMD_SEND_INFO)
        expect(MiBle.AVDTP, MiBle.RCV_RDY, "发送校验前")
        writeParcel(MiBle.AVDTP, Hkdf.hmac(k.appKey, appRand + devRand))
        expect(MiBle.AVDTP, MiBle.RCV_OK, "发送校验后")
        val result = recvOrThrow(MiBle.UPNP)
        if (!result.contentEquals(MiBle.CFM_LOGIN_OK)) throw BleError("登录被拒绝：${MiBeacon.hex(result)}")
        keys = k
    }

    private var txCounter = 1

    /**
     * 经 0x1A 发送一次加密的 get_properties 查询，并收下 0x1B 上的分包回复（与诊断时的流程一致）。
     * 返回解密后的回复；失败返回 null（不影响后续读数）。
     */
    suspend fun queryProperties(props: List<Pair<Int, Int>>, tid: Int = 100): ByteArray? {
        val k = keys ?: return null
        val frames = MiBle.parcelFrames(MiBle.encrypt(k, txCounter++, MiBle.getProperties(tid, props)))
        val acks = queues.getValue(MiBle.VEND1A)
        while (acks.tryReceive().isSuccess) Unit
        write(MiBle.VEND1A, byteArrayOf(0, 0, 0, 0, frames.size.toByte(), 0))
        expect(MiBle.VEND1A, MiBle.RCV_RDY, "查询 RDY")
        for (f in frames) { write(MiBle.VEND1A, f); delay(20) }
        expect(MiBle.VEND1A, MiBle.RCV_OK, "查询 OK")
        // 回复：0x1B 上的分包
        val head = recv(MiBle.CMTP, 3_000) ?: return null
        if (head.size < 6 || head[0].toInt() != 0 || head[1].toInt() != 0 || head[2].toInt() != 0) return null
        val n = head[4].toInt() and 0xFF
        write(MiBle.CMTP, MiBle.RCV_RDY)
        var buf = ByteArray(0)
        repeat(n) { buf += (recv(MiBle.CMTP, 3_000) ?: return null).let { it.copyOfRange(2, it.size) } }
        write(MiBle.CMTP, MiBle.RCV_OK)
        return MiBle.decrypt(k, buf)
    }

    /** 读取下一条 mi.miot.ble 推送并解密；超时或断开返回 null。 */
    suspend fun nextMiotMessage(timeoutMs: Long): ByteArray? {
        val k = keys ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val raw = recv(MIOT_NOTIFY, left) ?: return null
            MiBle.decrypt(k, raw)?.let { return it }
        }
    }

    suspend fun close() {
        val g = gatt ?: return
        runCatching { g.disconnect() }
        delay(300)
        runCatching { g.close() }
        gatt = null
    }

    companion object {
        /** 小米 MIoT over BLE 服务（UUID 尾部为倒序 ASCII "mi.miot.ble"）。 */
        val MIOT_NOTIFY: UUID = UUID.fromString("00000102-0065-6c62-2e74-6f696d2e696d")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
