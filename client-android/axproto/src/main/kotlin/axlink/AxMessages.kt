package axlink

class AxError(message: String) : IllegalArgumentException(message)

const val MAX_LINE_BYTES = 64 * 1024
const val PROTOCOL_VERSION = 1

val TOUCH_ACTIONS = setOf("tap", "swipe", "long_press")
val KEYS = setOf("volume_up", "volume_down", "home", "back")
val ERROR_CODES = setOf("bad_json", "unknown_type", "bad_coords", "bad_key", "too_big")

sealed class AxMessage {
    data class Beacon(val name: String, val control: Int, val video: Int,
                      val audio: Int, val ver: Int) : AxMessage()
    data class Hello(val client: String, val codecs: List<String>,
                     val resolutions: List<Pair<Int, Int>>) : AxMessage()
    data class Session(val id: String, val video: VideoCaps, val audio: AudioCaps) : AxMessage()
    data class Touch(val action: String, val t: Double, val x: Double? = null,
                     val y: Double? = null, val x0: Double? = null,
                     val y0: Double? = null, val x1: Double? = null,
                     val y1: Double? = null, val ms: Double? = null) : AxMessage()
    data class Key(val key: String, val t: Double) : AxMessage()
    data class Ping(val t: Double) : AxMessage()
    data class Pong(val t: Double) : AxMessage()
    data class Error(val code: String, val detail: String = "") : AxMessage()
}
data class VideoCaps(val codec: String, val w: Int, val h: Int, val fps: Int, val pt: Int)
data class AudioCaps(val codec: String, val rate: Int, val pt: Int)

@Suppress("UNCHECKED_CAST")
private fun map(v: Any?, name: String): Map<String, Any?> =
    v as? Map<String, Any?> ?: throw AxError("$name must be object")

private fun num(v: Any?, name: String): Double {
    if (v is Boolean || v !is Number) throw AxError("$name must be a number")
    return v.toDouble()
}

private fun unit(v: Any?, name: String): Double {
    val d = num(v, name)
    if (d < 0.0 || d > 1.0) throw AxError("$name out of range [0,1]")
    return d
}

/** Wire beacon (UDP): type "hello" + version/ports. */
fun makeBeacon(name: String = "AX-Source", control: Int = 50001,
               video: Int = 50002, audio: Int = 50004): AxMessage.Beacon =
    AxMessage.Beacon(name, control, video, audio, PROTOCOL_VERSION)

fun parseBeacon(m: Map<String, Any?>): AxMessage.Beacon {
    if (m["type"] != "hello" || !m.containsKey("ver")) throw AxError("not a beacon")
    val ver = (m["ver"] as? Number)?.toInt() ?: throw AxError("beacon missing ver")
    if (ver > PROTOCOL_VERSION) throw AxError("unsupported version $ver")
    for (k in listOf("control", "video", "audio"))
        if (!m.containsKey(k)) throw AxError("beacon missing $k")
    return AxMessage.Beacon(m["name"].toString(),
        (m["control"] as Number).toInt(), (m["video"] as Number).toInt(),
        (m["audio"] as Number).toInt(), ver)
}

/** TCP control message (one JSON line, no trailing LF). */
fun decodeLine(raw: ByteArray): AxMessage {
    val text = try { raw.toString(Charsets.UTF_8) }
    catch (e: Exception) { throw AxError("bad_json: $e") }
    val v = try { AxJson.parse(text) }
    catch (e: Exception) { throw AxError("bad_json: ${e.message}") }
    val m = v as? Map<String, Any?> ?: throw AxError("bad_json: top level must be object")
    return validate(m)
}

fun encode(msg: AxMessage): ByteArray {
    val raw = (AxJson.stringify(toMap(msg)) + "\n").toByteArray(Charsets.UTF_8)
    if (raw.size > MAX_LINE_BYTES) throw AxError("too_big")
    return raw
}

