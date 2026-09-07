"""AXLink v1: fuente simulada (servidor). Modos mock y file-loop."""
from __future__ import annotations

import argparse
import os
import random
import shutil
import socket
import subprocess
import threading
import time

from axproto import messages
from axproto.framing import LineFramer
from axproto.messages import ProtocolError

BEACON_PORT = 50000
CONTROL_PORT = 50001
VIDEO_PORT = 50002
AUDIO_PORT = 50004


def find_ffmpeg(explicit: str | None = None) -> str:
    if explicit and os.path.isfile(explicit):
        return explicit
    found = shutil.which("ffmpeg")
    if found:
        return found
    raise SystemExit("ffmpeg no encontrado: instala FFmpeg o pasa --ffmpeg RUTA")


def build_session(sid: str, w: int, h: int, fps: int, audio_codec: str) -> dict:
    apt = 98 if audio_codec == "pcm" else 97
    return {"type": "session", "id": sid,
            "video": {"codec": "h264", "w": w, "h": h, "fps": fps, "pt": 96},
            "audio": {"codec": audio_codec, "rate": 48000, "pt": apt}}


def ff_escape(path: str) -> str:
    """Escapa una ruta para la sintaxis de filtros (sin comillas shell).
    En Windows la unidad necesita doble contrabarra (C\\:) en este FFmpeg.
    """
    return path.replace("\\", "/").replace(":", "\\\\:")


def build_ffmpeg_cmd(ffmpeg: str, mode: str, src_file: str | None,
                     w: int, h: int, fps: int, vbitrate: str,
                     audio_codec: str, client_ip: str,
                     overlay_file: str | None = None) -> list:
    """Comando FFmpeg que emite vídeo+audio por RTP al cliente."""
    if mode == "file":
        if not src_file:
            raise SystemExit("--file es obligatorio en modo file")
        vin = ["-stream_loop", "-1", "-re", "-i", src_file]
    else:
        font = ff_escape("C:/Windows/Fonts/arial.ttf") if os.name == "nt" \
            else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
        vf = (f"drawtext=fontfile={font}:text='%{{localtime}}':"
              f"fontsize=48:fontcolor=white:x=(w-text_w)/2:y=60")
        if overlay_file:
            vf += (f",drawtext=fontfile={font}:textfile={ff_escape(overlay_file)}:"
                   f"reload=1:fontsize=40:fontcolor=yellow:x=40:y=h-120")
        vin = ["-re", "-f", "lavfi", "-i",
               f"testsrc2=size={w}x{h}:rate={fps}",
               "-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
               "-vf", vf]
    acodec = {"opus": "libopus", "aac": "aac", "pcm": "pcm_s16be"}[audio_codec]
    audio_pt = {"opus": 97, "aac": 97, "pcm": 98}[audio_codec]
    # Dos salidas RTP explícitas, cada una con su -map (el RTP solo
    # admite un stream por salida).
    audio_args = ["-c:a", acodec, "-ar", "48000", "-ac", "2"]
    if audio_codec != "pcm":
        audio_args += ["-b:a", "64k"]
    return ([ffmpeg, "-hide_banner", "-loglevel", "warning"] + vin + [
        "-map", "0:v",
        "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
        "-b:v", vbitrate, "-g", str(fps * 2),
        "-f", "rtp", "-payload_type", "96", f"rtp://{client_ip}:{VIDEO_PORT}",
        "-map", "1:a",
    ] + audio_args + [
        "-f", "rtp", "-payload_type", str(audio_pt),
        f"rtp://{client_ip}:{AUDIO_PORT}",
    ])


class TouchOverlay:
    """Último evento visible: se vuelca a un fichero que FFmpeg relee."""

    def __init__(self, path: str):
        self.path = path
        self.write("listo")

    def write(self, text: str):
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(text + "\n")

    def touch(self, action: str, x: float, y: float):
        self.write(f"{action} {x:.2f} {y:.2f} {time.strftime('%H:%M:%S')}")


