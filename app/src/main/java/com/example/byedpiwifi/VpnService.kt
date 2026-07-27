package com.example.byedpiwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VpnService : VpnService() {

    companion object {
        private const val TAG = "VpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val connections = ConcurrentHashMap<String, Connection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        startForeground(NOTIFICATION_ID, createNotification())
        running.set(true)
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        running.set(false)
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        try {
            Log.d(TAG, "Starting VPN interface")
            val builder = Builder()
            builder.setSession("ByeDPI WiFi")
            builder.addAddress("10.0.0.2", 32)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addRoute("0.0.0.0", 0)
            builder.setMtu(1500)
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface is null")
                return
            }
            Log.d(TAG, "VPN интерфейс создан успешно")
            scope.launch {
                readPackets()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать VPN: ${e.message}")
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        vpnInterface?.close()
        vpnInterface = null
        connections.values.forEach { it.close() }
        connections.clear()
        stopForeground(true)
    }

    private suspend fun readPackets() {
        val fd = vpnInterface?.fileDescriptor
        if (fd == null) {
            Log.e(TAG, "File descriptor is null, cannot read packets")
            return
        }
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)
        val buffer = ByteBuffer.allocate(65536)
        buffer.order(ByteOrder.BIG_ENDIAN)

        while (running.get()) {
            try {
                buffer.clear()
                val len = inputStream.channel.read(buffer)
                if (len <= 0) {
                    delay(10)
                    continue
                }
                buffer.flip()
                val packetData = ByteArray(len)
                buffer.get(packetData)
                processPacket(packetData, outputStream)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Ошибка чтения: ${e.message}")
            }
        }
        Log.d(TAG, "readPackets finished")
    }

    private fun processPacket(packetData: ByteArray, outputStream: FileOutputStream) {
        if (packetData.size < 20) return
        val buffer = ByteBuffer.wrap(packetData)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val version = (buffer.get(0).toInt() shr 4) and 0x0F
        if (version != 4) return

        val ipHeaderLen = (buffer.get(0).toInt() and 0x0F) * 4
        if (ipHeaderLen < 20) return
        val totalLen = buffer.getShort(2).toInt() and 0xFFFF
        if (totalLen > packetData.size) return

        val protocol = buffer.get(9).toInt() and 0xFF
        val srcIp = buffer.getInt(12)
        val dstIp = buffer.getInt(16)

        when (protocol) {
            6 -> handleTcp(packetData, ipHeaderLen, srcIp, dstIp, outputStream)
            17 -> handleUdp(packetData, ipHeaderLen, srcIp, dstIp, outputStream)
            else -> { /* игнорируем */ }
        }
    }

    private fun handleTcp(packetData: ByteArray, ipHeaderLen: Int, srcIp: Int, dstIp: Int, outputStream: FileOutputStream) {
        val tcpOffset = ipHeaderLen
        if (packetData.size < tcpOffset + 20) return
        val buffer = ByteBuffer.wrap(packetData)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.position(tcpOffset)

        val srcPort = buffer.getShort().toInt() and 0xFFFF
        val dstPort = buffer.getShort().toInt() and 0xFFFF
        val seq = buffer.getInt()
        val ack = buffer.getInt()
        val flagsOffset = buffer.getShort().toInt() and 0xFFFF
        val tcpHeaderLen = ((flagsOffset shr 12) and 0x0F) * 4
        if (tcpHeaderLen < 20) return

        val syn = (flagsOffset and 0x02) != 0
        val ackFlag = (flagsOffset and 0x10) != 0
        val rst = (flagsOffset and 0x04) != 0
        val fin = (flagsOffset and 0x01) != 0

        val dataOffset = tcpOffset + tcpHeaderLen
        val dataLen = totalLen(packetData) - dataOffset

        val key = "$srcIp:$srcPort->$dstIp:$dstPort"
        val reverseKey = "$dstIp:$dstPort->$srcIp:$srcPort"

        if (syn && !ackFlag) {
            val conn = Connection(srcIp, srcPort, dstIp, dstPort, outputStream, seq, ack)
            connections[key] = conn
            conn.connect()
            Log.d(TAG, "Новое TCP-соединение $key")
        } else if (rst || fin) {
            connections.remove(key)?.close()
            connections.remove(reverseKey)?.close()
            Log.d(TAG, "Закрытие соединения $key")
        } else if (dataLen > 0) {
            val conn = connections[key]
            if (conn != null && conn.connected) {
                val data = ByteArray(dataLen)
                buffer.position(dataOffset)
                buffer.get(data)
                conn.sendToServer(data)
            } else {
                Log.d(TAG, "Данные для неизвестного соединения, игнорируем")
            }
        }
    }

    private fun handleUdp(packetData: ByteArray, ipHeaderLen: Int, srcIp: Int, dstIp: Int, outputStream: FileOutputStream) {
        Log.d(TAG, "UDP пакет получен, пропущен")
    }

    private fun totalLen(packet: ByteArray): Int {
        return ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ByeDPI WiFi")
            .setContentText("VPN активен")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ==================== ВНУТРЕННИЙ КЛАСС CONNECTION ====================
    inner class Connection(
        val srcIp: Int, val srcPort: Int,
        val dstIp: Int, val dstPort: Int,
        private val outputStream: FileOutputStream,
        var clientSeq: Int,
        var clientAck: Int
    ) {
        @Volatile var connected = false
        private var socket: Socket? = null
        private var inputStream: java.io.InputStream? = null
        private var outStream: java.io.OutputStream? = null
        private var serverSeq = (System.currentTimeMillis() / 1000).toInt() and 0xFFFF
        private var serverAck = 0
        private var isTls = false

        fun connect() {
            try {
                socket = Socket()
                protect(socket!!)
                socket!!.soTimeout = 30000
                val addr = InetAddress.getByAddress(intToBytes(dstIp))
                socket!!.connect(InetSocketAddress(addr, dstPort), 10000)
                connected = true
                inputStream = socket!!.getInputStream()
                outStream = socket!!.getOutputStream()
                Log.d(TAG, "Подключено к ${addr.hostAddress}:$dstPort")
                scope.launch { readFromServer() }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подключения: ${e.message}")
                close()
            }
        }

        private suspend fun readFromServer() {
            val buffer = ByteArray(65535)
            while (connected && running.get()) {
                try {
                    val len = inputStream?.read(buffer) ?: -1
                    if (len > 0) {
                        val data = buffer.copyOf(len)
                        sendPacketToClient(data)
                    } else break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Ошибка чтения от сервера: ${e.message}")
                    break
                }
            }
            close()
        }

        private fun sendPacketToClient(data: ByteArray) {
            val ipLen = 20
            val tcpLen = 20
            val totalLen = ipLen + tcpLen + data.size
            val packet = ByteBuffer.allocate(totalLen)
            packet.order(ByteOrder.BIG_ENDIAN)

            // IP-заголовок
            packet.put(0x45)
            packet.put(0x00)
            packet.putShort(totalLen.toShort())
            packet.putShort(0x1234.toShort())
            packet.putShort(0x4000.toShort())
            packet.put(64)
            packet.put(6)
            packet.putShort(0)
            packet.putInt(dstIp)
            packet.putInt(srcIp)

            // TCP-заголовок
            packet.putShort(dstPort.toShort())
            packet.putShort(srcPort.toShort())
            packet.putInt(serverSeq)
            packet.putInt(clientAck)
            val flags = 0x10 // ACK
            packet.putShort(((5 shl 12) or flags).toShort())
            packet.putShort(65535)
            packet.putShort(0)
            packet.putShort(0)

            packet.put(data)

            val packetArray = packet.array()

            // IP checksum
            val ipChecksum = calculateChecksum(packetArray, 0, ipLen)
            packetArray[10] = (ipChecksum shr 8).toByte()
            packetArray[11] = (ipChecksum and 0xFF).toByte()

            // TCP pseudo-header checksum
            val pseudoHeader = ByteBuffer.allocate(12 + tcpLen + data.size)
            pseudoHeader.order(ByteOrder.BIG_ENDIAN)
            pseudoHeader.putInt(dstIp)
            pseudoHeader.putInt(srcIp)
            pseudoHeader.put(0)
            pseudoHeader.put(6)
            pseudoHeader.putShort((tcpLen + data.size).toShort())
            pseudoHeader.put(packetArray, ipLen, tcpLen + data.size)
            val tcpChecksum = calculateChecksum(pseudoHeader.array(), 0, pseudoHeader.position())
            val tcpOffset = ipLen + 16
            packetArray[tcpOffset] = (tcpChecksum shr 8).toByte()
            packetArray[tcpOffset + 1] = (tcpChecksum and 0xFF).toByte()

            try {
                outputStream.write(packetArray)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки клиенту: ${e.message}")
            }
        }

        fun sendToServer(data: ByteArray) {
            if (!connected) return
            try {
                if (!isTls && data.isNotEmpty() && data[0].toInt() == 0x16) {
                    isTls = true
                }
                val shouldFragment = isTls || isHttp(data)
                if (shouldFragment) {
                    val chunkSize = 100
                    var offset = 0
                    while (offset < data.size) {
                        val len = minOf(chunkSize, data.size - offset)
                        val chunk = data.copyOfRange(offset, offset + len)
                        outStream?.write(chunk)
                        outStream?.flush()
                        offset += len
                        Thread.sleep(10)
                    }
                    Log.d(TAG, "Отправлено с фрагментацией, размер: ${data.size}")
                } else {
                    outStream?.write(data)
                    outStream?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки на сервер: ${e.message}")
                close()
            }
        }

        private fun isHttp(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val first = data[0].toInt() and 0xFF
            val second = data[1].toInt() and 0xFF
            val third = data[2].toInt() and 0xFF
            val fourth = data[3].toInt() and 0xFF
            return (first == 'G'.code && second == 'E'.code && third == 'T'.code && fourth == ' '.code) ||
                    (first == 'P'.code && second == 'O'.code && third == 'S'.code && fourth == 'T'.code) ||
                    (first == 'H'.code && second == 'E'.code && third == 'A'.code && fourth == 'D'.code) ||
                    (first == 'P'.code && second == 'U'.code && third == 'T'.code) ||
                    (first == 'D'.code && second == 'E'.code && third == 'L'.code && fourth == 'E'.code)
        }

        fun close() {
            try {
                inputStream?.close()
                outStream?.close()
                socket?.close()
            } catch (e: Exception) { /* ignore */ }
            connected = false
            connections.remove("$srcIp:$srcPort->$dstIp:$dstPort")
        }
    }
}
