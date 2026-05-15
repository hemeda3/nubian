#!/usr/bin/env python3
"""Rolling framebuffer recorder for input-trace replay.

Keeps recent screenshots in /logs/screen-recorder/frames as frame-<ts_ms>.png.
The computer agent later links input events to the nearest frame and crops
active-window partial screenshots on demand.
"""
import json
import os
import pathlib
import time


RECORDER_DIR = pathlib.Path(os.environ.get("SCREEN_RECORDER_DIR", "/logs/screen-recorder"))
FRAMES_DIR = RECORDER_DIR / "frames"
LOG_PATH = pathlib.Path(os.environ.get("SCREEN_RECORDER_LOG", "/logs/screen-recorder.jsonl"))
FPS = float(os.environ.get("SCREEN_RECORDER_FPS", "5"))
RETENTION_SEC = float(os.environ.get("SCREEN_RECORDER_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "300")))
PNG_COMPRESS_LEVEL = int(os.environ.get("SCREEN_RECORDER_PNG_COMPRESS_LEVEL", "1"))
DISPLAY = os.environ.get("DISPLAY", ":0")


def iso_timestamp_from_ms(ts_unix_ms):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts_unix_ms // 1000)) + f".{ts_unix_ms % 1000:03d}Z"


def write_log(record):
    try:
        record = dict(record)
        ts_unix_ms = int(record.get("ts_unix_ms") or round(time.time() * 1000))
        record["ts_unix_ms"] = ts_unix_ms
        record["t"] = ts_unix_ms / 1000.0
        record["timestamp"] = iso_timestamp_from_ms(ts_unix_ms)
        LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with LOG_PATH.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    except Exception:
        pass


def prune_old_frames():
    if RETENTION_SEC <= 0:
        return
    cutoff = time.time() - RETENTION_SEC
    for folder in (FRAMES_DIR,):
        if not folder.exists():
            continue
        for path in folder.glob("frame-*.png"):
            try:
                if path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
            except Exception:
                pass


def capture_loop():
    os.environ.setdefault("DISPLAY", DISPLAY)
    import pyautogui

    pyautogui.PAUSE = 0
    pyautogui.FAILSAFE = False
    FRAMES_DIR.mkdir(parents=True, exist_ok=True)
    interval = 1.0 / max(0.1, FPS)
    write_log({"event": "start", "fps": FPS, "retention_sec": RETENTION_SEC, "frames_dir": str(FRAMES_DIR)})
    last_prune = 0.0
    while True:
        started = time.time()
        ts_unix_ms = int(round(started * 1000))
        tmp = FRAMES_DIR / f".frame-{ts_unix_ms}.png.tmp"
        path = FRAMES_DIR / f"frame-{ts_unix_ms}.png"
        try:
            image = pyautogui.screenshot()
            image.save(tmp, format="PNG", compress_level=max(0, min(PNG_COMPRESS_LEVEL, 9)))
            tmp.replace(path)
        except Exception as exc:
            try:
                tmp.unlink(missing_ok=True)
            except Exception:
                pass
            write_log({"event": "capture_error", "error": str(exc)})
            time.sleep(min(2.0, interval))
            continue
        if started - last_prune >= 1.0:
            prune_old_frames()
            last_prune = started
        elapsed = time.time() - started
        time.sleep(max(0.0, interval - elapsed))


if __name__ == "__main__":
    capture_loop()
