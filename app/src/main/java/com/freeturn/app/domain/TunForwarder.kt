package com.freeturn.app.domain

import android.system.Os
import com.freeturn.app.ProxyServiceState
import java.io.FileDescriptor
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class TunForwarder(
    private val tunFd: FileDescriptor,
    private val socksHost: String,
    private val socksPort: Int
) {
    private val connections = ConcurrentHashMap<ConnKey, TcpConn>()
    private val running = AtomicBoolean(true)
    private var tunThread: Thread? = null

    data class ConnKey(
        val srcIp: Int, val srcPort: Int,
        val dstIp: Int, val dstPort: Int
    ) {
        fun shortName(): String = "${srcPort}:${ipStr(dstIp)}:${dstPort}"
    }

    class TcpConn(
        val socket: Socket,
        val readerThread: Thread,
        val dstIp: Int,
        val dstPort: Int,
        val appIp: Int,
        val appPort: Int,
        @Volatile var appSeq: Long
    )

    fun start() {
        tunThread = Thread { tunLoop() }.apply {
            name = "tun-reader"
            start()
        }
        ProxyServiceState.addLog("tun2socks: forwarder started")
    }

    fun stop() {
        running.set(false)
        tunThread?.interrupt()
        connections.values.forEach { conn ->
            runCatching { conn.socket.close() }
            runCatching { conn.readerThread.interrupt() }
        }
        connections.clear()
    }

    private fun tunLoop() {
        val buf = ByteArray(65535)
        while (running.get()) {
            try {
                val len = Os.read(tunFd, buf, 0, buf.size)
                if (len <= 0) continue
                handlePacket(buf, len)
            } catch (e: Exception) {
                if (running.get()) {
                    ProxyServiceState.addLog("tun: read error: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < 20) return
        val verIhl = buf[0].toInt() and 0xFF
        if ((verIhl shr 4) != 4) return
        val ihl = (verIhl and 0x0F) * 4
        if (ihl < 20 || len < ihl) return
        val protocol = buf[9].toInt() and 0xFF
        if (protocol != 6) return

        val srcIp = readIntBE(buf, 12)
        val dstIp = readIntBE(buf, 16)
        val tcpOff = ihl
        if (len < tcpOff + 20) return
        val srcPort = readShortBE(buf, tcpOff) and 0xFFFF
        val dstPort = readShortBE(buf, tcpOff + 2) and 0xFFFF
        val seq = readIntBE(buf, tcpOff + 4).toLong() and 0xFFFFFFFFL
        val tcpHdrLen = ((buf[tcpOff + 12].toInt() shr 4) and 0x0F) * 4
        if (tcpHdrLen < 20 || len < tcpOff + tcpHdrLen) return
        val flags = buf[tcpOff + 13].toInt() and 0xFF
        val payloadLen = len - tcpOff - tcpHdrLen
        val key = ConnKey(srcIp, srcPort.toInt(), dstIp, dstPort.toInt())

        val syn = (flags and 0x02) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        when {
            syn && !fin -> handleSyn(key, srcIp, srcPort, dstIp, dstPort, seq, buf, tcpOff, tcpHdrLen, len)
            (fin || rst) && !syn -> {
                connections.remove(key)?.let { conn ->
                    runCatching { conn.socket.close() }
                    runCatching { conn.readerThread.interrupt() }
                }
            }
            payloadLen > 0 -> handleData(key, buf, tcpOff, tcpHdrLen, payloadLen, seq)
        }
    }

    private fun handleSyn(
        key: ConnKey, srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int, seq: Long,
        packet: ByteArray, tcpOff: Int, tcpHdrLen: Int, totalLen: Int
    ) {
        connections.remove(key)?.let { old ->
            runCatching { old.socket.close() }
            runCatching { old.readerThread.interrupt() }
        }
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(socksHost, socksPort), 5000)
            val out = socket.getOutputStream()
            val sockIn = socket.getInputStream()

            out.write(byteArrayOf(0x05, 0x01, 0x00))
            readFully(sockIn, ByteArray(2))

            val dstAddr = InetAddress.getByAddress(intToBytesBE(dstIp))
            val req = ByteArray(10).apply {
                this[0] = 0x05; this[1] = 0x01; this[2] = 0x00; this[3] = 0x01
                val addr = dstAddr.address; addr.copyInto(this, 4)
                this[8] = ((dstPort shr 8) and 0xFF).toByte()
                this[9] = (dstPort and 0xFF).toByte()
            }
            out.write(req)
            val resp = ByteArray(10)
            readFully(sockIn, resp)
            if (resp[1] != 0x00.toByte()) {
                socket.close()
                return
            }

            val srvInitSeq = (Random.nextLong() and 0x3FFFFFFFL) + 1L
            val synAck = buildIpTcpPacket(
                dstIp, srcIp, dstPort.toInt(), srcPort.toInt(),
                srvInitSeq, (seq + 1) and 0xFFFFFFFFL,
                0x12, null
            )
            Os.write(tunFd, synAck, 0, synAck.size)

            val rk = key
            val reader = Thread {
                socketReader(rk, socket, sockIn, srvInitSeq)
            }.apply {
                name = "socks-reader-${key.shortName()}"
                isDaemon = true
                start()
            }
            val conn = TcpConn(
                socket = socket, readerThread = reader,
                dstIp = dstIp, dstPort = dstPort.toInt(),
                appIp = srcIp, appPort = srcPort.toInt(),
                appSeq = seq + 1
            )
            connections[key] = conn

            val synPayloadLen = totalLen - tcpOff - tcpHdrLen
            if (synPayloadLen > 0) {
                out.write(packet, tcpOff + tcpHdrLen, synPayloadLen)
                out.flush()
                conn.appSeq = (conn.appSeq + synPayloadLen) and 0xFFFFFFFFL
            }
        } catch (e: Exception) {
            ProxyServiceState.addLog("tun: SOCKS5 connect error: ${e.message}")
        }
    }

    private fun handleData(key: ConnKey, packet: ByteArray, tcpOff: Int, tcpHdrLen: Int, payloadLen: Int, seq: Long) {
        val conn = connections[key] ?: return
        if (seq != conn.appSeq) return
        try {
            conn.socket.getOutputStream().write(packet, tcpOff + tcpHdrLen, payloadLen)
            conn.socket.getOutputStream().flush()
            conn.appSeq = (conn.appSeq + payloadLen) and 0xFFFFFFFFL
        } catch (_: Exception) {
            cleanup(key)
        }
    }

    private fun socketReader(key: ConnKey, socket: Socket, sockIn: InputStream, srvInitSeq: Long) {
        try {
            val buf = ByteArray(16384)
            var srvSeq = srvInitSeq + 1
            while (running.get()) {
                val len = readFullyOrPartial(sockIn, buf)
                if (len <= 0) break
                val conn = connections[key] ?: break
                val pkt = buildIpTcpPacket(
                    conn.dstIp, conn.appIp,
                    conn.dstPort, conn.appPort,
                    srvSeq, conn.appSeq,
                    0x18, buf.copyOfRange(0, len)
                )
                Os.write(tunFd, pkt, 0, pkt.size)
                srvSeq = (srvSeq + len) and 0xFFFFFFFFL
            }
        } catch (_: Exception) {
        } finally {
            cleanup(key)
        }
    }

    private fun cleanup(key: ConnKey) {
        connections.remove(key)?.let { conn ->
            runCatching { conn.socket.close() }
            runCatching { conn.readerThread.interrupt() }
        }
    }

    companion object {
        fun ipStr(ip: Int): String {
            return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
        }

        fun readIntBE(buf: ByteArray, off: Int): Int {
            return ((buf[off].toInt() and 0xFF) shl 24) or
                    ((buf[off + 1].toInt() and 0xFF) shl 16) or
                    ((buf[off + 2].toInt() and 0xFF) shl 8) or
                    (buf[off + 3].toInt() and 0xFF)
        }

        fun readShortBE(buf: ByteArray, off: Int): Int {
            return ((buf[off].toInt() and 0xFF) shl 8) or
                    (buf[off + 1].toInt() and 0xFF)
        }

        fun intToBytesBE(v: Int): ByteArray {
            return byteArrayOf(
                ((v shr 24) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                (v and 0xFF).toByte()
            )
        }

        fun readFully(stream: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = stream.read(buf, off, buf.size - off)
                if (n < 0) throw java.io.EOFException()
                off += n
            }
        }

        fun readFullyOrPartial(stream: InputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = stream.read(buf, off, buf.size - off)
                if (n < 0) return if (off == 0) -1 else off
                off += n
                if (off > 0 && stream.available() == 0) break
            }
            return off
        }

        fun buildIpTcpPacket(
            srcIp: Int, dstIp: Int,
            srcPort: Int, dstPort: Int,
            seq: Long, ack: Long,
            flags: Int, payload: ByteArray?
        ): ByteArray {
            val data = payload ?: ByteArray(0)
            val tcpLen = 20 + data.size
            val totalLen = 20 + tcpLen

            val pkt = ByteArray(totalLen)
            // IP header (20 bytes)
            pkt[0] = 0x45 // version=4, ihl=5
            pkt[1] = 0x00 // DSCP+ECN
            writeShortBE(pkt, 2, totalLen)
            writeShortBE(pkt, 4, 0) // id
            writeShortBE(pkt, 6, 0x4000) // flags=DF, frag=0
            pkt[8] = 64 // TTL
            pkt[9] = 6 // TCP
            writeShortBE(pkt, 10, 0) // checksum placeholder
            writeIntBE(pkt, 12, srcIp)
            writeIntBE(pkt, 16, dstIp)

            // TCP header (20 bytes)
            writeShortBE(pkt, 20, srcPort)
            writeShortBE(pkt, 22, dstPort)
            writeIntBE(pkt, 24, seq.toInt())
            writeIntBE(pkt, 28, ack.toInt())
            writeShortBE(pkt, 32, (0x50 shl 8) or flags) // data_offset=5 + flags
            writeShortBE(pkt, 34, 65535) // window
            writeShortBE(pkt, 36, 0) // checksum placeholder
            writeShortBE(pkt, 38, 0) // urgent ptr

            // TCP payload
            if (data.isNotEmpty()) data.copyInto(pkt, 40, 0, data.size)

            // TCP checksum (with pseudo-header)
            val tcpCksum = computeTcpChecksum(srcIp, dstIp, pkt, 20, tcpLen)
            writeShortBE(pkt, 36, tcpCksum)

            // IP checksum
            val ipCksum = computeIpChecksum(pkt, 0, 20)
            writeShortBE(pkt, 10, ipCksum)

            return pkt
        }

        fun computeIpChecksum(buf: ByteArray, off: Int, len: Int): Int {
            var sum = 0L
            var i = off
            while (i < off + len - 1) {
                sum += readShortBE(buf, i)
                i += 2
            }
            if (i < off + len) sum += (buf[i].toInt() and 0xFF)
            sum = (sum shr 16) + (sum and 0xFFFF)
            sum += (sum shr 16)
            return (sum.toInt() xor 0xFFFF) and 0xFFFF
        }

        fun computeTcpChecksum(srcIp: Int, dstIp: Int, buf: ByteArray, off: Int, tcpLen: Int): Int {
            val totalLen = 12 + tcpLen
            val arr = ByteArray(totalLen)
            writeIntBE(arr, 0, srcIp)
            writeIntBE(arr, 4, dstIp)
            arr[8] = 0
            arr[9] = 6
            writeShortBE(arr, 10, tcpLen)
            buf.copyInto(arr, 12, off, off + tcpLen)
            return computeIpChecksum(arr, 0, totalLen)
        }

        fun writeIntBE(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v shr 24) and 0xFF).toByte()
            buf[off + 1] = ((v shr 16) and 0xFF).toByte()
            buf[off + 2] = ((v shr 8) and 0xFF).toByte()
            buf[off + 3] = (v and 0xFF).toByte()
        }

        fun writeShortBE(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v shr 8) and 0xFF).toByte()
            buf[off + 1] = (v and 0xFF).toByte()
        }
    }
}
