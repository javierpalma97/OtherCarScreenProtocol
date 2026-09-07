"""AXLink v1: cabecera RTP (RFC 3550) + fragmentación FU-A H.264 (RFC 6184)."""
from __future__ import annotations

import struct

RTP_VERSION = 2
VIDEO_PT = 96
AUDIO_PT = 97
VIDEO_CLOCK = 90000
AUDIO_CLOCK = 48000
MTU = 1200  # payload máximo por datagrama (conservador para WiFi)


def pack(seq: int, timestamp: int, ssrc: int, pt: int, marker: bool,
         payload: bytes) -> bytes:
    b0 = RTP_VERSION << 6  # V=2, P/X/CC = 0
    b1 = ((1 if marker else 0) << 7) | (pt & 0x7F)
    return struct.pack(">BBHII", b0, b1, seq & 0xFFFF,
                       timestamp & 0xFFFFFFFF, ssrc & 0xFFFFFFFF) + payload


def unpack(pkt: bytes) -> dict:
    if len(pkt) < 12:
        raise ValueError("rtp packet too short")
    b0, b1, seq, ts, ssrc = struct.unpack(">BBHII", pkt[:12])
    if (b0 >> 6) != RTP_VERSION:
        raise ValueError(f"bad rtp version {b0 >> 6}")
    if b0 & 0x20:
        raise ValueError("padding not supported in v1")
    if b0 & 0x10:
        raise ValueError("extension not supported in v1")
    cc = b0 & 0x0F
    if cc:
        raise ValueError("csrc not supported in v1")
    return {"marker": bool(b1 & 0x80), "pt": b1 & 0x7F,
            "seq": seq, "timestamp": ts, "ssrc": ssrc,
            "payload": pkt[12:]}


def seq_is_newer(a: int, b: int) -> bool:
    """True si `a` es posterior a `b` (aritmética módulo 2^16, RFC 3550 A.1)."""
    return ((a - b) & 0xFFFF) not in (0,) and ((a - b) & 0xFFFF) < 0x8000


class JitterWindow:
    """Ventana de reorden (64) con descarte de duplicados y antiguos."""

    SIZE = 64

    def __init__(self):
        self._seen = set()
        self._max = None

    def accept(self, seq: int) -> bool:
        seq &= 0xFFFF
        if seq in self._seen:
            return False
        if self._max is None:
            self._seen.add(seq)
            self._max = seq
            return True
        if seq_is_newer(seq, self._max):
            self._seen.add(seq)
            self._max = seq
            for old in [s for s in self._seen
                        if ((self._max - s) & 0xFFFF) > self.SIZE]:
                self._seen.discard(old)
            return True
        age = (self._max - seq) & 0xFFFF
        if 0 < age <= self.SIZE:
            self._seen.add(seq)
            return True
        return False


FU_A_TYPE = 28


def fu_a_fragment(nal: bytes, max_payload: int = MTU) -> list:
    """Fragmenta una NAL H.264 en paquetes FU-A. Devuelve lista de payloads."""
    if len(nal) <= max_payload:
        return [nal]
    hdr, data = nal[0], nal[1:]
    nri = hdr & 0x60
    nalu_type = hdr & 0x1F
    out = []
    # Primer fragmento lleva max_payload-2 para que todos quepan igual
    chunk = max_payload - 2
    parts = [data[i:i + chunk] for i in range(0, len(data), chunk)]
    for i, part in enumerate(parts):
        s = 1 if i == 0 else 0
        e = 1 if i == len(parts) - 1 else 0
        out.append(bytes([(FU_A_TYPE | nri), (s << 7) | (e << 6) | nalu_type]) + part)
    return out


def fu_a_reassemble(frags: list) -> bytes:
    """Reensambla fragmentos FU-A (en orden) a la NAL original."""
    if not frags:
        raise ValueError("empty fragments")
    if len(frags) == 1 and (frags[0][0] & 0x1F) != FU_A_TYPE:
        return frags[0]  # NAL única sin fragmentar
    nri = frags[0][0] & 0x60
    nalu_type = frags[0][1] & 0x1F
    if not (frags[0][1] & 0x80):
        raise ValueError("first fragment missing S bit")
    if not (frags[-1][1] & 0x40):
        raise ValueError("last fragment missing E bit")
    return bytes([nri | nalu_type]) + b"".join(f[2:] for f in frags)
