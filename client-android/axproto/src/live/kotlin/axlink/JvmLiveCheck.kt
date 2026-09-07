package axlink

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/** Integración JVM: usa el axlink real del cliente contra el servidor real. */
fun main() {
    var fails = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        println((if (cond) "PASS " else "FAIL ") + name + " " + detail)
        if (!cond) fails++
    }

    // 1. Beacon
    val u = DatagramSocket(null).apply { reuseAddress = true }
    u.bind(java.net.InetSocketAddress(50000))
    u.soTimeout = 5000
    val bbuf = ByteArray(2048)
    val bp = DatagramPacket(bbuf, bbuf.size)
    u.receive(bp)
    val beacon = parseBeacon(AxJson.parse(String(bp.data, 0, bp.length)) as Map<String, Any?>)
    check("beacon", beacon.ver == 1 && beacon.control == 50001)
    u.close()

    // 2. Sesión
    val sock = Socket("127.0.0.1", 50001).apply { tcpNoDelay = true; soTimeout = 10000 }
    val out = sock.getOutputStream()
    val fr = LineFramer()
    fun send(m: AxMessage) { val b = encode(m); synchronized(out) { out.write(b); out.flush() } }
    send(AxMessage.Hello("JVM-Live", listOf("h264", "opus"),
        listOf(Pair(800, 480), Pair(1280, 720))))
    val inp = sock.getInputStream()
    val tmp = ByteArray(8192)
    var sess: AxMessage.Session? = null
    while (sess == null) {
        val n = inp.read(tmp)
        if (n < 0) throw RuntimeException("sin session")
        for (m in fr.feed(tmp.copyOf(n))) if (m is AxMessage.Session) sess = m
    }
    check("session", sess!!.video.w == 800 && sess.audio.codec == "opus")

    // 3. RTP 5 s con el pipeline real del cliente
    val winV = JitterWindow()
    val winA = JitterWindow()
    var vPkts = java.util.concurrent.atomic.AtomicInteger(0)
    var aPkts = java.util.concurrent.atomic.AtomicInteger(0)
    var frames = java.util.concurrent.atomic.AtomicInteger(0)
    var nals = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    val fuBufs = HashMap<Int, ArrayList<ByteArray>>()
    val sv = DatagramSocket(null).apply {
        reuseAddress = true
        receiveBufferSize = 2 * 1024 * 1024
    }
    sv.bind(java.net.InetSocketAddress(50002)); sv.soTimeout = 5000
    val sa = DatagramSocket(null).apply {
        reuseAddress = true
        receiveBufferSize = 2 * 1024 * 1024
    }
    sa.bind(java.net.InetSocketAddress(50004)); sa.soTimeout = 5000
    val end = System.currentTimeMillis() + 5000
    val vb = ByteArray(65535)
    val ab = ByteArray(65535)
    send(AxMessage.Touch("tap", 1.0, x = 0.42, y = 0.61))
    send(AxMessage.Ping(999.0))
    var pongOk = false
    val t0 = System.currentTimeMillis()
    val vt = Thread {
        while (System.currentTimeMillis() < end) {
            try {
                val p = DatagramPacket(vb.clone(), vb.size)
                sv.receive(p)
                val h = rtpUnpack(p.data.copyOf(p.length))
                if (h.pt != 96 || !winV.accept(h.seq)) continue
                vPkts.incrementAndGet()
                val t = h.payload[0].toInt() and 0x1F
                val units: List<ByteArray> = when (t) {
                    28 -> {
                        val s = h.payload[1].toInt() and 0x80 != 0
                        val k = h.timestamp.toInt()
                        if (s) fuBufs[k] = ArrayList()
                        val acc = fuBufs[k]
                        if (acc == null) emptyList()
                        else {
                            acc.add(h.payload)
                            if (h.marker) listOf(fuAReassemble(fuBufs.remove(k)!!)) else emptyList()
                        }
                    }
                    24 -> stapAUnpack(h.payload)
                    else -> listOf(h.payload)
                }
                units.forEach { nals.add(it[0].toInt() and 0x1F) }
                if (h.marker && units.isNotEmpty()) frames.incrementAndGet()
            } catch (_: java.net.SocketTimeoutException) { }
        }
    }
    val at = Thread {
        while (System.currentTimeMillis() < end) {
            try {
                val p = DatagramPacket(ab.clone(), ab.size)
                sa.receive(p)
                val h = rtpUnpack(p.data.copyOf(p.length))
                if (h.pt == 97 && winA.accept(h.seq)) aPkts.incrementAndGet()
            } catch (_: java.net.SocketTimeoutException) { }
        }
    }
    vt.isDaemon = true; at.isDaemon = true
    vt.start(); at.start()
    while (System.currentTimeMillis() < end) {
        try {
            while (inp.available() > 0) {
                val n = inp.read(tmp)
                if (n < 0) break
                for (m in fr.feed(tmp.copyOf(n)))
                    if (m is AxMessage.Pong && m.t == 999.0) pongOk = true
            }
        } catch (_: Exception) { }
        Thread.sleep(50)
    }
    vt.join(6000); at.join(6000)
    val dt = (System.currentTimeMillis() - t0) / 1000.0
    check("rtp-video", vPkts.get() > 100, "${vPkts.get()} pkts")
    check("frames", frames.get() > 100, "${frames.get()} marcos NAL=$nals")
    check("h264-slices", nals.intersect(setOf(1, 5)).isNotEmpty(), "$nals")
    check("rtp-audio", aPkts.get() > 100, "${aPkts.get()} pkts = ${"%.0f".format(aPkts.get() / dt)}/s")
    check("pong", pongOk)
    sv.close(); sa.close(); sock.close()
    println(if (fails == 0) "JVM_LIVE: OK" else "JVM_LIVE: FALLOS $fails")
    if (fails > 0) kotlin.system.exitProcess(1)
}
