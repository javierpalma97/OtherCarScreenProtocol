"""Tests del servidor: sesión, comando FFmpeg y overlay táctil."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from axproto import messages
from server import TouchOverlay, build_ffmpeg_cmd, build_session


def test_session_validates_against_protocol():
    s = build_session("abc123", 1280, 720, 30, "opus")
    assert messages.decode_line(messages.encode(s)[:-1])["id"] == "abc123"
    assert s["video"]["pt"] == 96 and s["audio"]["pt"] == 97
    p = build_session("x", 800, 480, 30, "pcm")
    assert p["audio"]["codec"] == "pcm" and p["audio"]["pt"] == 98


def test_ffmpeg_cmd_mock_points_at_client_ports():
    cmd = build_ffmpeg_cmd("ffmpeg", "mock", None, 800, 480, 30, "1M",
                           "opus", "127.0.0.1", overlay_file=None)
    blob = " ".join(cmd)
    assert "testsrc2=size=800x480" in blob
    assert "libx264" in blob and "libopus" in blob
    assert cmd.count("-payload_type") == 2
    assert "96" in cmd and "97" in cmd
    assert "rtp://127.0.0.1:50002" in blob and "rtp://127.0.0.1:50004" in blob


def test_ffmpeg_cmd_file_mode():
    cmd = build_ffmpeg_cmd("ffmpeg", "file", "clip.mp4", 1280, 720, 30,
                           "2M", "aac", "10.0.0.5", overlay_file=None)
    assert "-stream_loop" in cmd and "clip.mp4" in cmd
    assert "-c:a" in cmd and "aac" in cmd
    blob = " ".join(cmd)
    assert "rtp://10.0.0.5:50002" in blob


def test_touch_overlay(tmp_path):
    ov = TouchOverlay(str(tmp_path / "ov.txt"))
    ov.touch("tap", 0.42, 0.61)
    text = open(ov.path, encoding="utf-8").read()
    assert text.startswith("tap 0.42 0.61")
