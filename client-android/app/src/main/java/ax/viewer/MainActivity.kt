package ax.viewer

import android.app.Activity
import android.util.Log
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import axlink.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var video: SurfaceView
    private lateinit var connectPanel: LinearLayout
    private lateinit var sideBar: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectStatus: TextView
    private lateinit var ipField: EditText

    @Volatile private var running = true
    @Volatile private var userDisconnect = false
    @Volatile private var manualIp: String? = null
    @Volatile private var ctrlOut: java.io.OutputStream? = null
    @Volatile private var lastRttMs = -1L
    @Volatile private var videoInfo = ""
    @Volatile private var surfaceReady = false
    private var sessionThread: Thread? = null
    private val TAG = "AXViewer"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        video = findViewById(R.id.video)
        connectPanel = findViewById(R.id.connectPanel)
        sideBar = findViewById(R.id.sideBar)
        status = findViewById(R.id.status)
        connectStatus = findViewById(R.id.connectStatus)
        ipField = findViewById(R.id.ipField)
        findViewById<Button>(R.id.connectBtn).setOnClickListener {
            // Limpia TODO el espacio (el teclado a veces mete espacios/gestos
            // que trim() no quita si van entre caracteres).
            val clean = ipField.text.toString().filter { !it.isWhitespace() }
            ipField.setText(clean)
            startSession(clean.ifEmpty { null })
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener { sendKey("home") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { sendKey("back") }
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { sendKey("volume_up") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { sendKey("volume_down") }
        findViewById<Button>(R.id.btnDisc).setOnClickListener { stopSession() }
        video.setOnTouchListener { v, e -> onVideoTouch(v, e); true }
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) { }
            override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false }
        })
        setStatus("Buscando servidor…", true)
        // Autoconexión por descubrimiento al arrancar
        startSession(null)
    }

    override fun onDestroy() {
        running = false
        stopSession()
        super.onDestroy()
    }

    // Sin botones propios: todo es táctil dentro del vídeo.
    // Atrás del sistema = desconectar y volver al panel.
    @Deprecated("uso directo para API < 33")
    override fun onBackPressed() {
        if (connectPanel.visibility != View.VISIBLE) stopSession()
        else super.onBackPressed()
    }

    // ---------- UI helpers ----------

    private fun setStatus(s: String, connecting: Boolean) = runOnUiThread {
        // La barra lateral no se muestra nunca: solo botones in-video.
        sideBar.visibility = View.GONE
        if (connecting) {
            connectPanel.visibility = View.VISIBLE
            status.visibility = View.GONE
            connectStatus.text = s
        } else {
            connectPanel.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = s
        }
    }

    private fun updateOverlay() {
        val rtt = if (lastRttMs >= 0) "${lastRttMs}ms" else "…"
        setStatus("$videoInfo · $rtt", false)
    }

    // ---------- Session lifecycle ----------

    @Synchronized
    private fun startSession(manual: String?) {
        userDisconnect = false
        if (!manual.isNullOrEmpty()) manualIp = manual
        if (sessionThread?.isAlive == true) return // el bucle recoge manualIp solo
        sessionThread = thread(name = "ax-session", isDaemon = true) {
            sessionLoop()
        }
    }

    private fun stopSession() {
        userDisconnect = true
        try { ctrlOut?.close() } catch (_: Exception) { }
        ctrlOut = null
    }

    private fun sessionLoop() {
        Log.d(TAG, "sessionLoop start")
        while (running && !userDisconnect) {
            try {
                var ip = manualIp
                if (ip.isNullOrEmpty()) {
                    setStatus("Buscando servidor…", true)
                    ip = discover { msg -> runOnUiThread { connectStatus.text = msg } } ?: continue
                } else {
                    setStatus("Conectando a $ip…", true)
                }
                Log.d(TAG, "runSession $ip")
                runSession(ip)
            } catch (e: Exception) {
                Log.d(TAG, "sessionLoop error: $e")
                setStatus("Error: ${e.message}. Reintentando…", true)
            }
            if (running && !userDisconnect) Thread.sleep(2000)
        }
        Log.d(TAG, "sessionLoop end")
        if (userDisconnect) setStatus("Desconectado", true)
    }

    private fun discover(progress: (String) -> Unit): String? {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
        }
        sock.bind(java.net.InetSocketAddress(50000))
        val buf = ByteArray(2048)
        val end = System.currentTimeMillis() + 10000
        try {
            while (running && System.currentTimeMillis() < end) {
                if (!manualIp.isNullOrEmpty()) return manualIp
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val m = AxJson.parse(String(p.data, 0, p.length, Charsets.UTF_8))
                    val b = parseBeacon(m as Map<String, Any?>)
                    return (p.address as InetAddress).hostAddress
                } catch (_: java.net.SocketTimeoutException) { }
            }
        } finally {
            sock.close()
        }
        progress("Sin beacons. Introduce la IP manual.")
        return null
    }

    private fun runSession(ip: String) {
        val sock = Socket(ip, 50001).apply { tcpNoDelay = true; soTimeout = 15000 }
        val out = sock.getOutputStream()
        ctrlOut = out
        val framer = LineFramer()
        fun send(m: AxMessage) = synchronized(out) {
            out.write(encode(m)); out.flush()
        }
        send(AxMessage.Hello("AX-Viewer", listOf("h264", "opus"),
            listOf(Pair(800, 480), Pair(1280, 720))))
        val inp = sock.getInputStream()
        var session: AxMessage.Session? = null
        val tmp = ByteArray(8192)
        while (session == null) {
            val n = inp.read(tmp)
            if (n < 0) throw java.io.EOFException("sin session")
            for (m in framer.feed(tmp.copyOf(n))) {
                if (m is AxMessage.Session) session = m
                else if (m is AxMessage.Pong) lastRttMs = System.currentTimeMillis() - m.t.toLong()
            }
        }
        val sess = session!!
        Log.d(TAG, "session ok ${sess.video.w}x${sess.video.h} id=${sess.id}")
        videoInfo = "${sess.video.w}x${sess.video.h}@${sess.video.fps}"
        updateOverlay()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val vt = thread(name = "ax-video", isDaemon = true) {
            try { videoLoop(ip, sess, stop) } catch (e: Exception) { status("VÍDEO: ${e.message}") }
        }
        val at = thread(name = "ax-audio", isDaemon = true) {
            try { audioLoop(ip, sess, stop) } catch (e: Exception) { status("AUDIO: ${e.message}") }
        }
        val pt = thread(name = "ax-ping", isDaemon = true) {
            try {
                while (!stop.get()) {
                    val t0 = System.currentTimeMillis()
                    try {
                        send(AxMessage.Ping(t0.toDouble()))
                    } catch (e: Exception) {
                        Log.d(TAG, "ping send failed: $e")
                        break
                    }
                    Thread.sleep(2000)
                }
            } catch (_: Exception) { }
        }
        try {
            while (!stop.get()) {
                val n = inp.read(tmp)
                if (n < 0) break
                for (m in framer.feed(tmp.copyOf(n))) {
                    if (m is AxMessage.Pong) {
                        lastRttMs = System.currentTimeMillis() - m.t.toLong()
                        updateOverlay()
                    } else if (m is AxMessage.Ping) {
                        try {
                            send(AxMessage.Pong(m.t))
                        } catch (e: Exception) {
                            Log.d(TAG, "pong send failed: $e")
                        }
                    }
                }
            }
        } finally {
            stop.set(true)
            try { sock.close() } catch (_: Exception) { }
            ctrlOut = null
            vt.join(2000); at.join(2000)
        }
        if (!userDisconnect) throw java.io.EOFException("sesión perdida")
    }

    private fun status(s: String) = setStatus(s, true)

    private fun sendKey(key: String) {
        val out = ctrlOut ?: return
        thread(isDaemon = true) {
            try {
                val msg = encode(AxMessage.Key(key, System.currentTimeMillis().toDouble()))
                synchronized(out) { out.write(msg); out.flush() }
            } catch (_: Exception) { }
        }
    }

    // ---------- Touch ----------

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private fun onVideoTouch(v: View, e: MotionEvent) {
        val out = ctrlOut ?: return
        fun send(m: AxMessage) = thread(isDaemon = true) {
            try {
                val b = encode(m)
                synchronized(out) { out.write(b); out.flush() }
            } catch (_: Exception) { }
        }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dt = System.currentTimeMillis() - downT
                val dx = (e.x - downX) / v.width
                val dy = (e.y - downY) / v.height
                val t = System.currentTimeMillis().toDouble()
                if (dt >= 500 && abs(dx) < 0.02 && abs(dy) < 0.02) {
                    send(AxMessage.Touch("long_press", t,
                        x = (downX / v.width).toDouble(),
                        y = (downY / v.height).toDouble(), ms = dt.toDouble()))
                } else if (abs(dx) > 0.05 || abs(dy) > 0.05) {
                    send(AxMessage.Touch("swipe", t,
                        x0 = (downX / v.width).toDouble(),
                        y0 = (downY / v.height).toDouble(),
                        x1 = (e.x / v.width).toDouble(),
                        y1 = (e.y / v.height).toDouble()))
                } else {
                    send(AxMessage.Touch("tap", t,
                        x = (e.x / v.width).toDouble(),
                        y = (e.y / v.height).toDouble()))
                }
            }
        }
    }

    // ---------- Video ----------

    private fun videoLoop(ip: String, sess: AxMessage.Session, stop: java.util.concurrent.atomic.AtomicBoolean) {
        val sock = DatagramSocket(50002).apply { soTimeout = 1000 }
        val win = JitterWindow()
        var decoder: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var pkts = 0
        var frames = 0
        val fuBufs = HashMap<Int, ArrayList<ByteArray>>()
        val buf = ByteArray(65535)
        Log.d(TAG, "videoLoop start")
        try {
            while (!stop.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val h = try { rtpUnpack(p.data.copyOf(p.length)) } catch (_: Exception) { continue }
                if (h.pt != 96 || !win.accept(h.seq)) continue
                if (++pkts % 150 == 1) Log.d(TAG, "video pkts=$pkts dec=${decoder != null}")
                val nalType = h.payload[0].toInt() and 0x1F
                val units: List<ByteArray> = when (nalType) {
                    28 -> { // FU-A
                        val s = h.payload[1].toInt() and 0x80 != 0
                        val e = h.payload[1].toInt() and 0x40 != 0
                        val key = h.timestamp.toInt()
                        if (s) { fuBufs[key] = ArrayList() }
                        fuBufs[key]?.add(h.payload)
                        if (!e || !h.marker) continue
                        val frags = fuBufs.remove(key) ?: continue
                        listOf(fuAReassemble(frags))
                    }
                    24 -> stapAUnpack(h.payload)
                    else -> listOf(h.payload)
                }
                for (u in units) {
                    when (u[0].toInt() and 0x1F) {
                        7 -> sps = u
                        8 -> pps = u
                    }
                }
                if (decoder == null && sps != null && pps != null) {
                    decoder = startVideoDecoder(sess, sps!!, pps!!)
                    Log.d(TAG, "decoder started")
                }
                val dec = decoder ?: continue
                if (!h.marker) continue // esperar fotograma completo (simple)
                frames++
                if (frames % 30 == 1) Log.d(TAG, "frames=$frames")
                val annexb = ArrayList<Byte>()
                // SPS/PPS en banda antes de cada IDR: algunos decodificadores
                // (goldfish) ignoran el csd si su parseo falla.
                var hasIdr = false
                for (u in units) if ((u[0].toInt() and 0x1F) == 5) { hasIdr = true; break }
                if (hasIdr && sps != null && pps != null) {
                    annexb.addAll(listOf(0, 0, 0, 1))
                    annexb.addAll(sps!!.toList())
                    annexb.addAll(listOf(0, 0, 0, 1))
                    annexb.addAll(pps!!.toList())
                }
                for (u in units) {
                    annexb.addAll(listOf(0, 0, 0, 1))
                    annexb.addAll(u.toList())
                }
                val bytes = annexb.toByteArray()
                val idx = dec.dequeueInputBuffer(10000)
                if (idx >= 0) {
                    dec.getInputBuffer(idx)!!.apply { clear(); put(bytes) }
                    dec.queueInputBuffer(idx, 0, bytes.size, h.timestamp * 1000 / 90, 0)
                }
                drain(dec, false)
            }
        } finally {
            try { sock.close() } catch (_: Exception) { }
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) { }
        }
    }

    private fun startVideoDecoder(sess: AxMessage.Session, sps: ByteArray, pps: ByteArray): MediaCodec {
        var waited = 0
        while (!surfaceReady && waited < 5000) {
            Thread.sleep(100)
            waited += 100
        }
        Log.d(TAG, "csd sps=${sps.size} ${sps.take(8).joinToString("") { "%02x".format(it) }} " +
            "pps=${pps.size} ${pps.take(4).joinToString("") { "%02x".format(it) }}")
        val fmt = MediaFormat.createVideoFormat("video/avc", sess.video.w, sess.video.h)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        val dec = MediaCodec.createDecoderByType("video/avc")
        dec.configure(fmt, video.holder.surface, null, 0)
        dec.start()
        return dec
    }

    private var outFrames = 0
    private var outAudio = 0

    private fun drain(dec: MediaCodec, audio: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = dec.dequeueOutputBuffer(info, 0)
            if (idx >= 0) {
                if (!audio) {
                    dec.releaseOutputBuffer(idx, true)
                    outFrames++
                    if (outFrames % 30 == 1) Log.d(TAG, "rendered=$outFrames size=${info.size}")
                } else {
                    val b = dec.getOutputBuffer(idx)!!
                    val pcm = ByteArray(info.size)
                    b.get(pcm)
                    audioSink?.write(pcm, 0, pcm.size)
                    dec.releaseOutputBuffer(idx, false)
                    if (info.size > 0) {
                        outAudio++
                        if (outAudio % 50 == 1) Log.d(TAG, "pcm_out=$outAudio bytes=${info.size}")
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else break
        }
    }

    // ---------- Audio ----------

    @Volatile private var audioSink: AudioTrack? = null

    private fun audioLoop(ip: String, sess: AxMessage.Session, stop: java.util.concurrent.atomic.AtomicBoolean) {
        val sock = DatagramSocket(50004).apply { soTimeout = 1000 }
        val win = JitterWindow()
        val track = AudioTrack(AudioManager.STREAM_MUSIC, 48000,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            48000 * 2 * 2 / 5, AudioTrack.MODE_STREAM)
        track.play()
        audioSink = track
        // PCM directo (testing): el payload ya es s16be stereo 48 kHz.
        val pcmMode = sess.audio.codec == "pcm"
        val dec: MediaCodec? = if (pcmMode) null else {
            // OpusHead: magic + ver(1) + ch(2) + preskip 312 LE + rate 48000 LE + gain 0 + map 0
            val head = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            head.put("OpusHead".toByteArray())
            head.put(1); head.put(2)
            head.putShort(312)
            head.putInt(48000); head.putShort(0)
            head.put(0)
            val csd = head.array()
            val fmt = MediaFormat.createAudioFormat("audio/opus", 48000, 2)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            MediaCodec.createDecoderByType("audio/opus").apply {
                configure(fmt, null, null, 0)
                start()
            }
        }
        val buf = ByteArray(65535)
        var apkts = 0
        Log.d(TAG, "audioLoop start pcm=$pcmMode")
        try {
            while (!stop.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val h = try { rtpUnpack(p.data.copyOf(p.length)) } catch (_: Exception) { continue }
                val wantPt = if (pcmMode) 98 else 97
                if (h.pt != wantPt || !win.accept(h.seq)) continue
                if (++apkts % 50 == 1) Log.d(TAG, "audio pkts=$apkts")
                if (pcmMode) {
                    track.write(h.payload, 0, h.payload.size)
                    outAudio++
                    if (outAudio % 50 == 1) Log.d(TAG, "pcm_out=$outAudio bytes=${h.payload.size}")
                } else {
                    val idx = dec!!.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        dec.getInputBuffer(idx)!!.apply { clear(); put(h.payload) }
                        dec.queueInputBuffer(idx, 0, h.payload.size, h.timestamp * 1000000L / 48000, 0)
                    }
                    drain(dec, true)
                }
            }
        } finally {
            audioSink = null
            try { sock.close() } catch (_: Exception) { }
            try { track.stop(); track.release() } catch (_: Exception) { }
            try { dec?.stop(); dec?.release() } catch (_: Exception) { }
        }
    }
}