class Server:
    def __init__(self, args):
        self.args = args
        self.ffmpeg_bin = find_ffmpeg(args.ffmpeg)
        self.sid = "%06x" % random.randrange(16 ** 6)
        self.overlay = TouchOverlay(args.overlay_file)
        self._stop = threading.Event()
        self._ff = None

    def log(self, *a):
        print("[srv]", *a, flush=True)

    def beacon_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        msg = messages.encode(messages.beacon())
        while not self._stop.is_set():
            try:
                sock.sendto(msg, ("<broadcast>", BEACON_PORT))
            except OSError as e:
                self.log("beacon error:", e)
            self._stop.wait(1.0)

    def start_ffmpeg(self, client_ip: str):
        self.stop_ffmpeg()
        cmd = build_ffmpeg_cmd(self.ffmpeg_bin, self.args.mode,
                               self.args.file, self.args.width,
                               self.args.height, self.args.fps,
                               self.args.vbitrate, self.args.audio,
                               client_ip, self.args.overlay_file)
        self.log("ffmpeg ->", client_ip)
        self._ff = subprocess.Popen(cmd)

    def stop_ffmpeg(self):
        if self._ff and self._ff.poll() is None:
            self._ff.terminate()
            try:
                self._ff.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._ff.kill()
        self._ff = None

    def handle_client(self, conn: socket.socket, addr):
        ip = addr[0]
        self.log("cliente TCP:", ip)
        framer = LineFramer()
        conn.settimeout(30)
        try:
            while not self._stop.is_set():
                data = conn.recv(4096)
                if not data:
                    break
                try:
                    msgs = framer.feed(data)
                except ProtocolError as e:
                    conn.sendall(messages.encode(
                        {"type": "error", "code": "too_big", "detail": str(e)}))
                    break
                for m in msgs:
                    self.on_msg(conn, m, ip)
        except (OSError, ProtocolError) as e:
            self.log("fin cliente:", e)
        finally:
            self.log("cliente desconectado:", ip)
            self.stop_ffmpeg()
            conn.close()

    def on_msg(self, conn, m: dict, ip: str):
        t = m.get("type")
        if t == "hello" and "codecs" in m:
            session = build_session(self.sid, self.args.width,
                                    self.args.height, self.args.fps,
                                    self.args.audio)
            conn.sendall(messages.encode(session))
            self.start_ffmpeg(ip)
            self.log("sesión", self.sid, "para", ip)
        elif t == "touch":
            if m["action"] == "swipe":
                self.log(f"toque {m['action']} "
                         f"({m['x0']:.2f},{m['y0']:.2f})->({m['x1']:.2f},{m['y1']:.2f})")
                self.overlay.write(f"swipe {time.strftime('%H:%M:%S')}")
            else:
                self.log(f"toque {m['action']} ({m['x']:.2f},{m['y']:.2f})")
                self.overlay.touch(m["action"], m["x"], m["y"])
        elif t == "key":
            self.log("tecla:", m["key"])
            self.overlay.write(f"{m['key']} {time.strftime('%H:%M:%S')}")
        elif t == "ping":
            conn.sendall(messages.encode({"type": "pong", "t": m["t"]}))
        else:
            conn.sendall(messages.encode(
                {"type": "error", "code": "unknown_type",
                 "detail": str(t)}))

    def run(self):
        threading.Thread(target=self.beacon_loop, daemon=True).start()
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", CONTROL_PORT))
        srv.listen(1)
        self.log(f"escuchando TCP {CONTROL_PORT}, beacons UDP {BEACON_PORT}")
        try:
            while not self._stop.is_set():
                srv.settimeout(1.0)
                try:
                    conn, addr = srv.accept()
                except socket.timeout:
                    continue
                self.handle_client(conn, addr)
        except KeyboardInterrupt:
            pass
        finally:
            self._stop.set()
            self.stop_ffmpeg()
            srv.close()


def main(argv=None):
    ap = argparse.ArgumentParser(description="AXLink v1: servidor simulado")
    ap.add_argument("--mode", choices=["mock", "file"], default="mock")
    ap.add_argument("--file", default=None, help="vídeo para modo file")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--vbitrate", default="2M")
    ap.add_argument("--audio", choices=["opus", "aac", "pcm"], default="opus")
    ap.add_argument("--ffmpeg", default=None)
    ap.add_argument("--overlay-file", default="touch_overlay.txt")
    args = ap.parse_args(argv)
    Server(args).run()


if __name__ == "__main__":
    main()
