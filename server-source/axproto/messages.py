"""AXLink v1: construcción y validación de mensajes de control (JSON)."""
from __future__ import annotations

import json

MAX_LINE_BYTES = 64 * 1024

TOUCH_ACTIONS = ("tap", "swipe", "long_press")
KEYS = ("volume_up", "volume_down", "home", "back")
ERROR_CODES = ("bad_json", "unknown_type", "bad_coords", "bad_key", "too_big")

PROTOCOL_VERSION = 1


class ProtocolError(ValueError):
    pass


def _num(v, name):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ProtocolError(f"{name} must be a number")
    return v


def _unit(v, name):
    v = _num(v, name)
    if not 0.0 <= v <= 1.0:
        raise ProtocolError(f"{name} out of range [0,1]")
    return float(v)


def encode(msg: dict) -> bytes:
    """Serializa un mensaje a una línea JSON + LF (lanza si > 64 KiB)."""
    raw = (json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8")
    if len(raw) > MAX_LINE_BYTES:
        raise ProtocolError("too_big")
    return raw


def decode_line(raw: bytes) -> dict:
    """Parsea una línea (sin el \\n) y valida según su `type`."""
    try:
        msg = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ProtocolError(f"bad_json: {e}")
    if not isinstance(msg, dict):
        raise ProtocolError("bad_json: top level must be object")
    return validate(msg)


def validate(msg: dict) -> dict:
    t = msg.get("type")
    if t == "hello":
        # En TCP es el hello de control; en UDP el beacon se valida
        # con parse_beacon() (mismo "hello" en el wire, distinto contexto).
        return _hello(msg)
    if t == "session":
        return _session(msg)
    if t == "touch":
        return _touch(msg)
    if t == "key":
        return _key(msg)
    if t == "ping" or t == "pong":
        _num(msg.get("t"), "t")
        return {"type": t, "t": msg["t"]}
    if t == "error":
        if msg.get("code") not in ERROR_CODES:
            raise ProtocolError("unknown error code")
        return {"type": "error", "code": msg["code"], "detail": str(msg.get("detail", ""))}
    # El beacon de descubrimiento usa type "hello" igual que el de control;
    # se distinguen por contexto (UDP vs TCP). Aquí "hello" es el de control.
    raise ProtocolError(f"unknown_type: {t}")


def beacon(name="AX-Source", control=50001, video=50002, audio=50004) -> dict:
    """Beacon de descubrimiento (en el wire viaja con type="hello")."""
    return {"type": "hello", "name": name, "control": control,
            "video": video, "audio": audio, "ver": PROTOCOL_VERSION}


def parse_beacon(m: dict) -> dict:
    """Valida un beacon recibido por UDP (type "hello" + campos de beacon)."""
    if m.get("type") != "hello" or "ver" not in m:
        raise ProtocolError("not a beacon")
    if int(m["ver"]) > PROTOCOL_VERSION:
        raise ProtocolError(f"unsupported version {m['ver']}")
    for k in ("control", "video", "audio"):
        if k not in m:
            raise ProtocolError(f"beacon missing {k}")
    return {"type": "hello", "name": str(m.get("name", "")),
            "control": int(m["control"]), "video": int(m["video"]),
            "audio": int(m["audio"]), "ver": int(m["ver"])}


def _hello(m: dict) -> dict:
    if not isinstance(m.get("codecs"), list) or not m["codecs"]:
        raise ProtocolError("hello needs non-empty codecs")
    if not isinstance(m.get("resolutions"), list) or not m["resolutions"]:
        raise ProtocolError("hello needs non-empty resolutions")
    return {"type": "hello", "client": str(m.get("client", "")),
            "codecs": [str(c) for c in m["codecs"]],
            "resolutions": [[int(w), int(h)] for w, h in m["resolutions"]]}


def _session(m: dict) -> dict:
    v, a = m.get("video", {}), m.get("audio", {})
    return {"type": "session", "id": str(m.get("id", "")),
            "video": {"codec": str(v.get("codec")), "w": int(v.get("w")),
                      "h": int(v.get("h")), "fps": int(v.get("fps")),
                      "pt": int(v.get("pt"))},
            "audio": {"codec": str(a.get("codec")), "rate": int(a.get("rate")),
                      "pt": int(a.get("pt"))}}


def _touch(m: dict) -> dict:
    action = m.get("action")
    if action not in TOUCH_ACTIONS:
        raise ProtocolError(f"unknown touch action: {action}")
    out = {"type": "touch", "action": action, "t": _num(m.get("t"), "t")}
    if action == "swipe":
        out.update(x0=_unit(m.get("x0"), "x0"), y0=_unit(m.get("y0"), "y0"),
                   x1=_unit(m.get("x1"), "x1"), y1=_unit(m.get("y1"), "y1"))
    else:
        out.update(x=_unit(m.get("x"), "x"), y=_unit(m.get("y"), "y"))
        if action == "long_press":
            out["ms"] = _num(m.get("ms"), "ms")
    return out


def _key(m: dict) -> dict:
    if m.get("key") not in KEYS:
        raise ProtocolError(f"bad_key: {m.get('key')}")
    return {"type": "key", "key": m["key"], "t": _num(m.get("t"), "t")}
