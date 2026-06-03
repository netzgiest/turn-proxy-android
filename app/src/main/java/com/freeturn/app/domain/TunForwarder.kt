package com.freeturn.app.domain

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.freeturn.app.ProxyServiceState
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
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
        Thread { testConnectivity() }.apply {
            name = "socks-test"
            isDaemon = true
            start()
        }
    }

    private fun testConnectivity() {
        try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(socksHost, socksPort), 5000)
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val sockIn = socket.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            readFully(sockIn, ByteArray(2))
            val req = ByteArray(10).apply {
                this[0] = 0x05; this[1] = 0x01; this[2] = 0x00; this[3] = 0x01
                val addr = InetAddress.getByName("1.1.1.1").address; addr.copyInto(this, 4)
                this[8] = 0; this[9] = 80
            }
            out.write(req)
            val resp = ByteArray(10)
            readFully(sockIn, resp)
            if (resp[1] == 0x00.toByte()) {
                ProxyServiceState.addLog("tun: SOCKS5 test to 1.1.1.1:80 OK")
            } else {
                ProxyServiceState.addLog("tun: SOCKS5 test FAILED: ${resp[1]}")
            }
            socket.close()
        } catch (e: Exception) {
            ProxyServiceState.addLog("tun: SOCKS5 test error: ${e.message}")
        }
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
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) {
                    // non-blocking fd, no data right now
                    try { Thread.sleep(10) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } else if (running.get()) {
                    ProxyServiceState.addLog("tun: read error: ${e.message}")
                }
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
        val srcIp = readIntBE(buf, 12)
        val dstIp = readIntBE(buf, 16)

        if (protocol == 17) {
            val udpOff = ihl
            if (len < udpOff + 8) return
            val dstPort = readShortBE(buf, udpOff + 2) and 0xFFFF
            if (dstPort == 53) handleDnsViaSocks(buf, len, ihl, srcIp, dstIp)
            return
        }
        if (protocol != 6) return

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
            socket.soTimeout = 10000
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
                ProxyServiceState.addLog("tun: SOCKS5 CONNECT ${ipStr(dstIp)}:${dstPort} rejected: ${resp[1]}")
                socket.close()
                return
            }
            ProxyServiceState.addLog("tun: SYN ${ipStr(srcIp)}:${srcPort} -> ${ipStr(dstIp)}:${dstPort} via SOCKS5")

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

    private fun handleDnsViaSocks(buf: ByteArray, len: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        val udpOff = ihl
        val udpLen = readShortBE(buf, udpOff + 4) and 0xFFFF
        if (udpLen < 8 || len < udpOff + udpLen) return
        val payloadLen = udpLen - 8
        val srcPort = readShortBE(buf, udpOff) and 0xFFFF
        val dstPort = readShortBE(buf, udpOff + 2) and 0xFFFF
        if (payloadLen <= 0) return
        val payload = buf.copyOfRange(udpOff + 8, udpOff + udpLen)
        ProxyServiceState.addLog("dns: fwd ${ipStr(srcIp)}:${srcPort} -> ${ipStr(dstIp)}:${dstPort} via SOCKS5 TCP")
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(socksHost, socksPort), 5000)
            socket.soTimeout = 5000
            socks5Connect(socket, dstIp, dstPort)
            val out = socket.getOutputStream()
            val sockIn = socket.getInputStream()
            writeDnsOverTcp(out, payload)
            val dnsResp = readDnsOverTcp(sockIn) ?: return
            ProxyServiceState.addLog("dns: response ${dnsResp.size}B -> ${ipStr(srcIp)}:${srcPort}")
            val reply = buildIpUdpPacket(dstIp, srcIp, dstPort, srcPort, dnsResp, 0, dnsResp.size)
            Os.write(tunFd, reply, 0, reply.size)
        } catch (e: Exception) {
            ProxyServiceState.addLog("dns: ${e.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun socks5Connect(socket: Socket, dstIp: Int, dstPort: Int) {
        val out = socket.getOutputStream()
        val sockIn = socket.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        readFully(sockIn, ByteArray(2))
        val dstAddr = InetAddress.getByAddress(intToBytesBE(dstIp))
        val req = ByteArray(10).apply {
            this[0] = 0x05; this[1] = 0x01; this[2] = 0x00; this[3] = 0x01
            dstAddr.address.copyInto(this, 4)
            this[8] = ((dstPort shr 8) and 0xFF).toByte()
            this[9] = (dstPort and 0xFF).toByte()
        }
        out.write(req)
        val resp = ByteArray(10)
        readFully(sockIn, resp)
        if (resp[1] != 0x00.toByte()) throw RuntimeException("SOCKS5 connect rejected: ${resp[1]}")
    }

    private fun writeDnsOverTcp(out: OutputStream, dnsMsg: ByteArray) {
        val framed = ByteArray(2 + dnsMsg.size)
        writeShortBE(framed, 0, dnsMsg.size)
        dnsMsg.copyInto(framed, 2)
        out.write(framed)
        out.flush()
    }

    private fun readDnsOverTcp(stream: InputStream): ByteArray? {
        val lenHdr = ByteArray(2)
        readFully(stream, lenHdr)
        val respLen = readShortBE(lenHdr, 0)
        if (respLen <= 0 || respLen > 4096) throw RuntimeException("invalid DNS over TCP length: $respLen")
        val body = ByteArray(respLen)
        readFully(stream, body)
        return body
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

        fun buildIpUdpPacket(
            srcIp: Int, dstIp: Int,
            srcPort: Int, dstPort: Int,
            payload: ByteArray, payloadOff: Int, payloadLen: Int
        ): ByteArray {
            val udpLen = 8 + payloadLen
            val totalLen = 20 + udpLen
            val pkt = ByteArray(totalLen)
            pkt[0] = 0x45
            writeShortBE(pkt, 2, totalLen)
            writeShortBE(pkt, 4, 0)
            writeShortBE(pkt, 6, 0x4000)
            pkt[8] = 64
            pkt[9] = 17
            writeShortBE(pkt, 10, 0)
            writeIntBE(pkt, 12, srcIp)
            writeIntBE(pkt, 16, dstIp)
            writeShortBE(pkt, 20, srcPort)
            writeShortBE(pkt, 22, dstPort)
            writeShortBE(pkt, 24, udpLen)
            writeShortBE(pkt, 26, 0)
            payload.copyInto(pkt, 28, payloadOff, payloadOff + payloadLen)
            val cksum = computeUdpChecksum(srcIp, dstIp, pkt, 20, udpLen)
            writeShortBE(pkt, 26, cksum)
            val ipCksum = computeIpChecksum(pkt, 0, 20)
            writeShortBE(pkt, 10, ipCksum)
            return pkt
        }

        fun computeUdpChecksum(srcIp: Int, dstIp: Int, buf: ByteArray, off: Int, udpLen: Int): Int {
            val totalLen = 12 + udpLen
            val arr = ByteArray(totalLen)
            writeIntBE(arr, 0, srcIp)
            writeIntBE(arr, 4, dstIp)
            arr[8] = 0
            arr[9] = 17
            writeShortBE(arr, 10, udpLen)
            buf.copyInto(arr, 12, off, off + udpLen)
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
