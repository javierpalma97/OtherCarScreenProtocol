package axlink

/** Incremental TCP buffer: feed bytes, get complete messages. */
class LineFramer {
    private val buf = ArrayDeque<Byte>()

    fun feed(data: ByteArray): List<AxMessage> {
        data.forEach { buf.addLast(it) }
        if (buf.size > MAX_LINE_BYTES + 1) {
            buf.clear()
            throw AxError("too_big")
        }
        val out = ArrayList<AxMessage>()
        while (true) {
            val idx = buf.indexOf('\n'.code.toByte())
            if (idx < 0) break
            val raw = ByteArray(idx) { buf.removeFirst() }
            buf.removeFirst() // LF
            if (raw.size > MAX_LINE_BYTES) throw AxError("too_big")
            if (raw.isNotEmpty()) out.add(decodeLine(raw))
        }
        return out
    }
}
