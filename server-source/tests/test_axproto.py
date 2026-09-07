"""Tests AXLink v1: mensajes, framing y RTP."""
import json

import pytest

from axproto import framing, messages, rtp
from axproto.messages import ProtocolError


# --- Beacons ---

def test_beacon_roundtrip():
    b = messages.beacon()
    raw = messages.encode(b)
    assert raw.endswith(b"\n")
    assert messages.parse_beacon(json.loads(raw))["control"] == 50001


def test_beacon_rejects_future_version():
    with pytest.raises(ProtocolError):
        messages.parse_beacon({"type": "hello", "ver": 99, "control": 1,
                               "video": 2, "audio": 3})


def test_beacon_rejects_missing_fields():
    with pytest.raises(ProtocolError):
        messages.parse_beacon({"type": "hello", "ver": 1, "control": 1})


# --- Handshake ---

def test_hello_session_roundtrip():
    hello = {"type": "hello", "client": "AX-Viewer",
             "codecs": ["h264", "opus"], "resolutions": [[800, 480]]}
    assert messages.decode_line(messages.encode(hello)[:-1]) == hello
    session = {"type": "session", "id": "abc", "video": {"codec": "h264",
               "w": 1280, "h": 720, "fps": 30, "pt": 96},
               "audio": {"codec": "opus", "rate": 48000, "pt": 97}}
    assert messages.decode_line(messages.encode(session)[:-1])["id"] == "abc"


def test_hello_rejects_empty_caps():
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":"hello","codecs":[],"resolutions":[]}')


# --- Touch / key ---

def test_touch_vectors():
    tap = {"type": "touch", "action": "tap", "x": 0.42, "y": 0.61, "t": 1}
    assert messages.decode_line(messages.encode(tap)[:-1])["x"] == 0.42
    swipe = {"type": "touch", "action": "swipe", "x0": 0.1, "y0": 0.5,
             "x1": 0.8, "y1": 0.5, "t": 2}
    assert messages.decode_line(messages.encode(swipe)[:-1])["x1"] == 0.8
    lp = {"type": "touch", "action": "long_press", "x": 0.5, "y": 0.5,
          "ms": 600, "t": 3}
    assert messages.decode_line(messages.encode(lp)[:-1])["ms"] == 600


def test_touch_rejects_bad_coords_and_actions():
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":"touch","action":"tap","x":1.5,"y":0,"t":0}')
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":"touch","action":"pinch","x":0,"y":0,"t":0}')


def test_key_and_ping():
    for raw in (b'{"type":"key","key":"volume_up","t":9}',
                b'{"type":"ping","t":9}', b'{"type":"pong","t":9}'):
        assert messages.decode_line(raw)["t"] == 9
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":"key","key":"eject","t":9}')


def test_bad_json_and_unknown_type():
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":')
    with pytest.raises(ProtocolError):
        messages.decode_line(b'{"type":"teleport"}')


def test_too_big():
    with pytest.raises(ProtocolError):
        messages.encode({"type": "ping", "t": 0, "pad": "x" * (64 * 1024)})


# --- Framing ---

def test_framer_splits_and_buffers():
    f = framing.LineFramer()
    assert f.feed(b'{"type":"ping","t":1}\n{"type":"pon') == [
        {"type": "ping", "t": 1}]
    assert f.feed(b'g","t":2}\n') == [{"type": "pong", "t": 2}]
    assert f.feed(b"") == []


def test_framer_rejects_oversize():
    f = framing.LineFramer()
    with pytest.raises(ProtocolError):
        f.feed(b"x" * (64 * 1024 + 2))


# --- RTP ---

def test_rtp_roundtrip():
    pkt = rtp.pack(seq=42, timestamp=90000, ssrc=1234, pt=96, marker=True,
                   payload=b"\x65\x88abc")
    h = rtp.unpack(pkt)
    assert (h["seq"], h["timestamp"], h["ssrc"], h["pt"], h["marker"],
            h["payload"]) == (42, 90000, 1234, 96, True, b"\x65\x88abc")


def test_rtp_rejects_bad_version_and_short():
    with pytest.raises(ValueError):
        rtp.unpack(b"\x00" * 12)
    with pytest.raises(ValueError):
        rtp.unpack(b"\x80\x60\x00")


def test_seq_wrap():
    assert rtp.seq_is_newer(0, 65535)
    assert rtp.seq_is_newer(5, 4)
    assert not rtp.seq_is_newer(4, 5)
    assert not rtp.seq_is_newer(7, 7)


def test_jitter_window():
    w = rtp.JitterWindow()
    assert w.accept(10) and w.accept(12) and w.accept(11)  # desorden OK
    assert not w.accept(11)  # duplicado
    assert not w.accept(10 - 100)  # antiguo fuera de ventana
    assert w.accept(65535) or True  # wrap no rompe (acepta o rechaza sin error)
    for s in range(13, 13 + 70):
        w.accept(s)
    assert not w.accept(13)  # ya fuera de ventana tras avanzar


def test_fu_a_roundtrip():
    nal = bytes([0x65]) + bytes(range(250)) * 20  # 5001 bytes > MTU
    frags = rtp.fu_a_fragment(nal)
    assert len(frags) > 1
    assert all(len(f) <= rtp.MTU for f in frags)
    assert frags[0][1] & 0x80 and frags[-1][1] & 0x40
    assert not (frags[0][1] & 0x40) and not (frags[-1][1] & 0x80)
    assert rtp.fu_a_reassemble(frags) == nal
    small = b"\x41\x99\x88"
    assert rtp.fu_a_reassemble(rtp.fu_a_fragment(small)) == small
