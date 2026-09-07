package axlink

const val RTP_VERSION = 2
const val VIDEO_PT = 96
const val AUDIO_PT = 97
const val VIDEO_CLOCK = 90000
const val AUDIO_CLOCK = 48000
const val MTU = 1200
const val FU_A_TYPE = 28

data class RtpHeader(val marker: Boolean, val pt: Int, val seq: Int,
                     val timestamp: Long, val ssrc: Long, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtpHeader) return false
        return marker == other.marker && pt == other.pt && seq == other.seq &&
            timestamp == other.timestamp && ssrc == other.ssrc && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = seq * 31 + pt
}

fun rtpPack(seq: Int, timestamp: Long, ssrc: Long, pt: Int, marker: Boolean,
            payload: ByteArray): ByteArray {
    val b0 = (RTP_VERSION shl 6).toByte()
    val b1 = (((if (marker) 1 else 0) shl 7) or (pt and 0x7F)).toByte()
    val out = ByteArray(12 + payload.size)
    out[0] = b0; out[1] = b1
    out[2] = ((seq ushr 8) and 0xFF).toByte(); out[3] = (seq and 0xFF).toByte()
    for (k in 0..3) out[4 + k] = ((timestamp ushr (24 - 8 * k)) and 0xFF).toByte()
    for (k in 0..3) out[8 + k] = ((ssrc ushr (24 - 8 * k)) and 0xFF).toByte()
    payload.copyInto(out, 12)
    return out
}

fun rtpUnpack(pkt: ByteArray): RtpHeader {
    if (pkt.size < 12) throw IllegalArgumentException("rtp packet too short")
    val b0 = pkt[0].toInt() and 0xFF
    val b1 = pkt[1].toInt() and 0xFF
    if (b0 ushr 6 != RTP_VERSION) throw IllegalArgumentException("bad rtp version ${b0 ushr 6}")
    if (b0 and 0x20 != 0) throw IllegalArgumentException("padding not supported in v1")
    if (b0 and 0x10 != 0) throw IllegalArgumentException("extension not supported in v1")
    if (b0 and 0x0F != 0) throw IllegalArgumentException("csrc not supported in v1")
    val seq = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
    var ts = 0L; for (k in 0..3) ts = (ts shl 8) or (pkt[4 + k].toInt() and 0xFF).toLong()
    var ssrc = 0L; for (k in 0..3) ssrc = (ssrc shl 8) or (pkt[8 + k].toInt() and 0xFF).toLong()
    return RtpHeader(b1 and 0x80 != 0, b1 and 0x7F, seq, ts, ssrc, pkt.copyOfRange(12, pkt.size))
}

/** True if a is newer than b (mod 2^16, RFC 3550 A.1). */
fun seqIsNewer(a: Int, b: Int): Boolean {
    val d = (a - b) and 0xFFFF
    return d != 0 && d < 0x8000
}

/** Reorder window (64) with duplicate/old discard. */
class JitterWindow {
    companion object { const val SIZE = 64 }
    private val seen = HashSet<Int>()
    private var max: Int? = null

    fun accept(seqIn: Int): Boolean {
        val seq = seqIn and 0xFFFF
        if (!seen.add(seq)) return false
        val m = max
        if (m == null) { max = seq; return true }
        if (seqIsNewer(seq, m)) {
            max = seq
            seen.removeAll { s -> ((seq - s) and 0xFFFF) > SIZE }
            return true
        }
        val age = (m - seq) and 0xFFFF
        if (age in 1..SIZE) return true
        seen.remove(seq)
        return false
    }
}

/** Split an H.264 NAL into FU-A payloads. */
fun fuAFragment(nal: ByteArray, maxPayload: Int = MTU): List<ByteArray> {
    if (nal.size <= maxPayload) return listOf(nal)
    val hdr = nal[0].toInt() and 0xFF
    val nri = hdr and 0x60
    val naluType = hdr and 0x1F
    val data = nal.copyOfRange(1, nal.size)
    val chunk = maxPayload - 2
    val parts = data.toList().chunked(chunk).map { it.toByteArray() }
    return parts.mapIndexed { i, part ->
        val s = if (i == 0) 0x80 else 0
        val e = if (i == parts.size - 1) 0x40 else 0
        byteArrayOf((FU_A_TYPE or nri).toByte(), (s or e or naluType).toByte()) + part
    }
}

/** Split a STAP-A payload into its NAL units (each WITH header byte). */
fun stapAUnpack(payload: ByteArray): List<ByteArray> {
    if (payload.isEmpty() || (payload[0].toInt() and 0x1F) != 24)
        throw IllegalArgumentException("not STAP-A")
    val out = ArrayList<ByteArray>()
    var i = 1
    while (i + 2 <= payload.size) {
        val n = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
        i += 2
        if (i + n > payload.size) throw IllegalArgumentException("STAP-A truncated")
        out.add(payload.copyOfRange(i, i + n))
        i += n
    }
    if (out.isEmpty()) throw IllegalArgumentException("STAP-A empty")
    return out
}

/** Reassemble ordered FU-A fragments into the original NAL. */
fun fuAReassemble(frags: List<ByteArray>): ByteArray {
    if (frags.isEmpty()) throw IllegalArgumentException("empty fragments")
    if (frags.size == 1 && (frags[0][0].toInt() and 0x1F) != FU_A_TYPE) return frags[0]
    val nri = frags[0][0].toInt() and 0x60
    val naluType = frags[0][1].toInt() and 0x1F
    if (frags[0][1].toInt() and 0x80 == 0) throw IllegalArgumentException("first fragment missing S bit")
    if (frags.last()[1].toInt() and 0x40 == 0) throw IllegalArgumentException("last fragment missing E bit")
    var out = byteArrayOf((nri or naluType).toByte())
    frags.forEach { out += it.copyOfRange(2, it.size) }
    return out
}