fun toMap(m: AxMessage): Map<String, Any?> = when (m) {
    is AxMessage.Beacon -> mapOf("type" to "hello", "name" to m.name,
        "control" to m.control, "video" to m.video, "audio" to m.audio, "ver" to m.ver)
    is AxMessage.Hello -> mapOf("type" to "hello", "client" to m.client,
        "codecs" to m.codecs, "resolutions" to m.resolutions.map { listOf(it.first, it.second) })
    is AxMessage.Session -> mapOf("type" to "session", "id" to m.id,
        "video" to mapOf("codec" to m.video.codec, "w" to m.video.w, "h" to m.video.h,
            "fps" to m.video.fps, "pt" to m.video.pt),
        "audio" to mapOf("codec" to m.audio.codec, "rate" to m.audio.rate, "pt" to m.audio.pt))
    is AxMessage.Touch -> buildMap {
        put("type", "touch"); put("action", m.action); put("t", m.t)
        m.x?.let { put("x", it) }; m.y?.let { put("y", it) }
        m.x0?.let { put("x0", it) }; m.y0?.let { put("y0", it) }
        m.x1?.let { put("x1", it) }; m.y1?.let { put("y1", it) }
        m.ms?.let { put("ms", it) } }
    is AxMessage.Key -> mapOf("type" to "key", "key" to m.key, "t" to m.t)
    is AxMessage.Ping -> mapOf("type" to "ping", "t" to m.t)
    is AxMessage.Pong -> mapOf("type" to "pong", "t" to m.t)
    is AxMessage.Error -> mapOf("type" to "error", "code" to m.code, "detail" to m.detail)
}

fun validate(m: Map<String, Any?>): AxMessage = when (m["type"]) {
    "hello" -> {
        val codecs = m["codecs"] as? List<*> ?: throw AxError("hello needs codecs")
        if (codecs.isEmpty()) throw AxError("hello needs non-empty codecs")
        val res = m["resolutions"] as? List<*> ?: throw AxError("hello needs resolutions")
        if (res.isEmpty()) throw AxError("hello needs non-empty resolutions")
        AxMessage.Hello(m["client"].toString(), codecs.map { it.toString() },
            res.map { val p = it as List<*>; Pair((p[0] as Number).toInt(), (p[1] as Number).toInt()) })
    }
    "session" -> {
        val v = map(m["video"], "video"); val a = map(m["audio"], "audio")
        AxMessage.Session(m["id"].toString(),
            VideoCaps(v["codec"].toString(), (v["w"] as Number).toInt(),
                (v["h"] as Number).toInt(), (v["fps"] as Number).toInt(), (v["pt"] as Number).toInt()),
            AudioCaps(a["codec"].toString(), (a["rate"] as Number).toInt(), (a["pt"] as Number).toInt()))
    }
    "touch" -> {
        val action = m["action"].toString()
        if (action !in TOUCH_ACTIONS) throw AxError("unknown touch action: $action")
        val t = num(m["t"], "t")
        if (action == "swipe") AxMessage.Touch(action, t,
            x0 = unit(m["x0"], "x0"), y0 = unit(m["y0"], "y0"),
            x1 = unit(m["x1"], "x1"), y1 = unit(m["y1"], "y1"))
        else AxMessage.Touch(action, t, x = unit(m["x"], "x"), y = unit(m["y"], "y"),
            ms = if (action == "long_press") num(m["ms"], "ms") else null)
    }
    "key" -> {
        val k = m["key"].toString()
        if (k !in KEYS) throw AxError("bad_key: $k")
        AxMessage.Key(k, num(m["t"], "t"))
    }
    "ping" -> AxMessage.Ping(num(m["t"], "t"))
    "pong" -> AxMessage.Pong(num(m["t"], "t"))
    "error" -> {
        val c = m["code"].toString()
        if (c !in ERROR_CODES) throw AxError("unknown error code")
        AxMessage.Error(c, m["detail"]?.toString() ?: "")
    }
    else -> throw AxError("unknown_type: ${m["type"]}")
}
