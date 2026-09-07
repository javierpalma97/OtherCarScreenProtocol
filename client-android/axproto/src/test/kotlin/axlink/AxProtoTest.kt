package axlink

import org.junit.Assert.*
import org.junit.Test

class AxProtoTest {

    @Test fun beaconRoundtrip() {
        val raw = encode(makeBeacon())
        assertTrue(raw.last() == '\n'.code.toByte())
        val b = parseBeacon(AxJson.parse(String(raw).trim()) as Map<String, Any?>)
        assertEquals(50001, b.control)
        // Vector cruzado con Python: mismo wire, mismo parseo
        val wire = "{\"type\":\"hello\",\"name\":\"AX-Source\",\"control\":50001,\"video\":50002,\"audio\":50004,\"ver\":1}"
        assertEquals(50002, parseBeacon(AxJson.parse(wire) as Map<String, Any?>).video)
    }

    @Test fun beaconRejectsFutureVersion() {
        try {
            parseBeacon(mapOf("type" to "hello", "ver" to 99.0, "control" to 1.0, "video" to 2.0, "audio" to 3.0))
            fail("expected")
        } catch (e: AxError) { }
    }

    @Test fun helloSessionRoundtrip() {
        val h = decodeLine("{\"type\":\"hello\",\"client\":\"AX-Viewer\",\"codecs\":[\"h264\",\"opus\"],\"resolutions\":[[800,480]]}".toByteArray()) as AxMessage.Hello
        assertEquals(listOf("h264", "opus"), h.codecs)
        assertEquals(Pair(800, 480), h.resolutions[0])
        val s = decodeLine("{\"type\":\"session\",\"id\":\"abc\",\"video\":{\"codec\":\"h264\",\"w\":1280,\"h\":720,\"fps\":30,\"pt\":96},\"audio\":{\"codec\":\"opus\",\"rate\":48000,\"pt\":97}}".toByteArray()) as AxMessage.Session
        assertEquals(1280, s.video.w)
        assertEquals(48000, s.audio.rate)
    }

    @Test fun touchVectors() {
        val tap = decodeLine("{\"type\":\"touch\",\"action\":\"tap\",\"x\":0.42,\"y\":0.61,\"t\":1}".toByteArray()) as AxMessage.Touch
        assertEquals(0.42, tap.x!!, 1e-9)
        val sw = decodeLine("{\"type\":\"touch\",\"action\":\"swipe\",\"x0\":0.1,\"y0\":0.5,\"x1\":0.8,\"y1\":0.5,\"t\":2}".toByteArray()) as AxMessage.Touch
        assertEquals(0.8, sw.x1!!, 1e-9)
        val lp = decodeLine("{\"type\":\"touch\",\"action\":\"long_press\",\"x\":0.5,\"y\":0.5,\"ms\":600,\"t\":3}".toByteArray()) as AxMessage.Touch
        assertEquals(600.0, lp.ms!!, 1e-9)
    }

    @Test fun touchRejectsBadCoords() {
        try {
            decodeLine("{\"type\":\"touch\",\"action\":\"tap\",\"x\":1.5,\"y\":0,\"t\":0}".toByteArray())
            fail("expected")
        } catch (e: AxError) { }
    }

    @Test fun keyAndPing() {
        assertEquals("volume_up", (decodeLine("{\"type\":\"key\",\"key\":\"volume_up\",\"t\":9}".toByteArray()) as AxMessage.Key).key)
        assertEquals(9.0, (decodeLine("{\"type\":\"ping\",\"t\":9}".toByteArray()) as AxMessage.Ping).t, 0.0)
        try {
            decodeLine("{\"type\":\"key\",\"key\":\"eject\",\"t\":9}".toByteArray())
            fail("expected")
        } catch (e: AxError) { }
    }

    @Test fun badJsonAndUnknownType() {
        try { decodeLine("{\"type\":".toByteArray()); fail("expected") } catch (e: AxError) { }
        try { decodeLine("{\"type\":\"teleport\"}".toByteArray()); fail("expected") } catch (e: AxError) { }
    }

    @Test fun tooBig() {
        try {
            encode(AxMessage.Ping(0.0)).let { }
            val big = ByteArray(MAX_LINE_BYTES + 2)
            LineFramer().feed(big)
            fail("expected")
        } catch (e: AxError) { }
    }

    @Test fun framerSplitsAndBuffers() {
        val f = LineFramer()
        assertEquals(listOf(AxMessage.Ping(1.0)), f.feed("{\"type\":\"ping\",\"t\":1}\n{\"type\":\"pon".toByteArray()))
        assertEquals(listOf(AxMessage.Pong(2.0)), f.feed("g\",\"t\":2}\n".toByteArray()))
        assertTrue(f.feed(ByteArray(0)).isEmpty())
    }

    @Test fun rtpRoundtrip() {
        val payload = byteArrayOf(0x65.toByte(), 0x88.toByte(), 0x61, 0x62, 0x63)
        val pkt = rtpPack(42, 90000L, 1234L, 96, true, payload)
        val h = rtpUnpack(pkt)
        assertEquals(42, h.seq)
        assertEquals(90000L, h.timestamp)
        assertEquals(1234L, h.ssrc)
        assertEquals(96, h.pt)
        assertTrue(h.marker)
        assertArrayEquals(payload, h.payload)
    }

    @Test fun rtpRejectsBadVersion() {
        try { rtpUnpack(ByteArray(12)); fail("expected") } catch (e: IllegalArgumentException) { }
    }

    @Test fun seqWrap() {
        assertTrue(seqIsNewer(0, 65535))
        assertTrue(seqIsNewer(5, 4))
        assertFalse(seqIsNewer(4, 5))
        assertFalse(seqIsNewer(7, 7))
    }

    @Test fun jitterWindow() {
        val w = JitterWindow()
        assertTrue(w.accept(10) && w.accept(12) && w.accept(11))
        assertFalse(w.accept(11))
        assertFalse(w.accept(10 - 100))
        for (s in 13 until 13 + 70) w.accept(s)
        assertFalse(w.accept(13))
    }

    @Test fun stapARoundtrip() {
        val sps = byteArrayOf(0x67.toByte(), 0x64, 0x00, 0x1F)
        val pps = byteArrayOf(0x68.toByte(), 0xE9.toByte(), 0x7B.toByte())
        val stap = byteArrayOf(24) +
            byteArrayOf((sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
            byteArrayOf((pps.size ushr 8).toByte(), pps.size.toByte()) + pps
        val units = stapAUnpack(stap)
        assertEquals(2, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
    }

    @Test fun fuARoundtrip() {
        val nal = byteArrayOf(0x65.toByte()) + ByteArray(5000) { (it % 250).toByte() }
        val frags = fuAFragment(nal)
        assertTrue(frags.size > 1)
        assertTrue(frags.all { it.size <= MTU })
        assertTrue(frags[0][1].toInt() and 0x80 != 0)
        assertTrue(frags.last()[1].toInt() and 0x40 != 0)
        assertArrayEquals(nal, fuAReassemble(frags))
        val small = byteArrayOf(0x41, 0x99.toByte(), 0x88.toByte())
        assertArrayEquals(small, fuAReassemble(fuAFragment(small)))
    }
}
