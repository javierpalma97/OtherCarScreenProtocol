"""AXLink v1: framing TCP (JSON por líneas, máx. 64 KiB)."""
from __future__ import annotations

from .messages import MAX_LINE_BYTES, ProtocolError, decode_line, encode


class LineFramer:
    """Buffer incremental: alimenta con bytes, escupe mensajes completos."""

    def __init__(self):
        self._buf = bytearray()

    def feed(self, data: bytes):
        out = []
        self._buf += data
        if len(self._buf) > MAX_LINE_BYTES + 1:
            self._buf.clear()
            raise ProtocolError("too_big")
        while True:
            idx = self._buf.find(b"\n")
            if idx < 0:
                break
            raw = bytes(self._buf[:idx])
            del self._buf[:idx + 1]
            if len(raw) > MAX_LINE_BYTES:
                raise ProtocolError("too_big")
            if raw:
                out.append(decode_line(raw))
        return out
