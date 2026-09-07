"""Chequeo manual en vivo: beacon + sesión + RTP + tap + pong.

Uso: 1) python server.py --mode mock --width 800 --height 480 --ffmpeg RUTA
     2) python tests/live_check.py
Devuelve exit 0 si todo pasa e imprime el resumen.
"""
import json
import socket
import struct
import sys
import time

FAIL = []


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name, detail, flush=True)
    if not cond:
        FAIL.append(name)


def recv_line(f):
    buf = f.readline(70000)
    if not buf:
        raise ConnectionError("tcp cerrado")
    return json.loads(buf.decode())


def main():
    # 1. Beacon
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    u.bind(("0.0.0.0", 50000))
    u.settimeout(5)
    try:
        data, _ = u.recvfrom(2048)
    except socket.timeout:
        check("beacon", False, "(timeout)")
        return 1
    b = json.loads(data.decode())
    check("beacon", b.get("type") == "hello" and b.get("ver") == 1, str(b))
    u.close()

    # 2. Sesión
    s = socket.create_connection(("127.0.0.1", 50001), timeout=5)
    f = s.makefile("rb")
    s.sendall(b'{"type":"hello","client":"live-check","codecs":["h264","opus"],'
              b'"resolutions":[[800,480]]}\n')
    sess = recv_line(f)
    check("session", sess.get("type") == "session" and
          sess["video"]["w"] == 800 and sess["audio"]["codec"] == "opus",
          str(sess))

    # 3. RTP vídeo + audio durante ~6 s
    def listen(port, secs):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", port))
        sock.settimeout(secs)
        pkts = []
        end = time.time() + secs
        while time.time() < end:
            try:
                d, _ = sock.recvfrom(65535)
                pkts.append(d)
            except socket.timeout:
                break
        sock.close()
        return pkts

    t0 = time.time()
    import threading
    vids, auds = [], []
    tv = threading.Thread(target=lambda: vids.extend(listen(50002, 6)))
    ta = threading.Thread(target=lambda: auds.extend(listen(50004, 6)))
    tv.start()
    ta.start()

    # 4. Tap + ping mientras llega RTP
    time.sleep(1.0)
    s.sendall(b'{"type":"touch","action":"tap","x":0.42,"y":0.61,"t":1}\n')
    s.sendall(b'{"type":"ping","t":777}\n')
    pong = recv_line(f)
    check("pong", pong.get("type") == "pong" and pong.get("t") == 777,
          str(pong))
    tv.join()
    ta.join()
    dt = time.time() - t0

    def hdr(p):
        b0, b1, seq, ts, ssrc = struct.unpack(">BBHII", p[:12])
        return (b0 >> 6, b1 >> 7, b1 & 0x7F, seq, ts, ssrc, p[12:])

    ok_v = ok_a = False
    nalu_types = set()
    if vids:
        v, m, pt, _, _, ssrc, _ = hdr(vids[0])
        ok_v = v == 2 and pt == 96 and ssrc != 0
        for p in vids:
            _, _, _, _, _, _, pay = hdr(p)
            if not pay:
                continue
            if (pay[0] & 0x1F) == 28:  # FU-A
                nalu_types.add(pay[1] & 0x1F)
            else:
                nalu_types.add(pay[0] & 0x1F)
    if auds:
        v, m, pt, _, _, ssrc, _ = hdr(auds[0])
        ok_a = v == 2 and pt == 97 and ssrc != 0
    check("rtp-video", ok_v, f"{len(vids)} pkts NAL={sorted(nalu_types)}")
    check("rtp-h264", bool(nalu_types & {1, 5, 7, 8}),
          "hace falta slice/SPS/PPS")
    check("rtp-audio", ok_a, f"{len(auds)} pkts en {dt:.1f}s")
    rate = len(auds) / max(dt, 0.1)
    check("audio-rate", 30 < rate < 70, f"{rate:.0f} pkt/s (~50 Opus/20ms)")
    s.close()

    # 5. Overlay visible actualizado por el tap (lo escribe el servidor en
    # su propio directorio de trabajo)
    time.sleep(0.5)
    ov_path = ("C:\\Users\\javie\\Desktop\\Proyecto IOS\\carplay-view"
               "\\server-source\\touch_overlay.txt")
    try:
        ov = open(ov_path, encoding="utf-8").read()
        check("overlay", ov.startswith("tap 0.42 0.61"), ov.strip())
    except OSError as e:
        check("overlay", False, str(e))

    print("RESULT:", "OK" if not FAIL else f"FALLOS {FAIL}", flush=True)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
