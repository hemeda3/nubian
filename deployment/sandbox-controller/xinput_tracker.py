#!/usr/bin/env python3
"""XInput2 global mouse/keyboard tracker.

Writes one JSON line per raw input event to stdout and /logs/input-events.jsonl.
This is intended for X11 desktop VMs.
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

LOG_PATH = pathlib.Path(os.environ.get("XINPUT_TRACKER_LOG", "/logs/input-events.jsonl"))
SCREENSHOT_DIR = pathlib.Path(os.environ.get("INPUT_EVENT_SCREENSHOT_DIR", "/logs/event-screenshots"))
SCREENSHOT_URL_PREFIX = os.environ.get("INPUT_EVENT_SCREENSHOT_URL_PREFIX", "/agent/eyes/event-screenshots").rstrip("/")
EVENT_RETENTION_SECONDS = float(os.environ.get("COMPUTER_AGENT_EVENT_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "3600")))
LOG_PRUNE_INTERVAL_SEC = float(os.environ.get("XINPUT_LOG_PRUNE_INTERVAL_SEC", "5"))
EVENT_SCREENSHOTS_ENABLED = os.environ.get("XINPUT_EVENT_SCREENSHOTS", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
EVENT_SCREENSHOT_CROP_PAD = int(os.environ.get("XINPUT_EVENT_SCREENSHOT_CROP_PAD", "50"))
MOTION_MIN_INTERVAL_SEC = float(os.environ.get("XINPUT_MOTION_MIN_INTERVAL_SEC", "0.05"))
ENRICH_RAW_MOTION = os.environ.get("XINPUT_ENRICH_RAW_MOTION", "true").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)

EVENT_RE = re.compile(r"^EVENT type \d+ \(([^)]+)\)")
DETAIL_RE = re.compile(r"detail:\s+(\d+)")
ROOT_RE = re.compile(r"root:\s+(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)")
EVENT_X_RE = re.compile(r"event:\s+(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)")
DEVICE_RE = re.compile(r"device:\s+(\d+)")
MOUSE_RE = re.compile(r"x:(-?\d+)\s+y:(-?\d+)")
DEVICE_LIST_RE = re.compile(r"(.+?)\s+id=(\d+)\s+\[([^\]]+)\]")
EVENT_COUNTER = 0
LAST_PRUNE_TS = 0.0
MODIFIER_KEYSYMS = {
    "Shift_L": "shift",
    "Shift_R": "shift",
    "Control_L": "ctrl",
    "Control_R": "ctrl",
    "Alt_L": "alt",
    "Alt_R": "alt",
    "Meta_L": "meta",
    "Meta_R": "meta",
    "Super_L": "super",
    "Super_R": "super",
    "Hyper_L": "hyper",
    "Hyper_R": "hyper",
}
TEXT_KEYSYMS = {
    "space": " ",
    "Tab": "\t",
    "Return": "\n",
    "KP_Enter": "\n",
    "BackSpace": "\b",
    "minus": "-",
    "underscore": "_",
    "equal": "=",
    "plus": "+",
    "bracketleft": "[",
    "braceleft": "{",
    "bracketright": "]",
    "braceright": "}",
    "backslash": "\\",
    "bar": "|",
    "semicolon": ";",
    "colon": ":",
    "apostrophe": "'",
    "quotedbl": "\"",
    "grave": "`",
    "asciitilde": "~",
    "comma": ",",
    "less": "<",
    "period": ".",
    "greater": ">",
    "slash": "/",
    "question": "?",
    "exclam": "!",
    "at": "@",
    "numbersign": "#",
    "dollar": "$",
    "percent": "%",
    "asciicircum": "^",
    "ampersand": "&",
    "asterisk": "*",
    "parenleft": "(",
    "parenright": ")",
}
KEYMAP_CACHE = {"t": 0.0, "map": {}}
MODIFIER_STATE = {}


def iso_timestamp_from_ms(ts_unix_ms):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts_unix_ms // 1000)) + f".{ts_unix_ms % 1000:03d}Z"


def event_time(record):
    if isinstance(record, dict):
        if "t" in record:
            try:
                return float(record["t"])
            except Exception:
                pass
        if "ts_unix_ms" in record:
            try:
                return float(record["ts_unix_ms"]) / 1000.0
            except Exception:
                pass
    return None


def safe_id(value):
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", str(value or "")).strip("-") or "event"


def prune_retained_files(force=False):
    global LAST_PRUNE_TS
    if EVENT_RETENTION_SECONDS <= 0:
        return
    now = time.time()
    if not force and now - LAST_PRUNE_TS < LOG_PRUNE_INTERVAL_SEC:
        return
    LAST_PRUNE_TS = now
    cutoff = now - EVENT_RETENTION_SECONDS
    try:
        if LOG_PATH.exists():
            lines = LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
            kept = []
            for line in lines:
                try:
                    record = json.loads(line)
                except Exception:
                    continue
                t = event_time(record)
                if t is not None and t >= cutoff:
                    kept.append(line)
            if len(kept) != len(lines):
                tmp = LOG_PATH.with_suffix(LOG_PATH.suffix + ".tmp")
                tmp.write_text(("\n".join(kept) + "\n") if kept else "", encoding="utf-8")
                tmp.replace(LOG_PATH)
    except Exception:
        pass
    try:
        if SCREENSHOT_DIR.exists():
            for path in SCREENSHOT_DIR.glob("*.png"):
                try:
                    if path.stat().st_mtime < cutoff:
                        path.unlink(missing_ok=True)
                except Exception:
                    pass
    except Exception:
        pass


def current_mouse_location():
    if not ENRICH_RAW_MOTION or not shutil.which("xdotool"):
        return None
    try:
        result = subprocess.run(
            ["xdotool", "getmouselocation"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=0.5,
            env=os.environ.copy(),
        )
        if result.returncode != 0:
            return None
        match = MOUSE_RE.search(result.stdout)
        if not match:
            return None
        return float(match.group(1)), float(match.group(2))
    except Exception:
        return None


def xinput_device_map():
    if not shutil.which("xinput"):
        return {}
    try:
        result = subprocess.run(
            ["xinput", "--list", "--short"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=1,
            env=os.environ.copy(),
        )
        if result.returncode != 0:
            return {}
    except Exception:
        return {}

    devices = {}
    for raw_line in result.stdout.splitlines():
        line = re.sub(r"^[^\w@./+-]+", "", raw_line.strip()).lstrip("↳ ").strip()
        match = DEVICE_LIST_RE.search(line)
        if not match:
            continue
        name = " ".join(match.group(1).split())
        device_id = int(match.group(2))
        descriptor = " ".join(match.group(3).split())
        device_use = "pointer" if "pointer" in descriptor else "keyboard" if "keyboard" in descriptor else "unknown"
        role = descriptor.split()[0] if descriptor else "unknown"
        master_match = re.search(r"\((\d+)\)", descriptor)
        devices[device_id] = {
            "id": device_id,
            "name": name,
            "use": device_use,
            "role": role,
            "master": int(master_match.group(1)) if master_match else None,
            "descriptor": descriptor,
        }
    return devices


def x_keymap():
    now = time.time()
    if KEYMAP_CACHE["map"] and now - KEYMAP_CACHE["t"] < 30:
        return KEYMAP_CACHE["map"]
    mapping = {}
    if not shutil.which("xmodmap"):
        return mapping
    try:
        result = subprocess.run(
            ["xmodmap", "-pke"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=1,
            env=os.environ.copy(),
        )
    except Exception:
        return mapping
    if result.returncode != 0:
        return mapping
    for line in result.stdout.splitlines():
        match = re.match(r"\s*keycode\s+(\d+)\s*=\s*(.*)$", line)
        if not match:
            continue
        symbols = [part for part in match.group(2).split() if part and part != "NoSymbol"]
        mapping[int(match.group(1))] = symbols
    KEYMAP_CACHE.update({"t": now, "map": mapping})
    return mapping


def keysym_to_text(symbol):
    if not symbol:
        return ""
    if symbol.startswith("U") and len(symbol) >= 5:
        try:
            return chr(int(symbol[1:], 16))
        except Exception:
            pass
    if len(symbol) == 1:
        return symbol
    if symbol in TEXT_KEYSYMS:
        return TEXT_KEYSYMS[symbol]
    if symbol.startswith("KP_") and len(symbol) == 4 and symbol[-1].isdigit():
        return symbol[-1]
    return ""


def modifier_names():
    active = sorted({name for name, down in MODIFIER_STATE.items() if down})
    return active


def enrich_key_event(record):
    if "Key" not in str(record.get("event", "")):
        return
    keycode = record.get("detail")
    try:
        keycode = int(keycode)
    except Exception:
        return
    symbols = x_keymap().get(keycode, [])
    pressed_modifiers = set(modifier_names())
    shift = "shift" in pressed_modifiers
    symbol_index = 1 if shift and len(symbols) > 1 else 0
    symbol = symbols[symbol_index] if symbols else ""
    base_symbol = symbols[0] if symbols else ""
    if not symbol:
        symbol = base_symbol
    modifier = MODIFIER_KEYSYMS.get(base_symbol or symbol)
    event_name = record.get("event")
    if event_name in ("KeyPress", "RawKeyPress") and modifier:
        pressed_modifiers.add(modifier)
    record["keycode"] = keycode
    record["key"] = symbol or f"keycode:{keycode}"
    record["key_base"] = base_symbol
    record["modifiers"] = sorted(pressed_modifiers)
    text = keysym_to_text(symbol)
    if event_name == "KeyPress" and text:
        # Ctrl/Alt/Super combos are commands, not literal typing.
        command_mods = pressed_modifiers.intersection({"ctrl", "alt", "super", "meta", "hyper"})
        record["text"] = "" if command_mods else text
        record["is_printable"] = not bool(command_mods)
    elif event_name == "KeyPress":
        record["text"] = ""
        record["is_printable"] = False
    if modifier and event_name in ("KeyPress", "RawKeyPress"):
        MODIFIER_STATE[modifier] = True
    elif modifier and event_name in ("KeyRelease", "RawKeyRelease"):
        MODIFIER_STATE[modifier] = False


def attach_device_info(record, devices):
    device_id = record.get("device")
    info = devices.get(device_id)
    if not info:
        return
    record["device_name"] = info.get("name")
    record["device_use"] = info.get("use")
    record["device_role"] = info.get("role")
    record["device_master"] = info.get("master")
    record["device_descriptor"] = info.get("descriptor")


def active_window_geometry():
    if not shutil.which("xdotool"):
        return None
    try:
        window = subprocess.run(
            ["xdotool", "getactivewindow"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=0.5,
            env=os.environ.copy(),
        )
        if window.returncode != 0:
            return None
        xid_decimal = window.stdout.strip()
        if not xid_decimal:
            return None
        geom = subprocess.run(
            ["xdotool", "getwindowgeometry", "--shell", xid_decimal],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=0.5,
            env=os.environ.copy(),
        )
        if geom.returncode != 0:
            return None
        values = {}
        for line in geom.stdout.splitlines():
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
        x = int(float(values.get("X", "0")))
        y = int(float(values.get("Y", "0")))
        w = int(float(values.get("WIDTH", "0")))
        h = int(float(values.get("HEIGHT", "0")))
        if w <= 0 or h <= 0:
            return None
        try:
            title = subprocess.run(
                ["xdotool", "getwindowname", xid_decimal],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=0.5,
                env=os.environ.copy(),
            ).stdout.strip()
        except Exception:
            title = ""
        return {
            "xid": hex(int(xid_decimal)),
            "x": x,
            "y": y,
            "w": w,
            "h": h,
            "bbox": [x, y, w, h],
            "title": title,
        }
    except Exception:
        return None


def capture_event_screenshot(record):
    if not EVENT_SCREENSHOTS_ENABLED:
        return
    if record.get("event") in ("start", "devices", "fatal"):
        return
    try:
        import pyautogui

        pyautogui.PAUSE = 0
        pyautogui.FAILSAFE = False
        image = pyautogui.screenshot()
        image_w, image_h = image.size
        window = active_window_geometry()
        if window:
            wx = max(0, min(int(window["x"]), image_w - 1))
            wy = max(0, min(int(window["y"]), image_h - 1))
            wr = max(wx + 1, min(int(window["x"] + window["w"]), image_w))
            wb = max(wy + 1, min(int(window["y"] + window["h"]), image_h))
            window_bbox = [wx, wy, wr - wx, wb - wy]
            pad = max(0, int(EVENT_SCREENSHOT_CROP_PAD))
            x = max(0, wx - pad)
            y = max(0, wy - pad)
            right = min(image_w, wr + pad)
            bottom = min(image_h, wb + pad)
            partial_bbox = [x, y, right - x, bottom - y]
            partial = image.crop((x, y, right, bottom))
        else:
            window_bbox = None
            partial_bbox = [0, 0, image_w, image_h]
            partial = image
        ts_unix_ms = int(record["ts_unix_ms"])
        event_id = record.get("event_id") or f"evt-{ts_unix_ms}-{safe_id(record.get('event'))}-{safe_id(record.get('device'))}-{record.get('_seq', 0)}"
        shot_id = f"shot-{event_id}"
        full_filename = f"{shot_id}.png"
        partial_filename = f"{shot_id}-partial.png"
        SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
        full_path = SCREENSHOT_DIR / full_filename
        partial_path = SCREENSHOT_DIR / partial_filename
        image.save(full_path, format="PNG", optimize=True)
        partial.save(partial_path, format="PNG", optimize=True)
        expires_ms = ts_unix_ms + int(max(0, EVENT_RETENTION_SECONDS) * 1000)
        expires_at = iso_timestamp_from_ms(expires_ms) if EVENT_RETENTION_SECONDS > 0 else None
        record["event_id"] = event_id
        record["screenshot"] = {
            "id": shot_id,
            "event_id": event_id,
            "url": f"{SCREENSHOT_URL_PREFIX}/{full_filename}",
            "path": str(full_path),
            "content_type": "image/png",
            "scope": "framebuffer",
            "bbox": [0, 0, image_w, image_h],
            "width": int(image_w),
            "height": int(image_h),
            "t": record["t"],
            "ts_unix_ms": ts_unix_ms,
            "timestamp": record["timestamp"],
            "expires_at": expires_at,
        }
        partial_ref = {
            "id": f"{shot_id}-partial",
            "event_id": event_id,
            "url": f"{SCREENSHOT_URL_PREFIX}/{partial_filename}",
            "path": str(partial_path),
            "content_type": "image/png",
            "scope": "active_window_expanded",
            "bbox": partial_bbox,
            "source_bbox": window_bbox,
            "padding_px": int(EVENT_SCREENSHOT_CROP_PAD),
            "width": int(partial.size[0]),
            "height": int(partial.size[1]),
            "t": record["t"],
            "ts_unix_ms": ts_unix_ms,
            "timestamp": record["timestamp"],
            "expires_at": expires_at,
        }
        if window:
            partial_ref["window"] = window
        record["screenshots"] = [record["screenshot"], partial_ref]
        record["partial_screenshot"] = partial_ref
    except Exception as exc:
        record["screenshot_error"] = str(exc)


def write_event(record):
    global EVENT_COUNTER
    EVENT_COUNTER += 1
    prune_retained_files()
    t = float(record.get("t") or time.time())
    ts_unix_ms = int(round(t * 1000))
    record["t"] = t
    record["ts_unix_ms"] = ts_unix_ms
    record["timestamp"] = iso_timestamp_from_ms(ts_unix_ms)
    record["_seq"] = EVENT_COUNTER
    capture_event_screenshot(record)
    record.pop("_seq", None)
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, ensure_ascii=False, sort_keys=True)
    print(line, flush=True)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def should_emit(record, last_motion_ts):
    event_type = record.get("event", "")
    if "Motion" not in event_type:
        return True
    now = record.get("t", 0)
    return now - last_motion_ts >= MOTION_MIN_INTERVAL_SEC


def emit_current(record, state):
    if not record:
        return
    enrich_key_event(record)
    if should_emit(record, state["last_motion_ts"]):
        if "Motion" in record.get("event", "") and ("x" not in record or "y" not in record):
            point = current_mouse_location()
            if point:
                record["x"], record["y"] = point
                record["event_x"], record["event_y"] = point
        write_event(record)
        if "Motion" in record.get("event", ""):
            state["last_motion_ts"] = record.get("t", state["last_motion_ts"])


def main():
    display = os.environ.get("DISPLAY", ":0")
    os.environ.setdefault("DISPLAY", display)
    write_event(
        {
            "t": round(time.time(), 3),
            "event": "start",
            "session": os.environ.get("XDG_SESSION_TYPE", "?"),
            "display": display,
            "log_path": str(LOG_PATH),
            "tool": "xinput test-xi2 --root",
        }
    )
    try:
        proc = subprocess.Popen(
            ["xinput", "test-xi2", "--root"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            env=os.environ.copy(),
        )
    except FileNotFoundError:
        write_event({"t": round(time.time(), 3), "event": "fatal", "error": "xinput not installed"})
        return 127

    state = {"last_motion_ts": 0.0}
    devices = xinput_device_map()
    write_event({"t": round(time.time(), 3), "event": "devices", "devices": list(devices.values())})
    current = None
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip("\n")
        match = EVENT_RE.search(line)
        if match:
            emit_current(current, state)
            current = {"t": round(time.time(), 3), "event": match.group(1)}
            continue
        if current is None:
            continue
        match = DEVICE_RE.search(line)
        if match:
            current["device"] = int(match.group(1))
            attach_device_info(current, devices)
            continue
        match = DETAIL_RE.search(line)
        if match:
            current["detail"] = int(match.group(1))
            continue
        match = ROOT_RE.search(line)
        if match:
            current["x"] = float(match.group(1))
            current["y"] = float(match.group(2))
            continue
        match = EVENT_X_RE.search(line)
        if match:
            current["event_x"] = float(match.group(1))
            current["event_y"] = float(match.group(2))

    emit_current(current, state)
    return proc.wait()


if __name__ == "__main__":
    sys.exit(main())
