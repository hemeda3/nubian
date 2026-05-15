#!/usr/bin/env python3
import base64
import bisect
import configparser
import csv
import difflib
import hashlib
import html
import io
import json
import mimetypes
import os
import pathlib
import platform
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLATFORM_SYSTEM = platform.system().lower()
IS_WINDOWS = os.name == "nt" or PLATFORM_SYSTEM == "windows"
IS_LINUX = PLATFORM_SYSTEM == "linux"
DEFAULT_AGENT_ROOT = pathlib.Path(
    os.environ.get(
        "COMPUTER_AGENT_ROOT",
        str(pathlib.Path(os.environ.get("LOCALAPPDATA", str(pathlib.Path.home()))) / "nubian-computer-agent")
        if IS_WINDOWS
        else "/",
    )
)
DEFAULT_WORKSPACE_DIR = pathlib.Path(
    os.environ.get("COMPUTER_AGENT_WORKSPACE", str(DEFAULT_AGENT_ROOT / "workspace") if IS_WINDOWS else "/workspace")
)
DEFAULT_DOWNLOADS_DIR = pathlib.Path(
    os.environ.get("COMPUTER_AGENT_DOWNLOADS", str(pathlib.Path.home() / "Downloads") if IS_WINDOWS else "/downloads")
)
DEFAULT_UPLOADS_DIR = pathlib.Path(
    os.environ.get("COMPUTER_AGENT_UPLOADS", str(DEFAULT_AGENT_ROOT / "uploads") if IS_WINDOWS else "/uploads")
)
DEFAULT_LOG_DIR = pathlib.Path(
    os.environ.get("COMPUTER_AGENT_LOG_DIR", str(DEFAULT_AGENT_ROOT / "logs") if IS_WINDOWS else "/logs")
)

HOST = os.environ.get("COMPUTER_AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("COMPUTER_AGENT_PORT", "6090"))
DISPLAY = os.environ.get("DISPLAY", ":0")
BACKEND = os.environ.get("COMPUTER_AGENT_BACKEND", "native").strip().lower()
VNC_BACKEND_ENABLED = BACKEND in ("vnc", "windows-vnc", "vncdotool")
VNC_TARGET_PLATFORM = os.environ.get("COMPUTER_AGENT_VNC_PLATFORM", "windows-vnc")
VNC_SERVER = os.environ.get("COMPUTER_AGENT_VNC_SERVER", os.environ.get("WINDOWS_AGENT_VNC_SERVER", ""))
VNCDO = os.environ.get("COMPUTER_AGENT_VNCDO", os.environ.get("WINDOWS_AGENT_VNCDO", "vncdo"))
VNC_TIMEOUT_SECONDS = float(os.environ.get("COMPUTER_AGENT_VNC_TIMEOUT_SECONDS", "20"))
PUBLIC_HOST = os.environ.get("PUBLIC_HOST", "YOUR_HOST")
PUBLIC_AGENT_PORT = int(os.environ.get("PUBLIC_AGENT_PORT", "29222"))
PUBLIC_AGENT_BASE_PATH = os.environ.get("PUBLIC_AGENT_BASE_PATH", "")
PUBLIC_VNC_PORT = int(os.environ.get("PUBLIC_VNC_PORT", "28006"))
OSWORLD_API = os.environ.get("OSWORLD_API", "http://127.0.0.1:5000")
PREFERRED_DISPLAY_WIDTH = int(os.environ.get("COMPUTER_AGENT_DISPLAY_WIDTH", "1024"))
PREFERRED_DISPLAY_HEIGHT = int(os.environ.get("COMPUTER_AGENT_DISPLAY_HEIGHT", "1024"))
PREFERRED_DISPLAY_REFRESH = int(os.environ.get("COMPUTER_AGENT_DISPLAY_REFRESH", "60"))
PREFERRED_DISPLAY_ENABLED = os.environ.get("COMPUTER_AGENT_DISPLAY_ENABLED", "true").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
SCREENSHOT_CURSOR_ENABLED = os.environ.get("COMPUTER_AGENT_SCREENSHOT_CURSOR", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
SCREENSHOT_CURSOR_RADIUS = int(os.environ.get("COMPUTER_AGENT_SCREENSHOT_CURSOR_RADIUS", "6"))
SCREENSHOT_MOUSE_XY_ENABLED = os.environ.get("COMPUTER_AGENT_SCREENSHOT_MOUSE_XY", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
SCREENSHOT_CLICK_HIGHLIGHT_SECONDS = float(os.environ.get("COMPUTER_AGENT_CLICK_HIGHLIGHT_SECONDS", "3.0"))
SCREENSHOT_API_DELAY_SECONDS = float(os.environ.get("COMPUTER_AGENT_SCREENSHOT_API_DELAY_SECONDS", "0.0"))
FAT_CLICK_ENABLED = os.environ.get("COMPUTER_AGENT_FAT_CLICK", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
FAT_CLICK_RADIUS = int(os.environ.get("COMPUTER_AGENT_FAT_CLICK_RADIUS", "10"))
FAT_CLICK_DELAY = float(os.environ.get("COMPUTER_AGENT_FAT_CLICK_DELAY", "0.035"))
HUMAN_CLICK_ENABLED = os.environ.get("COMPUTER_AGENT_HUMAN_CLICK", "true").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
HUMAN_CLICK_MOVE_PAUSE = float(os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_MOVE_PAUSE", "0.20"))
HUMAN_CLICK_HOLD_SECONDS = float(os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_HOLD_SECONDS", "0.35"))
HUMAN_CLICK_INTERVAL = float(os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_INTERVAL", "0.05"))
HOVER_PROBE_ENABLED = os.environ.get("COMPUTER_AGENT_HOVER_PROBE", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
HOVER_PROBE_WAIT_SECONDS = float(os.environ.get("COMPUTER_AGENT_HOVER_PROBE_WAIT_SECONDS", "0.18"))
HOVER_PROBE_PAD_X = int(os.environ.get("COMPUTER_AGENT_HOVER_PROBE_PAD_X", "55"))
HOVER_PROBE_PAD_Y = int(os.environ.get("COMPUTER_AGENT_HOVER_PROBE_PAD_Y", "28"))
HOVER_PROBE_CHANGED_PCT_THRESHOLD = float(os.environ.get("COMPUTER_AGENT_HOVER_PROBE_CHANGED_PCT_THRESHOLD", "1.0"))
HOVER_PROBE_MOVE_AWAY = os.environ.get("COMPUTER_AGENT_HOVER_PROBE_MOVE_AWAY", "true").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
HOVER_SNAP_ENABLED = os.environ.get("COMPUTER_AGENT_HOVER_SNAP", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
CENTER_SNAP_ENABLED = os.environ.get("COMPUTER_AGENT_CENTER_SNAP", "false").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
POINTER_BRIDGE_ENABLED = os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE", "true").strip().lower() not in (
    "0",
    "false",
    "no",
    "off",
)
POINTER_BRIDGE_MAX_AGE = float(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_MAX_AGE", "30.0"))
POINTER_BRIDGE_ANCHOR_PAUSE = float(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_ANCHOR_PAUSE", "0.08"))
POINTER_BRIDGE_MOVE_DURATION = float(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_MOVE_DURATION", "0.12"))
POINTER_BRIDGE_ROUTE = os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_ROUTE", "top_edge").strip().lower()
POINTER_BRIDGE_EDGE_Y = int(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_EDGE_Y", "0"))
ACTION_COORDINATE_SPACE = os.environ.get("COMPUTER_AGENT_ACTION_COORDINATES", "framebuffer").strip().lower()
LOG_DIR = DEFAULT_LOG_DIR
ACTION_LOG = LOG_DIR / "computer-agent-actions.jsonl"
WINDOW_LOG = pathlib.Path(os.environ.get("WINDOW_TRACKER_LOG", str(LOG_DIR / "windows.jsonl")))
INPUT_LOG = pathlib.Path(os.environ.get("XINPUT_TRACKER_LOG", str(LOG_DIR / "input-events.jsonl")))
ATSPI_LOG = pathlib.Path(os.environ.get("ATSPI_TRACKER_LOG", str(LOG_DIR / "atspi-events.jsonl")))
EVENT_SCREENSHOT_DIR = pathlib.Path(os.environ.get("INPUT_EVENT_SCREENSHOT_DIR", str(LOG_DIR / "event-screenshots")))
EVENT_RETENTION_SECONDS = float(os.environ.get("COMPUTER_AGENT_EVENT_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "3600")))
LOG_PRUNE_INTERVAL_SEC = float(os.environ.get("COMPUTER_AGENT_LOG_PRUNE_INTERVAL_SEC", "5"))
SCREEN_RECORDER_DIR = pathlib.Path(os.environ.get("SCREEN_RECORDER_DIR", str(LOG_DIR / "screen-recorder")))
SCREEN_RECORDER_MAX_DELTA_SEC = float(os.environ.get("SCREEN_RECORDER_MAX_FRAME_DELTA_SEC", "0.75"))
EVENT_SCREENSHOT_CROP_PAD = int(os.environ.get("INPUT_EVENT_SCREENSHOT_CROP_PAD", "50"))
EVENT_DB = pathlib.Path(os.environ.get("EVENT_INDEX_DB", str(LOG_DIR / "events.duckdb")))
EVENT_INDEX_STATE = pathlib.Path(os.environ.get("EVENT_INDEX_STATE", str(LOG_DIR / "event-indexer-state.json")))
EVENT_SESSION_ID_FILE = pathlib.Path(os.environ.get("EVENT_SESSION_ID_FILE", str(LOG_DIR / "session-id")))
SKILLS_DB = pathlib.Path(os.environ.get("COMPUTER_AGENT_SKILLS_DB", str(LOG_DIR / "skills.sqlite3")))
_FRAMEBUFFER_SIZE_CACHE = {"t": 0.0, "width": None, "height": None}
OCR_LOG_DIR = pathlib.Path(os.environ.get("COMPUTER_AGENT_OCR_DIR", str(LOG_DIR / "ocr")))
QWEN_REPAIR_URL = os.environ.get("COMPUTER_AGENT_QWEN_REPAIR_URL", "http://YOUR_HOST:28009/repair")
QWEN_REPAIR_DEFAULT = os.environ.get("COMPUTER_AGENT_QWEN_REPAIR", "off").strip().lower()
OMNIPARSER_URL = os.environ.get("COMPUTER_AGENT_OMNIPARSER_URL", os.environ.get("OMNIPARSER_URL", "")).strip().rstrip("/")
PADDLE_OCR_CACHE = {}
LAST_POINTER_EVENT = {"kind": "", "x": None, "y": None, "t": 0.0}
LAST_OMNIPARSER_PARSE = {"ok": False, "t": 0.0, "boxes": [], "width": None, "height": None}
LAST_LOG_PRUNE = {}
VNC_ACTION_LOCK = threading.Lock()
DESKTOP_APP_DIRS = (
    [
        pathlib.Path("/usr/share/applications"),
        pathlib.Path.home() / ".local/share/applications",
        pathlib.Path("/var/lib/snapd/desktop/applications"),
        pathlib.Path("/var/lib/flatpak/exports/share/applications"),
    ]
    if IS_LINUX
    else []
)
OBSERVE_FILE_DIRS = [
    DEFAULT_WORKSPACE_DIR,
    DEFAULT_DOWNLOADS_DIR,
    DEFAULT_UPLOADS_DIR,
    pathlib.Path.home() / "Documents",
    pathlib.Path.home() / "Downloads",
    pathlib.Path(os.environ.get("COMPUTER_AGENT_TMP", str(DEFAULT_AGENT_ROOT / "tmp") if IS_WINDOWS else "/tmp/agent")),
]

SEED_SKILLS = [
    {
        "id": "create_spreadsheet_csv",
        "task_pattern": "create spreadsheet excel sheet table csv rows columns save student fake id name list",
        "description": "Create a CSV spreadsheet-style table under /workspace or /workspace/agent-demo from explicit columns and rows.",
        "macro_type": "write_csv",
        "template": {
            "path": "/workspace/agent-demo/result.csv",
            "columns": ["Student Fake ID", "Student Name"],
            "rows": [["S001", "Aisha Khan"], ["S002", "Omar Ali"], ["S003", "Maya Chen"], ["S004", "Noah Smith"]],
        },
    },
    {
        "id": "create_markdown_report",
        "task_pattern": "write report markdown document summary save file md",
        "description": "Create a Markdown report file under /workspace or /workspace/agent-demo.",
        "macro_type": "write_text",
        "template": {"path": "/workspace/agent-demo/report.md", "content": "# Report\n\n"},
    },
    {
        "id": "web_links_to_markdown",
        "task_pattern": "open website find top links titles save markdown result md",
        "description": "Use browser/CDP extraction then save title/link pairs as Markdown.",
        "macro_type": "guidance",
        "template": {"preferred_tools": ["cdp_navigate", "cdp_extract_links", "write_file"]},
    },
]

DEFAULT_DIRS = [
    "/workspace",
    "/workspace/agent-demo",
    "/downloads",
    "/uploads",
    "/logs",
    "/logs/event-screenshots",
    "/logs/ocr",
    "/logs/screen-recorder",
    "/tmp/agent",
]


def ensure_dirs():
    if IS_LINUX:
        os.environ.setdefault("DISPLAY", DISPLAY)
    for folder in DEFAULT_DIRS:
        agent_path(folder).mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    OCR_LOG_DIR.mkdir(parents=True, exist_ok=True)
    ensure_skills_db()


def now_ms():
    return int(time.time() * 1000)


def iso_timestamp_from_ms(ts_unix_ms):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts_unix_ms // 1000)) + f".{ts_unix_ms % 1000:03d}Z"


def log_record_time(record):
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


def prune_jsonl_log(path, force=False):
    if EVENT_RETENTION_SECONDS <= 0 or not path.exists():
        return
    now = time.time()
    key = str(path)
    if not force and now - LAST_LOG_PRUNE.get(key, 0) < LOG_PRUNE_INTERVAL_SEC:
        return
    LAST_LOG_PRUNE[key] = now
    cutoff = now - EVENT_RETENTION_SECONDS
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        kept = []
        for line in lines:
            try:
                record = json.loads(line)
            except Exception:
                continue
            event_t = log_record_time(record)
            if event_t is not None and event_t >= cutoff:
                kept.append(line)
        if len(kept) == len(lines):
            return
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(("\n".join(kept) + "\n") if kept else "", encoding="utf-8")
        tmp.replace(path)
    except Exception:
        pass


def prune_event_screenshots(force=False):
    if EVENT_RETENTION_SECONDS <= 0 or not EVENT_SCREENSHOT_DIR.exists():
        return
    now = time.time()
    key = str(EVENT_SCREENSHOT_DIR)
    if not force and now - LAST_LOG_PRUNE.get(key, 0) < LOG_PRUNE_INTERVAL_SEC:
        return
    LAST_LOG_PRUNE[key] = now
    cutoff = now - EVENT_RETENTION_SECONDS
    try:
        for path in EVENT_SCREENSHOT_DIR.glob("*.png"):
            try:
                if path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
            except Exception:
                pass
    except Exception:
        pass


def append_log(event):
    try:
        prune_jsonl_log(ACTION_LOG)
        prune_event_screenshots()
        event = dict(event)
        ts_unix_ms = int(event.get("ts_unix_ms") or now_ms())
        event.setdefault("t", ts_unix_ms / 1000.0)
        event["ts_unix_ms"] = ts_unix_ms
        event.setdefault("timestamp", iso_timestamp_from_ms(ts_unix_ms))
        with ACTION_LOG.open("a", encoding="utf-8") as f:
            f.write(json.dumps(event, sort_keys=True) + "\n")
    except Exception:
        pass


def event_screenshot_url(filename):
    return public_agent_base_url().rstrip("/") + "/logs/event-screenshots/" + urllib.parse.quote(filename)


def ocr_artifact_url(filename):
    return public_agent_base_url().rstrip("/") + "/logs/ocr/" + urllib.parse.quote(filename)


def enrich_screenshot_ref(ref):
    if not isinstance(ref, dict):
        return ref
    ref = dict(ref)
    path = pathlib.Path(str(ref.get("path") or ""))
    filename = path.name or pathlib.Path(str(ref.get("url") or "")).name
    if filename:
        ref["url"] = event_screenshot_url(filename)
    return ref


def enrich_screenshot_refs(record):
    if not isinstance(record, dict):
        return record
    if isinstance(record.get("screenshot"), dict):
        record["screenshot"] = enrich_screenshot_ref(record["screenshot"])
    if isinstance(record.get("partial_screenshot"), dict):
        record["partial_screenshot"] = enrich_screenshot_ref(record["partial_screenshot"])
    if isinstance(record.get("screenshots"), list):
        record["screenshots"] = [
            enrich_screenshot_ref(item) if isinstance(item, dict) else item
            for item in record["screenshots"]
        ]
    return record


def safe_artifact_id(value):
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", str(value or "")).strip("-") or "event"


def trace_event_id(event, index=0):
    if isinstance(event, dict) and event.get("event_id"):
        return str(event["event_id"])
    ts = int((event or {}).get("ts_unix_ms") or round(float((event or {}).get("t") or time.time()) * 1000))
    return "evt-{}-{}-{}-{}-{}".format(
        ts,
        safe_artifact_id((event or {}).get("source")),
        safe_artifact_id((event or {}).get("event")),
        safe_artifact_id((event or {}).get("device")),
        int(index),
    )


def prune_recorded_frames(force=False):
    if EVENT_RETENTION_SECONDS <= 0:
        return
    frames_dir = SCREEN_RECORDER_DIR / "frames"
    if not frames_dir.exists():
        return
    now = time.time()
    key = str(frames_dir)
    if not force and now - LAST_LOG_PRUNE.get(key, 0) < LOG_PRUNE_INTERVAL_SEC:
        return
    LAST_LOG_PRUNE[key] = now
    cutoff = now - EVENT_RETENTION_SECONDS
    for path in frames_dir.glob("frame-*.png"):
        try:
            if path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)
        except Exception:
            pass


def recorded_frame_index():
    prune_recorded_frames()
    frames_dir = SCREEN_RECORDER_DIR / "frames"
    frames = []
    if not frames_dir.exists():
        return frames
    for path in frames_dir.glob("frame-*.png"):
        match = re.match(r"frame-(\d+)\.png$", path.name)
        if not match:
            continue
        try:
            frames.append((int(match.group(1)), path))
        except Exception:
            continue
    frames.sort(key=lambda item: item[0])
    return frames


def nearest_recorded_frame(ts_unix_ms, frames):
    if not frames:
        return None
    stamps = [item[0] for item in frames]
    pos = bisect.bisect_left(stamps, int(ts_unix_ms))
    candidates = []
    if pos < len(frames):
        candidates.append(frames[pos])
    if pos > 0:
        candidates.append(frames[pos - 1])
    if not candidates:
        return None
    best = min(candidates, key=lambda item: abs(item[0] - int(ts_unix_ms)))
    delta = abs(best[0] - int(ts_unix_ms)) / 1000.0
    if delta > SCREEN_RECORDER_MAX_DELTA_SEC:
        return None
    return best[0], best[1], delta


def event_source_bbox(event):
    for key in ("active_window", "window_at_point"):
        window = event.get(key) if isinstance(event, dict) else None
        if not isinstance(window, dict):
            continue
        bbox = window.get("bbox")
        if isinstance(bbox, list) and len(bbox) >= 4 and all(value is not None for value in bbox[:4]):
            try:
                return [int(float(bbox[0])), int(float(bbox[1])), int(float(bbox[2])), int(float(bbox[3]))]
            except Exception:
                pass
        try:
            return [
                int(float(window.get("x"))),
                int(float(window.get("y"))),
                int(float(window.get("w"))),
                int(float(window.get("h"))),
            ]
        except Exception:
            pass
    return None


def expanded_crop_bbox(source_bbox, image_size, pad=50):
    width, height = image_size
    if not source_bbox:
        return [0, 0, int(width), int(height)]
    x, y, w, h = source_bbox
    left = max(0, int(x) - int(pad))
    top = max(0, int(y) - int(pad))
    right = min(int(width), int(x) + int(w) + int(pad))
    bottom = min(int(height), int(y) + int(h) + int(pad))
    if right <= left or bottom <= top:
        return [0, 0, int(width), int(height)]
    return [left, top, right - left, bottom - top]


def event_xy(event):
    if not isinstance(event, dict):
        return None
    try:
        x = event.get("x")
        y = event.get("y")
        if x is None or y is None:
            return None
        return int(round(float(x))), int(round(float(y)))
    except Exception:
        return None


def draw_trace_marker(image, x, y):
    try:
        from PIL import ImageDraw

        image = image.convert("RGB")
        width, height = image.size
        if width <= 0 or height <= 0:
            return image
        x = max(0, min(int(x), width - 1))
        y = max(0, min(int(y), height - 1))
        draw = ImageDraw.Draw(image)
        radius = 18
        halo = radius + 8
        draw.ellipse((x - halo, y - halo, x + halo, y + halo), outline=(0, 0, 0), width=5)
        draw.ellipse((x - radius - 3, y - radius - 3, x + radius + 3, y + radius + 3), outline=(255, 255, 255), width=5)
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), outline=(255, 230, 0), width=5)
        draw.line((x - halo - 10, y, x + halo + 10, y), fill=(255, 0, 0), width=3)
        draw.line((x, y - halo - 10, x, y + halo + 10), fill=(255, 0, 0), width=3)
        draw.ellipse((x - 4, y - 4, x + 4, y + 4), fill=(255, 0, 0), outline=(0, 0, 0))
    except Exception:
        return image
    return image


def marker_ref(x, y, coordinate_space="framebuffer", framebuffer_xy=None):
    ref = {
        "x": int(x),
        "y": int(y),
        "coordinate_space": coordinate_space,
        "style": "black_white_yellow_ring_red_crosshair",
    }
    if framebuffer_xy:
        ref["framebuffer_x"] = int(framebuffer_xy[0])
        ref["framebuffer_y"] = int(framebuffer_xy[1])
    return ref


def attach_recorded_screenshots(event, frames, index=0):
    if not isinstance(event, dict):
        return event
    try:
        ts_unix_ms = int(event.get("ts_unix_ms") or round(float(event.get("t") or time.time()) * 1000))
    except Exception:
        return event
    nearest = nearest_recorded_frame(ts_unix_ms, frames)
    if not nearest:
        event["screenshot_missing"] = "no recorded frame within max delta"
        return event
    frame_ts_ms, frame_path, delta_sec = nearest
    event_id = trace_event_id(event, index)
    event["event_id"] = event_id
    shot_id = f"shot-{event_id}"
    full_name = f"{shot_id}.png"
    partial_name = f"{shot_id}-partial.png"
    full_path = EVENT_SCREENSHOT_DIR / full_name
    partial_path = EVENT_SCREENSHOT_DIR / partial_name
    EVENT_SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    try:
        from PIL import Image

        with Image.open(frame_path) as image:
            image = image.convert("RGB")
            width, height = image.size
            point = event_xy(event)
            full_marker = None
            full = image.copy()
            if point:
                full = draw_trace_marker(full, point[0], point[1])
                full_marker = marker_ref(point[0], point[1])
            full.save(full_path, format="PNG", compress_level=1)
            os.utime(full_path, None)
            source_bbox = event_source_bbox(event)
            crop_bbox = expanded_crop_bbox(source_bbox, image.size, EVENT_SCREENSHOT_CROP_PAD)
            left, top, crop_w, crop_h = crop_bbox
            partial_marker = None
            crop = image.crop((left, top, left + crop_w, top + crop_h))
            if point and left <= point[0] < left + crop_w and top <= point[1] < top + crop_h:
                partial_x = point[0] - left
                partial_y = point[1] - top
                crop = draw_trace_marker(crop, partial_x, partial_y)
                partial_marker = marker_ref(partial_x, partial_y, "crop", point)
            crop.save(partial_path, format="PNG", compress_level=1)
            os.utime(partial_path, None)
    except Exception as exc:
        event["screenshot_error"] = str(exc)
        return event
    timestamp = event.get("timestamp") or iso_timestamp_from_ms(ts_unix_ms)
    expires_at = iso_timestamp_from_ms(ts_unix_ms + int(EVENT_RETENTION_SECONDS * 1000)) if EVENT_RETENTION_SECONDS > 0 else None
    base = {
        "event_id": event_id,
        "content_type": "image/png",
        "t": ts_unix_ms / 1000.0,
        "ts_unix_ms": ts_unix_ms,
        "timestamp": timestamp,
        "expires_at": expires_at,
        "recording_frame": {
            "ts_unix_ms": frame_ts_ms,
            "timestamp": iso_timestamp_from_ms(frame_ts_ms),
            "delta_sec": round(float(delta_sec), 3),
            "path": str(frame_path),
        },
    }
    full_ref = {
        **base,
        "id": shot_id,
        "url": event_screenshot_url(full_name),
        "path": str(full_path),
        "scope": "framebuffer",
        "bbox": [0, 0, int(width), int(height)],
        "width": int(width),
        "height": int(height),
    }
    if full_marker:
        full_ref["marker"] = full_marker
    partial_ref = {
        **base,
        "id": f"{shot_id}-partial",
        "url": event_screenshot_url(partial_name),
        "path": str(partial_path),
        "scope": "active_window_expanded",
        "bbox": crop_bbox,
        "source_bbox": source_bbox,
        "padding_px": int(EVENT_SCREENSHOT_CROP_PAD),
        "width": int(crop_bbox[2]),
        "height": int(crop_bbox[3]),
    }
    if partial_marker:
        partial_ref["marker"] = partial_marker
    event["screenshot"] = full_ref
    event["partial_screenshot"] = partial_ref
    event["screenshots"] = [full_ref, partial_ref]
    return event


def json_bytes(payload):
    return json.dumps(payload, indent=2, sort_keys=True).encode("utf-8") + b"\n"


def safe_timeout_ms(value, default=30000):
    try:
        value = int(value)
    except Exception:
        value = default
    return max(100, min(value, 600000))


def bool_param(value, default=False):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() not in ("0", "false", "no", "off", "")
    return bool(value)


def query_values(query, *names):
    values = []
    for name in names:
        for raw in query.get(name, []) or []:
            for item in str(raw).split(","):
                item = item.strip()
                if item:
                    values.append(item)
    return values


def agent_path(value, default=None):
    text = str(value if value not in (None, "") else default if default is not None else DEFAULT_WORKSPACE_DIR)
    expanded = pathlib.Path(text).expanduser()
    if IS_WINDOWS:
        normalized = text.replace("\\", "/")
        mappings = {
            "/workspace": DEFAULT_WORKSPACE_DIR,
            "/downloads": DEFAULT_DOWNLOADS_DIR,
            "/uploads": DEFAULT_UPLOADS_DIR,
            "/logs": LOG_DIR,
            "/tmp/agent": pathlib.Path(os.environ.get("COMPUTER_AGENT_TMP", str(DEFAULT_AGENT_ROOT / "tmp"))),
        }
        for prefix, root in mappings.items():
            if normalized == prefix or normalized.startswith(prefix + "/"):
                tail = normalized[len(prefix) :].lstrip("/")
                return pathlib.Path(root, *[part for part in tail.split("/") if part])
    return expanded


def agent_path_string(value, default=None):
    return str(agent_path(value, default=default))


def platform_env(extra=None):
    env = {**os.environ}
    if IS_LINUX:
        env["DISPLAY"] = os.environ.get("DISPLAY", DISPLAY)
    if extra:
        env.update(extra)
    return env


def vnc_require_config():
    if not VNC_SERVER:
        raise RuntimeError("COMPUTER_AGENT_VNC_SERVER is required when COMPUTER_AGENT_BACKEND=vnc")
    if not shutil.which(VNCDO) and not pathlib.Path(VNCDO).exists():
        raise RuntimeError(f"vncdo not found: {VNCDO}")


def run_vncdo(args, timeout=None):
    vnc_require_config()
    cmd = [VNCDO, "-s", VNC_SERVER, "--delay=30", *[str(arg) for arg in args]]
    start = time.time()
    with VNC_ACTION_LOCK:
        proc = subprocess.run(
            cmd,
            cwd=str(DEFAULT_WORKSPACE_DIR),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=float(timeout or VNC_TIMEOUT_SECONDS),
            env=platform_env(),
        )
    return {
        "ok": proc.returncode == 0,
        "rc": proc.returncode,
        "stdout": proc.stdout,
        "stderr": proc.stderr,
        "elapsed_ms": int((time.time() - start) * 1000),
        "cmd": cmd,
    }


def vnc_capture_framebuffer_image():
    from PIL import Image

    DEFAULT_WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)
    path = DEFAULT_WORKSPACE_DIR / f"vnc-capture-{now_ms()}.png"
    try:
        result = run_vncdo(["capture", str(path)], timeout=VNC_TIMEOUT_SECONDS)
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or result.get("stdout") or "vnc capture failed")
        with Image.open(path) as image:
            copy = image.copy()
        remember_framebuffer_size(copy.size[0], copy.size[1])
        return copy
    finally:
        try:
            path.unlink(missing_ok=True)
        except Exception:
            pass


def vnc_button_number(button="left"):
    text = str(button or "left").lower()
    if text in ("1", "left", "primary"):
        return 1
    if text in ("2", "middle", "wheel"):
        return 2
    if text in ("3", "right", "secondary"):
        return 3
    if text in ("4", "scroll_up", "wheel_up"):
        return 4
    if text in ("5", "scroll_down", "wheel_down"):
        return 5
    raise ValueError(f"unsupported mouse button: {button!r}")


def vnc_key_combo(value):
    if isinstance(value, list):
        parts = [str(part).strip().lower() for part in value if str(part).strip()]
    else:
        parts = [part.strip().lower() for part in str(value or "").replace("+", "-").split("-") if part.strip()]
    aliases = {
        "control": "ctrl",
        "return": "enter",
        "esc": "escape",
        "cmd": "super",
        "command": "super",
        "win": "super",
        "windows": "super",
    }
    return "-".join(aliases.get(part, part) for part in parts)


def split_action_keys(value):
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        parts = []
        for item in value:
            parts.extend(split_action_keys(item))
        return parts
    text = str(value).strip()
    if not text:
        return []
    if "+" in text:
        return [part.strip() for part in text.split("+") if part.strip()]
    pieces = [part.strip() for part in text.split("-") if part.strip()]
    if len(pieces) > 1 and pieces[0].lower() in {
        "alt",
        "cmd",
        "command",
        "control",
        "control_l",
        "control_r",
        "ctrl",
        "ctrl_l",
        "ctrl_r",
        "meta",
        "shift",
        "shift_l",
        "shift_r",
        "super",
        "win",
        "windows",
    }:
        return pieces
    return [text]


def pyautogui_key_name(key):
    raw = str(key or "").strip()
    lowered = raw.lower()
    aliases = {
        "control": "ctrl",
        "control_l": "ctrl",
        "control_r": "ctrl",
        "ctrl_l": "ctrl",
        "ctrl_r": "ctrl",
        "leftctrl": "ctrl",
        "rightctrl": "ctrl",
        "shift_l": "shift",
        "shift_r": "shift",
        "leftshift": "shift",
        "rightshift": "shift",
        "alt_l": "alt",
        "alt_r": "alt",
        "leftalt": "alt",
        "rightalt": "alt",
        "option": "alt",
        "return": "enter",
        "esc": "escape",
        "spacebar": "space",
        "cmd": "win" if IS_WINDOWS else "super",
        "command": "win" if IS_WINDOWS else "super",
        "meta": "win" if IS_WINDOWS else "super",
        "super": "win" if IS_WINDOWS else "super",
        "windows": "win",
    }
    if lowered in aliases:
        return aliases[lowered]
    return lowered if len(raw) != 1 else raw.lower()


def pyautogui_key_combo(value):
    return [pyautogui_key_name(part) for part in split_action_keys(value)]


def vnc_action_one(item):
    typ = str(item.get("type", item.get("action", ""))).strip().lower()
    if not typ:
        raise ValueError("missing action type")

    if typ in ("wait", "pause", "sleep"):
        seconds = float(item.get("seconds", item.get("sec", float(item.get("ms", 1000)) / 1000.0)))
        time.sleep(max(0.0, seconds))
        return {"ok": True, "type": "wait", "seconds": seconds}

    if typ in ("move", "move_to"):
        x, y = clamp_framebuffer_point(item["x"], item["y"])
        result = run_vncdo(["move", x, y])
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc move failed")
        remember_pointer_event("move", x, y)
        return {"ok": True, "type": "move", "x": x, "y": y}

    if typ in ("click", "double_click", "dblclick", "right_click", "context_click"):
        x, y = clamp_framebuffer_point(item["x"], item["y"])
        clicks = int(item.get("clicks", 2 if typ in ("double_click", "dblclick") else 1))
        button = 3 if typ in ("right_click", "context_click") else vnc_button_number(item.get("button", "left"))
        args = ["move", x, y, "pause", HUMAN_CLICK_MOVE_PAUSE]
        for index in range(max(1, clicks)):
            args.extend(["mousedown", button, "pause", HUMAN_CLICK_HOLD_SECONDS, "mouseup", button])
            if index < clicks - 1:
                args.extend(["pause", HUMAN_CLICK_INTERVAL])
        result = run_vncdo(args, timeout=max(VNC_TIMEOUT_SECONDS, clicks * 2))
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc click failed")
        remember_pointer_event("click" if typ == "click" else typ, x, y)
        return {"ok": True, "type": typ, "x": x, "y": y, "button": button, "clicks": max(1, clicks)}

    if typ in ("mouse_down", "down", "press_mouse"):
        x = item.get("x", LAST_POINTER_EVENT.get("x") or 0)
        y = item.get("y", LAST_POINTER_EVENT.get("y") or 0)
        x, y = clamp_framebuffer_point(x, y)
        button = vnc_button_number(item.get("button", "left"))
        result = run_vncdo(["move", x, y, "mousedown", button])
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc mouse_down failed")
        remember_pointer_event("mouse_down", x, y)
        return {"ok": True, "type": "mouse_down", "x": x, "y": y, "button": button}

    if typ in ("mouse_up", "up", "release_mouse"):
        x = item.get("x", LAST_POINTER_EVENT.get("x") or 0)
        y = item.get("y", LAST_POINTER_EVENT.get("y") or 0)
        x, y = clamp_framebuffer_point(x, y)
        button = vnc_button_number(item.get("button", "left"))
        result = run_vncdo(["move", x, y, "mouseup", button])
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc mouse_up failed")
        remember_pointer_event("mouse_up", x, y)
        return {"ok": True, "type": "mouse_up", "x": x, "y": y, "button": button}

    if typ in ("drag", "drag_to"):
        x1, y1 = clamp_framebuffer_point(item.get("x", item.get("from_x")), item.get("y", item.get("from_y")))
        x2, y2 = clamp_framebuffer_point(item.get("to_x", item.get("end_x")), item.get("to_y", item.get("end_y")))
        button = vnc_button_number(item.get("button", "left"))
        result = run_vncdo(["move", x1, y1, "mousedown", button, "drag", x2, y2, "mouseup", button])
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc drag failed")
        remember_pointer_event("drag", x2, y2)
        return {"ok": True, "type": "drag", "from": [x1, y1], "to": [x2, y2], "button": button}

    if typ in ("type", "write", "text"):
        text = str(item.get("text", item.get("value", "")))
        if not text:
            return {"ok": True, "type": "type", "chars": 0}
        result = run_vncdo(["type", text], timeout=max(VNC_TIMEOUT_SECONDS, len(text) * 0.2))
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc type failed")
        return {"ok": True, "type": "type", "chars": len(text)}

    if typ in ("hotkey", "key", "press"):
        combo = vnc_key_combo(item.get("keys", item.get("combo", item.get("key", ""))))
        if not combo:
            raise ValueError("hotkey requires keys/combo/key")
        result = run_vncdo(["key", combo])
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc hotkey failed")
        return {"ok": True, "type": "hotkey", "combo": combo}

    if typ in ("scroll", "wheel"):
        x = item.get("x", LAST_POINTER_EVENT.get("x") or 0)
        y = item.get("y", LAST_POINTER_EVENT.get("y") or 0)
        x, y = clamp_framebuffer_point(x, y)
        raw = int(item.get("amount", item.get("clicks", 3)))
        button = 4 if raw > 0 else 5
        args = ["move", x, y]
        for _ in range(max(1, abs(raw))):
            args.extend(["click", button])
        result = run_vncdo(args)
        if not result.get("ok"):
            raise RuntimeError(result.get("stderr") or "vnc scroll failed")
        remember_pointer_event("scroll", x, y)
        return {"ok": True, "type": "scroll", "x": x, "y": y, "amount": raw, "button": button}

    raise ValueError(f"unsupported action type: {typ}")


def run_command(cmd, cwd="/workspace", timeout_ms=30000, shell=True):
    cwd_path = agent_path(cwd, default=DEFAULT_WORKSPACE_DIR)
    cwd_path.mkdir(parents=True, exist_ok=True)
    start = time.time()
    result = subprocess.run(
        cmd,
        cwd=str(cwd_path),
        shell=shell,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=safe_timeout_ms(timeout_ms) / 1000.0,
        env=platform_env(),
    )
    return {
        "ok": result.returncode == 0,
        "rc": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "cwd": str(cwd_path),
        "elapsed_ms": int((time.time() - start) * 1000),
    }


def set_clipboard_text(text):
    env = platform_env()
    if IS_WINDOWS:
        try:
            import pyperclip

            pyperclip.copy(str(text))
            return {"ok": True, "tool": "pyperclip"}
        except Exception:
            pass
        try:
            process = subprocess.Popen(
                ["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())"],
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
            )
            process.stdin.write(str(text))
            process.stdin.close()
            return {"ok": process.wait(timeout=5) == 0, "tool": "powershell"}
        except Exception as exc:
            return {"ok": False, "tool": "windows_clipboard", "error": str(exc)}
    if shutil.which("xclip"):
        process = subprocess.Popen(
            ["xclip", "-selection", "clipboard"],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=env,
            text=True,
            start_new_session=True,
        )
        process.stdin.write(text)
        process.stdin.close()
        time.sleep(0.15)
        return {"ok": process.poll() in (None, 0), "tool": "xclip", "pid": process.pid}
    if shutil.which("xsel"):
        result = subprocess.run(
            ["xsel", "--clipboard", "--input"],
            input=text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
            env=env,
        )
        return {"ok": result.returncode == 0, "tool": "xsel", "rc": result.returncode, "stderr": result.stderr}
    return {"ok": False, "tool": None, "error": "xclip or xsel not installed"}


def read_clipboard_text(max_chars=10000):
    max_chars = max(0, min(int(max_chars or 10000), 200000))
    if VNC_BACKEND_ENABLED:
        return {
            "ok": False,
            "tool": "vnc",
            "text": "",
            "length": 0,
            "error": "clipboard read is unavailable through raw VNC; run native Windows/Linux agent or add a guest clipboard bridge",
        }

    def payload(text, tool):
        text = "" if text is None else str(text)
        truncated = len(text) > max_chars
        return {
            "ok": True,
            "tool": tool,
            "text": text[:max_chars],
            "length": len(text),
            "truncated": truncated,
        }

    env = platform_env()
    if IS_WINDOWS:
        try:
            import pyperclip

            return payload(pyperclip.paste(), "pyperclip")
        except Exception:
            pass
        try:
            result = subprocess.run(
                ["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=5,
                env=env,
            )
            if result.returncode == 0:
                return payload(result.stdout, "powershell")
            return {"ok": False, "tool": "powershell", "text": "", "length": 0, "error": result.stderr.strip()}
        except Exception as exc:
            return {"ok": False, "tool": "windows_clipboard", "text": "", "length": 0, "error": str(exc)}

    if shutil.which("xclip"):
        result = subprocess.run(
            ["xclip", "-selection", "clipboard", "-o"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5,
            env=env,
        )
        if result.returncode == 0:
            return payload(result.stdout, "xclip")
    if shutil.which("xsel"):
        result = subprocess.run(
            ["xsel", "--clipboard", "--output"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5,
            env=env,
        )
        if result.returncode == 0:
            return payload(result.stdout, "xsel")
    try:
        import pyperclip

        return payload(pyperclip.paste(), "pyperclip")
    except Exception as exc:
        return {"ok": False, "tool": None, "text": "", "length": 0, "error": str(exc)}


def rows_to_tsv(rows):
    lines = []
    for row in rows or []:
        if not isinstance(row, (list, tuple)):
            row = [row]
        cells = [str(cell).replace("\t", " ").replace("\r", " ").replace("\n", " ") for cell in row]
        lines.append("\t".join(cells))
    return "\n".join(lines)


def json_safe(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if hasattr(value, "tolist"):
        return json_safe(value.tolist())
    if hasattr(value, "item"):
        try:
            return value.item()
        except Exception:
            pass
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    return str(value)


def normalize_match_text(value):
    text = unicodedata.normalize("NFKD", str(value or ""))
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return re.sub(r"\s+", " ", text).strip().casefold()


def extract_python_code(value):
    text = str(value or "").strip()
    xml_match = re.search(r"<(?:code|execute)\b[^>]*>(.*?)</(?:code|execute)>", text, re.IGNORECASE | re.DOTALL)
    if xml_match:
        text = xml_match.group(1).strip()
    match = re.search(r"```(?:python|py)?\s*(.*?)```", text, re.IGNORECASE | re.DOTALL)
    if match:
        return match.group(1).strip()
    lines = text.splitlines()
    if lines and lines[0].strip().casefold() in ("python", "py"):
        return "\n".join(lines[1:]).strip()
    return text


def split_top_level(text, delimiter=";"):
    parts = []
    start = 0
    depth = 0
    quote = None
    triple = False
    escaped = False
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif triple and text.startswith(quote * 3, i):
                quote = None
                triple = False
                i += 2
            elif not triple and ch == quote:
                quote = None
        else:
            if ch in ("'", '"'):
                quote = ch
                triple = text.startswith(ch * 3, i)
                if triple:
                    i += 2
            elif ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth = max(0, depth - 1)
            elif ch == delimiter and depth == 0:
                parts.append(text[start:i].strip())
                start = i + 1
        i += 1
    parts.append(text[start:].strip())
    return [part for part in parts if part]


def find_top_level_colon(text):
    depth = 0
    quote = None
    triple = False
    escaped = False
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif triple and text.startswith(quote * 3, i):
                quote = None
                triple = False
                i += 2
            elif not triple and ch == quote:
                quote = None
        else:
            if ch in ("'", '"'):
                quote = ch
                triple = text.startswith(ch * 3, i)
                if triple:
                    i += 2
            elif ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth = max(0, depth - 1)
            elif ch == ":" and depth == 0:
                return i
        i += 1
    return -1


def is_compound_statement_start(statement):
    stripped = statement.lstrip()
    return (
        stripped.startswith(("if ", "for ", "while ", "with ", "def ", "class ", "elif ", "except "))
        or stripped.startswith(("async for ", "async with ", "async def "))
        or stripped in ("else", "try", "finally")
        or stripped.startswith(("else:", "try:", "finally:"))
    )


def expand_inline_compound_statement(statement, indent=0):
    stripped = statement.strip()
    if not stripped:
        return []
    colon = find_top_level_colon(stripped)
    if colon < 0 or not is_compound_statement_start(stripped[:colon]):
        return [" " * indent + stripped]
    header = stripped[:colon].rstrip()
    tail = stripped[colon + 1 :].strip()
    lines = [" " * indent + header + ":"]
    if tail:
        for part in split_top_level(tail, ";"):
            lines.extend(expand_inline_compound_statement(part, indent + 4))
    return lines


def expand_inline_python(code):
    out = []
    for raw_line in code.splitlines():
        if not raw_line.strip():
            out.append(raw_line)
            continue
        base_indent = len(raw_line) - len(raw_line.lstrip(" "))
        stripped = raw_line.strip()
        if stripped.startswith("#"):
            out.append(raw_line)
            continue
        for part in split_top_level(stripped, ";"):
            out.extend(expand_inline_compound_statement(part, base_indent))
    return "\n".join(out).strip()


def compound_header_tail(statement):
    stripped = statement.strip()
    colon = find_top_level_colon(stripped)
    if colon < 0:
        return None, None
    header = stripped[:colon].strip()
    tail = stripped[colon + 1 :].strip()
    if not is_compound_statement_start(header):
        return None, None
    return header, tail


def statement_clause_kind(statement):
    header, _tail = compound_header_tail(statement)
    if not header:
        return ""
    lowered = header.casefold()
    if lowered.startswith("elif "):
        return "elif"
    if lowered == "else":
        return "else"
    if lowered.startswith("except"):
        return "except"
    if lowered == "finally":
        return "finally"
    return ""


def structured_inline_statement_lines(statement, indent):
    header, tail = compound_header_tail(statement)
    if not header:
        return [" " * indent + statement.strip()] if statement.strip() else []
    lines = [" " * indent + header + ":"]
    if tail:
        nested, _idx = parse_structured_inline_tokens(split_top_level(tail, ";"), 0, indent + 4, ())
        lines.extend(nested or [" " * (indent + 4) + "pass"])
    return lines


def parse_structured_inline_tokens(tokens, index=0, indent=0, stop_kinds=()):
    lines = []
    while index < len(tokens):
        token = tokens[index].strip()
        if not token:
            index += 1
            continue
        kind = statement_clause_kind(token)
        if kind in stop_kinds:
            break
        header, tail = compound_header_tail(token)
        if not header:
            lines.append(" " * indent + token)
            index += 1
            continue

        lowered = header.casefold()
        if lowered == "try":
            lines.append(" " * indent + header + ":")
            body = []
            if tail:
                nested, _idx = parse_structured_inline_tokens(split_top_level(tail, ";"), 0, indent + 4, ())
                body.extend(nested)
            index += 1
            nested, index = parse_structured_inline_tokens(tokens, index, indent + 4, ("except", "finally"))
            body.extend(nested)
            lines.extend(body or [" " * (indent + 4) + "pass"])
            while index < len(tokens) and statement_clause_kind(tokens[index]) in ("except", "finally"):
                clause_header, clause_tail = compound_header_tail(tokens[index])
                lines.append(" " * indent + clause_header + ":")
                index += 1
                clause_body = []
                if clause_tail:
                    nested, _idx = parse_structured_inline_tokens(split_top_level(clause_tail, ";"), 0, indent + 4, ())
                    clause_body.extend(nested)
                nested, index = parse_structured_inline_tokens(tokens, index, indent + 4, ("except", "finally"))
                clause_body.extend(nested)
                lines.extend(clause_body or [" " * (indent + 4) + "pass"])
            continue

        if lowered.startswith("if "):
            lines.append(" " * indent + header + ":")
            body = []
            if tail:
                nested, _idx = parse_structured_inline_tokens(split_top_level(tail, ";"), 0, indent + 4, ())
                body.extend(nested)
            index += 1
            nested, index = parse_structured_inline_tokens(
                tokens, index, indent + 4, ("elif", "else", "except", "finally")
            )
            body.extend(nested)
            lines.extend(body or [" " * (indent + 4) + "pass"])
            while index < len(tokens) and statement_clause_kind(tokens[index]) in ("elif", "else"):
                clause_header, clause_tail = compound_header_tail(tokens[index])
                lines.append(" " * indent + clause_header + ":")
                index += 1
                clause_body = []
                if clause_tail:
                    nested, _idx = parse_structured_inline_tokens(split_top_level(clause_tail, ";"), 0, indent + 4, ())
                    clause_body.extend(nested)
                nested, index = parse_structured_inline_tokens(
                    tokens, index, indent + 4, ("elif", "else", "except", "finally")
                )
                clause_body.extend(nested)
                lines.extend(clause_body or [" " * (indent + 4) + "pass"])
            continue

        if (
            lowered.startswith(("for ", "while ", "with ", "def ", "class "))
            or lowered.startswith(("async for ", "async with ", "async def "))
        ):
            lines.append(" " * indent + header + ":")
            body = []
            if tail:
                nested, _idx = parse_structured_inline_tokens(split_top_level(tail, ";"), 0, indent + 4, ())
                body.extend(nested)
            index += 1
            nested, index = parse_structured_inline_tokens(tokens, index, indent + 4, ("else", "except", "finally"))
            body.extend(nested)
            lines.extend(body or [" " * (indent + 4) + "pass"])
            if lowered.startswith(("for ", "while ")) or lowered.startswith("async for "):
                while index < len(tokens) and statement_clause_kind(tokens[index]) == "else":
                    clause_header, clause_tail = compound_header_tail(tokens[index])
                    lines.append(" " * indent + clause_header + ":")
                    index += 1
                    clause_body = []
                    if clause_tail:
                        nested, _idx = parse_structured_inline_tokens(
                            split_top_level(clause_tail, ";"), 0, indent + 4, ()
                        )
                        clause_body.extend(nested)
                    nested, index = parse_structured_inline_tokens(tokens, index, indent + 4, ("except", "finally"))
                    clause_body.extend(nested)
                    lines.extend(clause_body or [" " * (indent + 4) + "pass"])
            continue

        lines.extend(structured_inline_statement_lines(token, indent))
        index += 1
    return lines, index


def structured_expand_inline_python(code):
    out = []
    for raw_line in code.splitlines():
        if not raw_line.strip():
            out.append(raw_line)
            continue
        base_indent = len(raw_line) - len(raw_line.lstrip(" "))
        stripped = raw_line.strip()
        if stripped.startswith("#"):
            out.append(raw_line)
            continue
        tokens = split_top_level(stripped, ";")
        expanded, _idx = parse_structured_inline_tokens(tokens, 0, base_indent, ())
        out.extend(expanded)
    return "\n".join(out).strip()


def syntax_error_summary(exc):
    return {
        "type": exc.__class__.__name__,
        "message": str(exc),
        "lineno": getattr(exc, "lineno", None),
        "offset": getattr(exc, "offset", None),
        "text": (getattr(exc, "text", "") or "").strip(),
    }


def repair_python_code_for_execution(code):
    changes = []
    try:
        compile(code, "<agent-code>", "exec")
        return code, changes, None
    except SyntaxError as first_exc:
        repaired = expand_inline_python(code)
        if repaired != code:
            try:
                compile(repaired, "<agent-code>", "exec")
                changes.append("expanded semicolon/inline compound statements")
                return repaired, changes, None
            except SyntaxError as second_exc:
                structured = structured_expand_inline_python(code)
                if structured != code:
                    try:
                        compile(structured, "<agent-code>", "exec")
                        changes.append("expanded structured inline try/if/else statements")
                        return structured, changes, None
                    except SyntaxError as third_exc:
                        return code, changes, {
                            "first": syntax_error_summary(first_exc),
                            "after_inline_repair": syntax_error_summary(second_exc),
                            "after_structured_repair": syntax_error_summary(third_exc),
                        }
                return code, changes, {
                    "first": syntax_error_summary(first_exc),
                    "after_inline_repair": syntax_error_summary(second_exc),
                }
        return code, changes, {"first": syntax_error_summary(first_exc)}


def run_ruff_rewrite(path, cwd, env, enabled=True):
    result = {
        "enabled": bool(enabled),
        "available": False,
        "changed": False,
        "check": None,
        "format": None,
    }
    if not enabled:
        return result
    ruff_bin = shutil.which("ruff", path=env.get("PATH", os.environ.get("PATH", "")))
    if not ruff_bin:
        return result
    result["available"] = True
    before = pathlib.Path(path).read_text(encoding="utf-8")
    for name, args in (
        ("check", [ruff_bin, "check", "--fix", "--exit-zero", str(path)]),
        ("format", [ruff_bin, "format", str(path)]),
    ):
        start = time.time()
        try:
            proc = subprocess.run(
                args,
                cwd=cwd,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=5,
            )
            result[name] = {
                "ok": proc.returncode == 0,
                "rc": proc.returncode,
                "stdout": proc.stdout[-4000:],
                "stderr": proc.stderr[-4000:],
                "elapsed_ms": int((time.time() - start) * 1000),
            }
        except Exception as exc:
            result[name] = {
                "ok": False,
                "error": str(exc),
                "elapsed_ms": int((time.time() - start) * 1000),
            }
    after = pathlib.Path(path).read_text(encoding="utf-8")
    result["changed"] = before != after
    return result


def truthy_setting(value, default=True):
    if value is None:
        return default
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "on", "auto", "always"):
        return True
    if text in ("0", "false", "no", "off", "none", "never", "disabled"):
        return False
    return default


def qwen_repair_mode(data):
    raw = data.get("qwen_repair", data.get("use_qwen_repair", data.get("qwen", QWEN_REPAIR_DEFAULT)))
    text = str(raw).strip().lower()
    if text in ("always", "force"):
        return "always"
    if text in ("0", "false", "no", "off", "none", "never", "disabled"):
        return "off"
    if not QWEN_REPAIR_URL:
        return "off"
    return "auto"


def call_qwen_code_repair(code, mode="auto", timeout=45, max_new_tokens=320):
    result = {
        "enabled": mode != "off",
        "mode": mode,
        "url": QWEN_REPAIR_URL,
        "called": False,
        "ok": False,
        "elapsed_ms": None,
        "generation_ms": None,
        "error": None,
        "code_chars": None,
    }
    if mode == "off" or not QWEN_REPAIR_URL:
        return result
    start = time.time()
    payload = {
        "code": code,
        "max_new_tokens": int(max(64, min(int(max_new_tokens or 320), 768))),
        "instruction": (
            "Repair this Python script so it parses and preserves the intended behavior. "
            "Return only corrected Python code."
        ),
    }
    try:
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            QWEN_REPAIR_URL,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        result["called"] = True
        with urllib.request.urlopen(req, timeout=max(5, float(timeout))) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
        data = json.loads(raw or "{}")
        result["response"] = {key: data.get(key) for key in ("ok", "generation_ms", "threads", "model", "dtype", "device")}
        result["generation_ms"] = data.get("generation_ms")
        repaired = str(data.get("code") or "").strip()
        if not data.get("ok") or not repaired:
            result["error"] = data.get("error") or "qwen returned no repaired code"
            return result
        try:
            compile(repaired, "<qwen-repaired-code>", "exec")
        except SyntaxError as exc:
            result["error"] = "qwen output did not compile"
            result["syntax_error"] = syntax_error_summary(exc)
            result["code_chars"] = len(repaired)
            return result
        result["ok"] = True
        result["code_chars"] = len(repaired)
        result["code"] = repaired
        return result
    except Exception as exc:
        result["error"] = str(exc)
        return result
    finally:
        result["elapsed_ms"] = int((time.time() - start) * 1000)


def codeact_contract():
    return {
        "ok": True,
        "service": "nubian-codeact",
        "description": "Minimal CodeAct interface: screenshot plus one full-control Python endpoint.",
        "screenshot": "GET /codeact/screenshot",
        "python": "POST /codeact/python",
        "ocr": "POST /codeact/ocr",
        "body": "Raw Python, fenced ```python``` code, <code>...</code>, or JSON {\"code\":\"...\",\"timeout_ms\":10000,\"requirements\":[]}",
        "ocr_body": "{\"bbox\":[x1,y1,x2,y2],\"target_text\":\"Accept all\",\"action\":\"click\"}",
        "coordinate_space": "framebuffer pixels from the returned screenshot; valid x/y are 0..width-1 and 0..height-1",
        "preloaded": [
            "pyautogui",
            "requests",
            "websocket",
            "cdp_base",
            "cdp_url(path)",
            "cdp_get(path='/json/version')",
            "cdp_post(path, payload=None)",
            "time",
            "os",
            "pathlib",
            "subprocess",
            "json",
            "math",
            "sys",
            "shutil",
        ],
        "notes": [
            "pyautogui.FAILSAFE is disabled for agent reliability.",
            "pyautogui mouse coordinates are normalized to framebuffer/screenshot pixels by the prelude.",
            "Default CDP base is http://127.0.0.1:9222 inside the guest.",
            "Use GET /codeact/screenshot for observations; do not call pyautogui.screenshot() in model code.",
            "The server strips language-label wrappers, repairs common inline compound syntax, and runs Ruff fix/format when available.",
            "If code still does not parse, Qwen code repair runs as a removable layer: qwen_repair='auto' (default), 'always', or false.",
            "Runtime errors return JSON with ok=false instead of HTTP 500 so the agent can inspect stderr and retry.",
            "Set target/target_region/target_action/target_text at the end of the code block to request OCR-driven action after exec.",
        ],
        "example": "POST /codeact/python {\"code\":\"target='Hyväksy kaikki'\\ntarget_region=[520,900,1500,1060]\\ntarget_action='click'\"}",
    }


def python_prelude(mode="pyautogui"):
    common = f"""\
import json
import math
import os
import pathlib
import shutil
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

os.environ.setdefault("DISPLAY", {json.dumps(os.environ.get("DISPLAY", DISPLAY))})
os.environ.setdefault("XDG_RUNTIME_DIR", {json.dumps(os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000"))})
os.environ.setdefault("DBUS_SESSION_BUS_ADDRESS", {json.dumps(os.environ.get("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus"))})

target = None
target_region = None
target_action = None
target_text = None

computer_agent_base = os.environ.get("COMPUTER_AGENT_BASE", "http://127.0.0.1:6090").rstrip("/")

def _computer_agent_post(path, payload, timeout=30):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        computer_agent_base + path,
        data=body,
        headers={{"Content-Type": "application/json"}},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except Exception:
            return {{"ok": False, "error": raw or str(exc), "status": exc.code}}

"""
    cdp_helpers = """\
try:
    import requests
except Exception:
    requests = None
try:
    import websocket
except Exception:
    websocket = None

cdp_base = os.environ.get("CDP_BASE", "http://127.0.0.1:9222").rstrip("/")

def cdp_url(path=""):
    path = str(path or "")
    if path.startswith("http://") or path.startswith("https://"):
        return path
    if not path.startswith("/"):
        path = "/" + path
    return cdp_base + path

def cdp_request(path="/json/version", payload=None, method=None, timeout=5, headers=None):
    data = None
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        method = method or "POST"
    if requests is not None:
        response = requests.request(method or "GET", cdp_url(path), data=data, headers=hdrs, timeout=timeout)
        response.raise_for_status()
        try:
            return response.json()
        except Exception:
            return response.text
    req = urllib.request.Request(cdp_url(path), data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        text = raw.decode("utf-8", errors="replace")
        content_type = resp.headers.get("content-type", "")
        if "json" in content_type or text.lstrip().startswith(("{", "[")):
            return json.loads(text)
        return text

def cdp_get(path="/json/version", timeout=5):
    return cdp_request(path, method="GET", timeout=timeout)

def cdp_post(path, payload=None, timeout=5):
    return cdp_request(path, payload=payload, method="POST", timeout=timeout)

computer_agent_base = os.environ.get("COMPUTER_AGENT_BASE", "http://127.0.0.1:6090").rstrip("/")

def _computer_agent_post(path, payload, timeout=30):
    body = json.dumps(payload).encode("utf-8")
    url = computer_agent_base + path
    if requests is not None:
        response = requests.post(url, json=payload, timeout=timeout)
        try:
            return response.json()
        except Exception:
            data = {"ok": False, "error": response.text, "status": response.status_code}
        if response.status_code >= 400:
            return data
        return data
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except Exception:
            return {"ok": False, "error": raw or str(exc), "status": exc.code}

"""
    pyautogui_helpers = """\
import pyautogui
pyautogui.PAUSE = 0
pyautogui.FAILSAFE = False

_nubian_pyautogui_raw = {
    "click": pyautogui.click,
    "doubleClick": pyautogui.doubleClick,
    "rightClick": pyautogui.rightClick,
    "middleClick": pyautogui.middleClick,
    "moveTo": pyautogui.moveTo,
    "mouseDown": pyautogui.mouseDown,
    "mouseUp": pyautogui.mouseUp,
    "dragTo": pyautogui.dragTo,
    "scroll": pyautogui.scroll,
    "size": pyautogui.size,
    "position": pyautogui.position,
}

_nubian_coord_cache = {"t": 0.0, "fb_w": None, "fb_h": None, "display_w": None, "display_h": None}

def _computer_agent_get(path, timeout=3):
    req = urllib.request.Request(computer_agent_base + path, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="replace"))

def _nubian_coord_meta():
    now = time.time()
    if _nubian_coord_cache.get("fb_w") and now - float(_nubian_coord_cache.get("t") or 0) <= 1.0:
        return _nubian_coord_cache
    try:
        data = _computer_agent_get("/eyes/framebuffer", timeout=3)
        fb = data.get("framebuffer") or {}
        display = data.get("display_geometry") or {}
        _nubian_coord_cache.update({
            "t": now,
            "fb_w": int(fb.get("width") or display.get("width") or _nubian_pyautogui_raw["size"]()[0]),
            "fb_h": int(fb.get("height") or display.get("height") or _nubian_pyautogui_raw["size"]()[1]),
            "display_w": int(display.get("width") or fb.get("width") or _nubian_pyautogui_raw["size"]()[0]),
            "display_h": int(display.get("height") or fb.get("height") or _nubian_pyautogui_raw["size"]()[1]),
        })
    except Exception:
        width, height = _nubian_pyautogui_raw["size"]()
        _nubian_coord_cache.update({"t": now, "fb_w": int(width), "fb_h": int(height), "display_w": int(width), "display_h": int(height)})
    return _nubian_coord_cache

def _nubian_clamp(value, max_value):
    return max(0, min(int(round(float(value))), int(max_value) - 1))

def framebuffer_to_display_xy(x, y):
    meta = _nubian_coord_meta()
    fb_w, fb_h = int(meta["fb_w"]), int(meta["fb_h"])
    display_w, display_h = int(meta["display_w"]), int(meta["display_h"])
    fx, fy = _nubian_clamp(x, fb_w), _nubian_clamp(y, fb_h)
    if fb_w == display_w and fb_h == display_h:
        return fx, fy
    dx = 0 if fb_w <= 1 else round(fx * (display_w - 1) / (fb_w - 1))
    dy = 0 if fb_h <= 1 else round(fy * (display_h - 1) / (fb_h - 1))
    return _nubian_clamp(dx, display_w), _nubian_clamp(dy, display_h)

def display_to_framebuffer_xy(x, y):
    meta = _nubian_coord_meta()
    fb_w, fb_h = int(meta["fb_w"]), int(meta["fb_h"])
    display_w, display_h = int(meta["display_w"]), int(meta["display_h"])
    dx, dy = _nubian_clamp(x, display_w), _nubian_clamp(y, display_h)
    if fb_w == display_w and fb_h == display_h:
        return dx, dy
    fx = 0 if display_w <= 1 else round(dx * (fb_w - 1) / (display_w - 1))
    fy = 0 if display_h <= 1 else round(dy * (fb_h - 1) / (display_h - 1))
    return _nubian_clamp(fx, fb_w), _nubian_clamp(fy, fb_h)

def _nubian_patch_xy_args(args, kwargs, x_index=0, y_index=1):
    args = list(args)
    kwargs = dict(kwargs)
    x = kwargs.get("x") if "x" in kwargs else (args[x_index] if len(args) > x_index else None)
    y = kwargs.get("y") if "y" in kwargs else (args[y_index] if len(args) > y_index else None)
    if x is None or y is None:
        return tuple(args), kwargs
    dx, dy = framebuffer_to_display_xy(x, y)
    if "x" in kwargs:
        kwargs["x"] = dx
    elif len(args) > x_index:
        args[x_index] = dx
    else:
        kwargs["x"] = dx
    if "y" in kwargs:
        kwargs["y"] = dy
    elif len(args) > y_index:
        args[y_index] = dy
    else:
        kwargs["y"] = dy
    return tuple(args), kwargs

def _nubian_xy_from_args(args, kwargs, x_index=0, y_index=1):
    x = kwargs.get("x") if "x" in kwargs else (args[x_index] if len(args) > x_index else None)
    y = kwargs.get("y") if "y" in kwargs else (args[y_index] if len(args) > y_index else None)
    if x is None or y is None:
        return None
    return x, y

def _nubian_bridge_to_display(x, y):
    if os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE", "true").strip().lower() in ("0", "false", "no", "off"):
        return
    try:
        x, y = int(round(float(x))), int(round(float(y)))
        cur_x, cur_y = _nubian_pyautogui_raw["position"]()
        if (int(cur_x), int(cur_y)) == (x, y):
            return
        _nubian_pyautogui_raw["moveTo"](cur_x, cur_y, duration=0)
        pause = max(0.0, float(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_ANCHOR_PAUSE", "0.08")))
        if pause:
            time.sleep(pause)
        duration = max(0.0, float(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_MOVE_DURATION", "0.12")))
        route = os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_ROUTE", "top_edge").strip().lower()
        if route in ("top", "top_edge", "edge", "outside"):
            meta = _nubian_coord_meta()
            edge_y = _nubian_clamp(os.environ.get("COMPUTER_AGENT_POINTER_BRIDGE_EDGE_Y", "0"), int(meta["display_h"]))
            segment = duration / 3.0 if duration else 0
            _nubian_pyautogui_raw["moveTo"](cur_x, edge_y, duration=segment)
            _nubian_pyautogui_raw["moveTo"](x, edge_y, duration=segment)
            _nubian_pyautogui_raw["moveTo"](x, y, duration=segment)
        elif route in ("bottom", "bottom_edge", "under"):
            meta = _nubian_coord_meta()
            edge_y = int(meta["display_h"]) - 1
            _nubian_pyautogui_raw["moveTo"](x, edge_y, duration=0)
            _nubian_pyautogui_raw["moveTo"](x, y, duration=duration)
        elif route in ("right", "right_edge", "side"):
            meta = _nubian_coord_meta()
            edge_x = int(meta["display_w"]) - 1
            segment = duration / 3.0 if duration else 0
            _nubian_pyautogui_raw["moveTo"](edge_x, cur_y, duration=segment)
            _nubian_pyautogui_raw["moveTo"](edge_x, y, duration=segment)
            _nubian_pyautogui_raw["moveTo"](x, y, duration=segment)
        else:
            _nubian_pyautogui_raw["moveTo"](x, y, duration=duration)
    except Exception:
        pass

def _nubian_pos_arg(args, index, default=None):
    return args[index] if len(args) > index and args[index] is not None else default

def _nubian_human_click(name, target, patched_args, patched_kwargs):
    if os.environ.get("COMPUTER_AGENT_HUMAN_CLICK", "true").strip().lower() in ("0", "false", "no", "off"):
        return False
    if name == "click":
        clicks = patched_kwargs.get("clicks", _nubian_pos_arg(patched_args, 2, 1))
        interval = patched_kwargs.get("interval", _nubian_pos_arg(patched_args, 3, os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_INTERVAL", "0.05")))
        button = patched_kwargs.get("button", _nubian_pos_arg(patched_args, 4, "left"))
    elif name == "doubleClick":
        clicks = 2
        interval = patched_kwargs.get("interval", _nubian_pos_arg(patched_args, 2, os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_INTERVAL", "0.05")))
        button = patched_kwargs.get("button", _nubian_pos_arg(patched_args, 3, "left"))
    elif name == "rightClick":
        clicks = 1
        interval = os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_INTERVAL", "0.05")
        button = "right"
    elif name == "middleClick":
        clicks = 1
        interval = os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_INTERVAL", "0.05")
        button = "middle"
    else:
        return False
    try:
        clicks = max(1, int(clicks or 1))
    except Exception:
        clicks = 1
    try:
        interval = max(0.0, float(interval or 0))
    except Exception:
        interval = 0.0
    pause = max(0.0, float(os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_MOVE_PAUSE", "0.20")))
    hold = max(0.0, float(os.environ.get("COMPUTER_AGENT_HUMAN_CLICK_HOLD_SECONDS", "0.35")))
    if target is not None:
        _nubian_pyautogui_raw["moveTo"](target[0], target[1], duration=0)
    if pause:
        time.sleep(pause)
    for index in range(clicks):
        _nubian_pyautogui_raw["mouseDown"](button=button)
        if hold:
            time.sleep(hold)
        _nubian_pyautogui_raw["mouseUp"](button=button)
        if interval and index < clicks - 1:
            time.sleep(interval)
    return True

def _nubian_wrap_xy(name, x_index=0, y_index=1):
    raw = _nubian_pyautogui_raw[name]
    def wrapped(*args, **kwargs):
        patched_args, patched_kwargs = _nubian_patch_xy_args(args, kwargs, x_index=x_index, y_index=y_index)
        target = _nubian_xy_from_args(patched_args, patched_kwargs, x_index=x_index, y_index=y_index)
        if target is not None:
            _nubian_bridge_to_display(*target)
        if _nubian_human_click(name, target, patched_args, patched_kwargs):
            return None
        return raw(*patched_args, **patched_kwargs)
    return wrapped

def _nubian_size():
    meta = _nubian_coord_meta()
    return int(meta["fb_w"]), int(meta["fb_h"])

def _nubian_position(*args, **kwargs):
    x, y = _nubian_pyautogui_raw["position"](*args, **kwargs)
    return display_to_framebuffer_xy(x, y)

if os.environ.get("COMPUTER_AGENT_PYAUTOGUI_FRAMEBUFFER", "true").strip().lower() not in ("0", "false", "no", "off"):
    pyautogui.click = _nubian_wrap_xy("click")
    pyautogui.doubleClick = _nubian_wrap_xy("doubleClick")
    pyautogui.rightClick = _nubian_wrap_xy("rightClick")
    pyautogui.middleClick = _nubian_wrap_xy("middleClick")
    pyautogui.moveTo = _nubian_wrap_xy("moveTo")
    pyautogui.mouseDown = _nubian_wrap_xy("mouseDown")
    pyautogui.mouseUp = _nubian_wrap_xy("mouseUp")
    pyautogui.dragTo = _nubian_wrap_xy("dragTo")
    pyautogui.scroll = _nubian_wrap_xy("scroll", x_index=1, y_index=2)
    pyautogui.size = _nubian_size
    pyautogui.position = _nubian_position

"""
    if mode == "cdp":
        return common + cdp_helpers
    if mode == "codeact":
        return common + pyautogui_helpers + cdp_helpers
    return common + pyautogui_helpers


def public_agent_base_url():
    path = PUBLIC_AGENT_BASE_PATH.strip()
    if path and not path.startswith("/"):
        path = "/" + path
    return f"http://{PUBLIC_HOST}:{PUBLIC_AGENT_PORT}{path}"


def import_pyautogui():
    if IS_LINUX:
        os.environ.setdefault("DISPLAY", DISPLAY)
    import pyautogui

    pyautogui.PAUSE = 0
    pyautogui.FAILSAFE = False
    return pyautogui


def current_mouse_location_display():
    if VNC_BACKEND_ENABLED:
        if LAST_POINTER_EVENT.get("x") is None or LAST_POINTER_EVENT.get("y") is None:
            return None
        return int(LAST_POINTER_EVENT["x"]), int(LAST_POINTER_EVENT["y"])
    if IS_WINDOWS:
        try:
            pos = import_pyautogui().position()
            return int(pos[0]), int(pos[1])
        except Exception:
            return None
    if not shutil.which("xdotool"):
        return None
    try:
        result = subprocess.run(
            ["xdotool", "getmouselocation"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=1,
            env=platform_env(),
        )
        if result.returncode != 0:
            return None
        match = re.search(r"x:(-?\d+)\s+y:(-?\d+)", result.stdout)
        if not match:
            return None
        return int(match.group(1)), int(match.group(2))
    except Exception:
        return None


def current_mouse_location():
    point = current_mouse_location_display()
    if not point:
        return None
    return display_to_framebuffer_point(*point)


XINPUT_DEVICE_LIST_RE = re.compile(r"(.+?)\s+id=(\d+)\s+\[([^\]]+)\]")


def xinput_device_map():
    if IS_WINDOWS:
        return {}
    if not shutil.which("xinput"):
        return {}
    try:
        result = subprocess.run(
            ["xinput", "--list", "--short"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=1,
            env=platform_env(),
        )
        if result.returncode != 0:
            return {}
    except Exception:
        return {}

    devices = {}
    for raw_line in result.stdout.splitlines():
        line = re.sub(r"^[^\w@./+-]+", "", raw_line.strip()).lstrip("↳ ").strip()
        match = XINPUT_DEVICE_LIST_RE.search(line)
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


def attach_xinput_device_info(event, devices):
    device_id = event.get("device")
    try:
        device_id = int(device_id)
    except Exception:
        return event
    info = devices.get(device_id)
    if not info:
        return event
    for target, source in (
        ("device_name", "name"),
        ("device_use", "use"),
        ("device_role", "role"),
        ("device_master", "master"),
        ("device_descriptor", "descriptor"),
    ):
        if event.get(target) in (None, ""):
            event[target] = info.get(source)
    return event


def timestamp_fields(t):
    try:
        seconds = float(t)
    except Exception:
        seconds = time.time()
    unix_ms = int(round(seconds * 1000))
    whole = unix_ms // 1000
    millis = unix_ms % 1000
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(whole)) + f".{millis:03d}Z"
    return {"t": seconds, "ts_unix_ms": unix_ms, "timestamp": timestamp}


def remember_pointer_event(kind, x=None, y=None):
    if x is None or y is None:
        point = current_mouse_location()
        if not point:
            return
        x, y = point
    try:
        LAST_POINTER_EVENT.update({"kind": str(kind), "x": int(x), "y": int(y), "t": time.time()})
    except Exception:
        pass


def pointer_bridge_to(pyautogui, x, y, duration=None, anchor_pause=None, max_age=None):
    if not POINTER_BRIDGE_ENABLED:
        return None
    try:
        target_x, target_y = clamp_framebuffer_point(x, y)
        route = POINTER_BRIDGE_ROUTE
        total_duration = max(0.0, float(POINTER_BRIDGE_MOVE_DURATION if duration is None else duration))
        if route in ("bottom", "bottom_edge", "under"):
            fb_w, fb_h = framebuffer_size()
            edge_x, edge_y = clamp_point(target_x, fb_h - 1, fb_w, fb_h)
            edge_dx, edge_dy = framebuffer_to_display_point(edge_x, edge_y)
            target_dx, target_dy = framebuffer_to_display_point(target_x, target_y)
            pyautogui.moveTo(edge_dx, edge_dy, duration=0)
            pause = max(0.0, float(POINTER_BRIDGE_ANCHOR_PAUSE if anchor_pause is None else anchor_pause))
            if pause:
                time.sleep(pause)
            pyautogui.moveTo(target_dx, target_dy, duration=total_duration)
            return {
                "from": None,
                "to": [target_x, target_y],
                "display_to": [target_dx, target_dy],
                "route": route,
                "path": [[edge_x, edge_y], [target_x, target_y]],
                "age_s": None,
            }
        now = time.time()
        last_x = LAST_POINTER_EVENT.get("x")
        last_y = LAST_POINTER_EVENT.get("y")
        last_t = float(LAST_POINTER_EVENT.get("t") or 0)
        age = now - last_t if last_t else None
        if last_x is None or last_y is None or (age is not None and age > float(max_age or POINTER_BRIDGE_MAX_AGE)):
            point = current_mouse_location()
            if not point:
                return None
            last_x, last_y = point
            age = None
        last_x, last_y = clamp_framebuffer_point(last_x, last_y)
        if (last_x, last_y) == (target_x, target_y):
            return {
                "from": [last_x, last_y],
                "to": [target_x, target_y],
                "skipped": "same_point",
                "age_s": age,
            }
        last_dx, last_dy = framebuffer_to_display_point(last_x, last_y)
        target_dx, target_dy = framebuffer_to_display_point(target_x, target_y)
        pyautogui.moveTo(last_dx, last_dy, duration=0)
        pause = max(0.0, float(POINTER_BRIDGE_ANCHOR_PAUSE if anchor_pause is None else anchor_pause))
        if pause:
            time.sleep(pause)
        path = [[last_x, last_y], [target_x, target_y]]
        if route in ("top", "top_edge", "edge", "outside"):
            fb_w, fb_h = framebuffer_size()
            edge_x1, edge_y = clamp_point(last_x, POINTER_BRIDGE_EDGE_Y, fb_w, fb_h)
            edge_x2, edge_y2 = clamp_point(target_x, POINTER_BRIDGE_EDGE_Y, fb_w, fb_h)
            edge_y = edge_y2 = min(edge_y, edge_y2)
            path = [[last_x, last_y], [edge_x1, edge_y], [edge_x2, edge_y2], [target_x, target_y]]
            segment_duration = total_duration / 3.0 if total_duration else 0
            for px, py in path[1:]:
                pdx, pdy = framebuffer_to_display_point(px, py)
                pyautogui.moveTo(pdx, pdy, duration=segment_duration)
        elif route in ("right", "right_edge", "side"):
            fb_w, fb_h = framebuffer_size()
            edge_x = fb_w - 1
            edge_x1, edge_y1 = clamp_point(edge_x, last_y, fb_w, fb_h)
            edge_x2, edge_y2 = clamp_point(edge_x, target_y, fb_w, fb_h)
            path = [[last_x, last_y], [edge_x1, edge_y1], [edge_x2, edge_y2], [target_x, target_y]]
            segment_duration = total_duration / 3.0 if total_duration else 0
            for px, py in path[1:]:
                pdx, pdy = framebuffer_to_display_point(px, py)
                pyautogui.moveTo(pdx, pdy, duration=segment_duration)
        else:
            pyautogui.moveTo(target_dx, target_dy, duration=total_duration)
        return {
            "from": [last_x, last_y],
            "to": [target_x, target_y],
            "display_from": [last_dx, last_dy],
            "display_to": [target_dx, target_dy],
            "route": route,
            "path": path,
            "age_s": age,
        }
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def clamp_point(x, y, width, height):
    return max(0, min(int(round(float(x))), int(width) - 1)), max(0, min(int(round(float(y))), int(height) - 1))


def remember_framebuffer_size(width, height):
    try:
        _FRAMEBUFFER_SIZE_CACHE.update({"t": time.time(), "width": int(width), "height": int(height)})
    except Exception:
        pass


def framebuffer_size(cache_seconds=1.0):
    now = time.time()
    cached_w = _FRAMEBUFFER_SIZE_CACHE.get("width")
    cached_h = _FRAMEBUFFER_SIZE_CACHE.get("height")
    if cached_w and cached_h and now - float(_FRAMEBUFFER_SIZE_CACHE.get("t") or 0) <= float(cache_seconds):
        return int(cached_w), int(cached_h)
    try:
        image = capture_framebuffer_image()
        remember_framebuffer_size(image.size[0], image.size[1])
        return int(image.size[0]), int(image.size[1])
    except Exception:
        return display_geometry()


def clamp_framebuffer_point(x, y):
    fb_w, fb_h = framebuffer_size()
    return clamp_point(x, y, fb_w, fb_h)


def clamp_display_point(x, y):
    display_w, display_h = display_geometry()
    return clamp_point(x, y, display_w, display_h)


def framebuffer_to_display_point(x, y):
    if ACTION_COORDINATE_SPACE not in ("framebuffer", "fb", "screenshot", "image"):
        return clamp_display_point(x, y)
    fb_w, fb_h = framebuffer_size()
    display_w, display_h = display_geometry()
    fx, fy = clamp_point(x, y, fb_w, fb_h)
    if fb_w == display_w and fb_h == display_h:
        return fx, fy
    dx = 0 if fb_w <= 1 else round(fx * (display_w - 1) / (fb_w - 1))
    dy = 0 if fb_h <= 1 else round(fy * (display_h - 1) / (fb_h - 1))
    return clamp_point(dx, dy, display_w, display_h)


def display_to_framebuffer_point(x, y):
    if ACTION_COORDINATE_SPACE not in ("framebuffer", "fb", "screenshot", "image"):
        return clamp_display_point(x, y)
    fb_w, fb_h = framebuffer_size()
    display_w, display_h = display_geometry()
    dx, dy = clamp_point(x, y, display_w, display_h)
    if fb_w == display_w and fb_h == display_h:
        return dx, dy
    fx = 0 if display_w <= 1 else round(dx * (fb_w - 1) / (display_w - 1))
    fy = 0 if display_h <= 1 else round(dy * (fb_h - 1) / (display_h - 1))
    return clamp_point(fx, fy, fb_w, fb_h)


def clamp_screen_point(x, y):
    return clamp_framebuffer_point(x, y)


def image_crop_delta(before, after, box):
    from PIL import ImageChops, ImageStat

    b = before.crop(box).convert("RGB")
    a = after.crop(box).convert("RGB")
    diff = ImageChops.difference(b, a)
    stat = ImageStat.Stat(diff)
    mean = float(sum(stat.mean) / 3.0)
    changed = []
    for index, pixel in enumerate(diff.getdata()):
        if pixel != (0, 0, 0):
            changed.append(index)
    nonzero = len(changed)
    total = max(1, diff.size[0] * diff.size[1])
    result = {
        "mean_abs_delta_0_255": round(mean, 4),
        "changed_pixels": int(nonzero),
        "total_pixels": int(total),
        "changed_pct": round(nonzero * 100.0 / total, 4),
    }
    if changed:
        width = diff.size[0]
        xs = [idx % width for idx in changed]
        ys = [idx // width for idx in changed]
        x1 = int(min(xs) + box[0])
        y1 = int(min(ys) + box[1])
        x2 = int(max(xs) + box[0] + 1)
        y2 = int(max(ys) + box[1] + 1)
        cx, cy = clamp_framebuffer_point((x1 + x2) / 2.0, (y1 + y2) / 2.0)
        result["changed_bbox"] = [x1, y1, x2, y2]
        result["changed_center"] = [int(cx), int(cy)]
    return result


def atspi_center_snap(x, y, enabled=None):
    enabled = CENTER_SNAP_ENABLED if enabled is None else bool(enabled)
    if not enabled or IS_WINDOWS:
        return None
    try:
        import pyatspi

        fx, fy = clamp_framebuffer_point(x, y)
        dx, dy = framebuffer_to_display_point(fx, fy)
        desktop = pyatspi.Registry.getDesktop(0)
        chain = []
        visited = set()

        def descend(obj, depth=0):
            if obj is None or depth > 20:
                return
            key = repr(obj)
            if key in visited:
                return
            visited.add(key)
            chain.append(obj)
            try:
                component = obj.queryComponent()
                child = component.getAccessibleAtPoint(int(dx), int(dy), pyatspi.DESKTOP_COORDS)
            except Exception:
                child = None
            if child is not None and repr(child) not in visited:
                descend(child, depth + 1)

        def safe(fn, default=None):
            try:
                value = fn()
                return default if value is None else value
            except Exception:
                return default

        descend(desktop)
        clickable_roles = {
            "push button",
            "toggle button",
            "radio button",
            "check box",
            "menu item",
            "menu",
            "page tab",
            "combo box",
            "spin button",
            "slider",
            "scroll bar",
        }
        skip_roles = {
            "application",
            "frame",
            "window",
            "dialog",
            "panel",
            "filler",
            "image",
            "document frame",
            "layered pane",
        }
        for obj in reversed(chain):
            role = str(safe(lambda obj=obj: obj.getRoleName(), "") or "").strip().lower()
            name = str(safe(lambda obj=obj: obj.name, "") or "")
            if role in skip_roles:
                continue
            action_iface = safe(lambda obj=obj: obj.queryAction(), None)
            has_action = bool(action_iface and int(safe(lambda: action_iface.nActions, 0) or 0) > 0)
            if role not in clickable_roles and not has_action:
                continue
            component = safe(lambda obj=obj: obj.queryComponent(), None)
            if not component:
                continue
            extents = safe(lambda: component.getExtents(pyatspi.DESKTOP_COORDS), None)
            if not extents:
                continue
            ex = int(getattr(extents, "x", 0))
            ey = int(getattr(extents, "y", 0))
            ew = int(getattr(extents, "width", 0))
            eh = int(getattr(extents, "height", 0))
            if ew < 6 or eh < 6 or ew > 900 or eh > 300:
                continue
            if not (ex - 2 <= dx <= ex + ew + 2 and ey - 2 <= dy <= ey + eh + 2):
                continue
            cx, cy = display_to_framebuffer_point(ex + ew / 2.0, ey + eh / 2.0)
            if (cx, cy) == (fx, fy):
                return None
            return {
                "from": [int(fx), int(fy)],
                "to": [int(cx), int(cy)],
                "role": role,
                "name": name,
                "bbox": [int(ex), int(ey), int(ew), int(eh)],
                "reason": "atspi_clickable_center",
            }
    except Exception as exc:
        return {"ok": False, "error": str(exc)}
    return None


def hover_probe(pyautogui, x, y, pad_x=None, pad_y=None, wait_seconds=None, enabled=None):
    enabled = HOVER_PROBE_ENABLED if enabled is None else bool(enabled)
    if not enabled:
        return None
    try:
        fx, fy = clamp_framebuffer_point(x, y)
        dx, dy = framebuffer_to_display_point(fx, fy)
        fb_w, fb_h = framebuffer_size()
        pad_x = int(HOVER_PROBE_PAD_X if pad_x is None else pad_x)
        pad_y = int(HOVER_PROBE_PAD_Y if pad_y is None else pad_y)
        box = (
            max(0, fx - pad_x),
            max(0, fy - pad_y),
            min(fb_w, fx + pad_x),
            min(fb_h, fy + pad_y),
        )
        away = None
        if HOVER_PROBE_MOVE_AWAY:
            away_y = fy + (pad_y * 2)
            if away_y >= fb_h:
                away_y = fy - (pad_y * 2)
            away_x, away_y = clamp_framebuffer_point(fx, away_y)
            if (away_x, away_y) != (fx, fy):
                away_dx, away_dy = framebuffer_to_display_point(away_x, away_y)
                pyautogui.moveTo(away_dx, away_dy, duration=0)
                if wait_seconds is None:
                    time.sleep(min(0.12, max(0.03, HOVER_PROBE_WAIT_SECONDS / 2.0)))
                away = [int(away_x), int(away_y)]
        before = capture_framebuffer_image().convert("RGB")
        pyautogui.moveTo(dx, dy, duration=0)
        wait = max(0.0, float(HOVER_PROBE_WAIT_SECONDS if wait_seconds is None else wait_seconds))
        if wait:
            time.sleep(wait)
        after = capture_framebuffer_image().convert("RGB")
        result = image_crop_delta(before, after, box)
        result.update(
            {
                "ok": True,
                "target": [int(fx), int(fy)],
                "display_target": [int(dx), int(dy)],
                "crop_box": [int(v) for v in box],
                "away": away,
                "responsive": bool(result["changed_pct"] >= HOVER_PROBE_CHANGED_PCT_THRESHOLD),
                "threshold_changed_pct": float(HOVER_PROBE_CHANGED_PCT_THRESHOLD),
            }
        )
        return result
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def hover_probe_and_snap(pyautogui, x, y, enabled=None):
    enabled = HOVER_SNAP_ENABLED if enabled is None else bool(enabled)
    fx, fy = clamp_framebuffer_point(x, y)
    probe = hover_probe(pyautogui, fx, fy)
    snap = None
    if not enabled or not probe:
        return fx, fy, probe, snap
    if probe.get("responsive"):
        center = probe.get("changed_center")
        if center:
            cx, cy = clamp_framebuffer_point(center[0], center[1])
            if (cx, cy) != (fx, fy):
                snap = {
                    "from": [int(fx), int(fy)],
                    "to": [int(cx), int(cy)],
                    "offset": [int(cx - fx), int(cy - fy)],
                    "reason": "hover_changed_region_center",
                    "probe": probe,
                }
                return int(cx), int(cy), probe, snap
        return fx, fy, probe, snap

    # Cheap local search for menu-row/button-center misses. Vertical candidates
    # come first because grounded menu text often lands on the row edge.
    offsets = [
        (0, 20),
        (0, 14),
        (0, 28),
        (0, -20),
        (0, -14),
        (20, 0),
        (-20, 0),
        (0, 36),
        (0, -28),
    ]
    best = None
    for ox, oy in offsets:
        cx, cy = clamp_framebuffer_point(fx + ox, fy + oy)
        candidate = hover_probe(pyautogui, cx, cy)
        if not candidate or not candidate.get("ok"):
            continue
        target_x, target_y = cx, cy
        if candidate.get("changed_center"):
            target_x, target_y = clamp_framebuffer_point(candidate["changed_center"][0], candidate["changed_center"][1])
        if best is None or float(candidate.get("changed_pct", 0.0)) > float(best.get("probe", {}).get("changed_pct", 0.0)):
            best = {"x": target_x, "y": target_y, "offset": [target_x - fx, target_y - fy], "probe": candidate}
        if candidate.get("responsive"):
            break
    if best and best["probe"].get("responsive"):
        snap = {
            "from": [int(fx), int(fy)],
            "to": [int(best["x"]), int(best["y"])],
            "offset": best["offset"],
            "reason": "nearby_hover_responsive_point",
            "probe": best["probe"],
        }
        return int(best["x"]), int(best["y"]), probe, snap
    return fx, fy, probe, snap


def fat_click_points(x, y, radius=None):
    radius = int(FAT_CLICK_RADIUS if radius is None else radius)
    cx, cy = clamp_framebuffer_point(x, y)
    if radius <= 0:
        return [(cx, cy)]
    candidates = [
        (cx, cy),
        (cx + radius, cy),
        (cx - radius, cy),
        (cx, cy + radius),
        (cx, cy - radius),
    ]
    points = []
    seen = set()
    for px, py in candidates:
        point = clamp_framebuffer_point(px, py)
        if point not in seen:
            seen.add(point)
            points.append(point)
    return points


def pyautogui_human_click(
    pyautogui,
    x=None,
    y=None,
    clicks=1,
    button="left",
    move_pause=None,
    hold_seconds=None,
    interval=None,
    enabled=None,
):
    enabled = HUMAN_CLICK_ENABLED if enabled is None else bool(enabled)
    clicks = max(1, int(clicks or 1))
    button = str(button or "left")
    if not enabled:
        kwargs = {"clicks": clicks, "button": button}
        if x is not None and y is not None:
            kwargs.update({"x": x, "y": y})
        pyautogui.click(**kwargs)
        return

    if x is not None and y is not None:
        pyautogui.moveTo(x, y, duration=0)
    pause = max(0.0, float(HUMAN_CLICK_MOVE_PAUSE if move_pause is None else move_pause))
    hold = max(0.0, float(HUMAN_CLICK_HOLD_SECONDS if hold_seconds is None else hold_seconds))
    between = max(0.0, float(HUMAN_CLICK_INTERVAL if interval is None else interval))
    if pause:
        time.sleep(pause)
    for index in range(clicks):
        pyautogui.mouseDown(button=button)
        if hold:
            time.sleep(hold)
        pyautogui.mouseUp(button=button)
        if between and index < clicks - 1:
            time.sleep(between)


def pyautogui_fat_click(pyautogui, x, y, clicks=1, button="left", enabled=None, radius=None, delay=None):
    enabled = FAT_CLICK_ENABLED if enabled is None else bool(enabled)
    clicks = int(clicks or 1)
    center_snap = None
    if str(button).lower() == "left":
        center_snap = atspi_center_snap(x, y)
        if center_snap and center_snap.get("to"):
            x, y = center_snap["to"]
    pointer_bridge_to(pyautogui, x, y)
    snap = None
    if str(button).lower() == "left" and clicks == 1:
        x, y, probe, snap = hover_probe_and_snap(pyautogui, x, y)
    else:
        probe = None
    if not enabled or str(button).lower() != "left" or clicks != 1:
        cx, cy = clamp_framebuffer_point(x, y)
        dx, dy = framebuffer_to_display_point(cx, cy)
        pyautogui_human_click(pyautogui, dx, dy, clicks=clicks, button=button)
        return {"points": [{"x": cx, "y": cy}], "hover_probe": probe, "hover_snap": snap, "center_snap": center_snap}
    points = fat_click_points(x, y, radius=radius)
    pause = max(0.0, float(FAT_CLICK_DELAY if delay is None else delay))
    for index, (px, py) in enumerate(points):
        dx, dy = framebuffer_to_display_point(px, py)
        pyautogui_human_click(pyautogui, dx, dy, clicks=1, button=button)
        if pause and index < len(points) - 1:
            time.sleep(pause)
    return {"points": [{"x": px, "y": py} for px, py in points], "hover_probe": probe, "hover_snap": snap, "center_snap": center_snap}


def overlay_cursor_marker(image):
    if not SCREENSHOT_CURSOR_ENABLED and not SCREENSHOT_MOUSE_XY_ENABLED:
        return image
    try:
        from PIL import ImageDraw, ImageFont

        image = image.convert("RGB")
        draw = ImageDraw.Draw(image)

        def clamp_point(px, py):
            return max(0, min(int(px), image.size[0] - 1)), max(0, min(int(py), image.size[1] - 1))

        event_age = time.time() - float(LAST_POINTER_EVENT.get("t") or 0)
        if LAST_POINTER_EVENT.get("x") is not None and event_age <= SCREENSHOT_CLICK_HIGHLIGHT_SECONDS:
            ex, ey = clamp_point(LAST_POINTER_EVENT.get("x"), LAST_POINTER_EVENT.get("y"))
            pulse = 9
            draw.ellipse((ex - pulse - 2, ey - pulse - 2, ex + pulse + 2, ey + pulse + 2), outline=(0, 0, 0), width=2)
            draw.ellipse((ex - pulse, ey - pulse, ex + pulse, ey + pulse), outline=(255, 230, 0), width=2)

        point = current_mouse_location()
        if point:
            x, y = clamp_point(*point)
            if SCREENSHOT_CURSOR_ENABLED:
                r = max(3, min(int(SCREENSHOT_CURSOR_RADIUS), 10))
                # Small hollow cursor marker so underlying UI pixels stay visible.
                draw.ellipse((x - r - 1, y - r - 1, x + r + 1, y + r + 1), outline=(0, 0, 0), width=2)
                draw.ellipse((x - r, y - r, x + r, y + r), outline=(255, 0, 0), width=2)
            if SCREENSHOT_MOUSE_XY_ENABLED:
                label = f"{x},{y}"
                scale = max(2, int(min(image.size) / 512))
                tx = max(4, min(x + 18, image.size[0] - 180))
                ty = max(32, min(y + 18, image.size[1] - 48))
                font_size = 7 * scale
                try:
                    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", font_size)
                except Exception:
                    try:
                        font = ImageFont.load_default(size=font_size)
                    except TypeError:
                        font = ImageFont.load_default()
                try:
                    box = draw.textbbox((tx, ty), label, font=font)
                    pad_x = 3
                    pad_y = 2
                    rect = (box[0] - pad_x, box[1] - pad_y, box[2] + pad_x, box[3] + pad_y)
                    draw.rectangle(
                        rect,
                        fill=(0, 0, 0),
                    )
                except Exception:
                    pass
                # Tiny leader arrow. The arrow tip is the exact mouse pixel.
                arrow_end_x = max(0, tx - 2)
                arrow_end_y = max(0, ty - 2)
                draw.line((arrow_end_x, arrow_end_y, x, y), fill=(0, 0, 0), width=2)
                draw.line((arrow_end_x, arrow_end_y, x, y), fill=(255, 255, 255), width=1)
                draw.polygon([(x, y), (x + 2, y + 1), (x + 1, y + 2)], fill=(255, 255, 255))
                draw.text((tx, ty), label, fill=(255, 255, 255), font=font)
    except Exception:
        return image
    return image


def capture_framebuffer_image():
    if VNC_BACKEND_ENABLED:
        return vnc_capture_framebuffer_image()
    pyautogui = import_pyautogui()
    image = pyautogui.screenshot()
    remember_framebuffer_size(image.size[0], image.size[1])
    return image


def framebuffer_geometry():
    try:
        image = capture_framebuffer_image()
        return {"ok": True, "width": int(image.size[0]), "height": int(image.size[1]), "source": "framebuffer"}
    except Exception as exc:
        width, height = display_geometry()
        return {
            "ok": False,
            "width": int(width),
            "height": int(height),
            "source": "display_geometry_fallback",
            "error": str(exc),
        }


def capture_png_bytes(retries=3):
    last = b""
    for attempt in range(max(1, int(retries))):
        image = overlay_cursor_marker(capture_framebuffer_image())
        out = io.BytesIO()
        image.save(out, format="PNG")
        body = out.getvalue()
        last = body
        if len(body) > 1024 and body.startswith(b"\x89PNG\r\n\x1a\n") and b"IEND" in body[-32:]:
            return body
        if attempt < retries - 1:
            time.sleep(0.05)
    raise RuntimeError(f"screenshot capture produced invalid/truncated PNG ({len(last)} bytes)")


def wait_before_screenshot_api():
    delay = max(0.0, float(SCREENSHOT_API_DELAY_SECONDS))
    if delay:
        time.sleep(delay)


def parse_crop_bbox(data, query, width, height):
    def first_value(name, default=None):
        if isinstance(data, dict) and name in data:
            return data.get(name)
        values = query.get(name) if isinstance(query, dict) else None
        if values:
            return values[0]
        return default

    bbox = first_value("bbox") or first_value("crop") or first_value("rect")
    mode = str(first_value("bbox_mode", first_value("mode", "xyxy")) or "xyxy").lower()
    if isinstance(bbox, str):
        bbox = [part for part in re.split(r"[\s,]+", bbox.strip()) if part]
    if isinstance(bbox, (list, tuple)) and len(bbox) >= 4:
        vals = [float(item) for item in bbox[:4]]
        if mode in ("xywh", "x_y_w_h", "left_top_width_height"):
            x1, y1, w, h = vals
            x2, y2 = x1 + w, y1 + h
        else:
            x1, y1, x2, y2 = vals
    elif first_value("x") is not None and first_value("y") is not None:
        x1 = float(first_value("x"))
        y1 = float(first_value("y"))
        if first_value("w") is not None or first_value("width") is not None:
            w = float(first_value("w", first_value("width", 0)))
            h = float(first_value("h", first_value("height", 0)))
            x2, y2 = x1 + w, y1 + h
        else:
            x2 = float(first_value("x2"))
            y2 = float(first_value("y2"))
    elif first_value("x1") is not None and first_value("y1") is not None:
        x1 = float(first_value("x1"))
        y1 = float(first_value("y1"))
        x2 = float(first_value("x2"))
        y2 = float(first_value("y2"))
    else:
        x1, y1, x2, y2 = 0, 0, width, height

    left = max(0, min(int(round(min(x1, x2))), width))
    top = max(0, min(int(round(min(y1, y2))), height))
    right = max(0, min(int(round(max(x1, x2))), width))
    bottom = max(0, min(int(round(max(y1, y2))), height))
    if right <= left or bottom <= top:
        raise ValueError(f"invalid crop bbox {[left, top, right, bottom]} for screen {width}x{height}")
    return [left, top, right, bottom]


def get_paddle_ocr(model="mobile", data=None):
    data = data if isinstance(data, dict) else {}
    model = str(model or "mobile").lower()
    key = (
        model,
        str(data.get("text_detection_model_name") or ""),
        str(data.get("text_recognition_model_name") or ""),
        str(data.get("lang") or "en"),
    )
    if key in PADDLE_OCR_CACHE:
        return PADDLE_OCR_CACHE[key], 0, True

    start = time.time()
    from paddleocr import PaddleOCR

    kwargs = {
        "lang": data.get("lang", "en"),
        "use_doc_orientation_classify": bool_param(data.get("use_doc_orientation_classify"), False),
        "use_doc_unwarping": bool_param(data.get("use_doc_unwarping"), False),
        "use_textline_orientation": bool_param(data.get("use_textline_orientation"), False),
    }
    if model in ("mobile", "fast"):
        kwargs.update(
            {
                "text_detection_model_name": data.get("text_detection_model_name", "PP-OCRv5_mobile_det"),
                "text_recognition_model_name": data.get("text_recognition_model_name", "en_PP-OCRv5_mobile_rec"),
            }
        )
    elif model not in ("default", "server"):
        raise ValueError("model must be one of mobile, fast, default, or server")

    for name in (
        "text_det_limit_side_len",
        "text_det_limit_type",
        "text_det_thresh",
        "text_det_box_thresh",
        "text_det_unclip_ratio",
        "text_rec_score_thresh",
        "text_recognition_batch_size",
    ):
        if data.get(name) is not None:
            kwargs[name] = data.get(name)

    ocr = PaddleOCR(**kwargs)
    PADDLE_OCR_CACHE[key] = ocr
    return ocr, int((time.time() - start) * 1000), False


def paddle_ocr_items(result, origin=(0, 0)):
    pages = result if isinstance(result, list) else [result]
    items = []
    ox, oy = int(origin[0]), int(origin[1])

    for page in pages:
        if hasattr(page, "json"):
            raw = page.json.get("res", page.json)
        elif isinstance(page, dict):
            raw = page.get("res", page)
        else:
            raw = page

        if isinstance(raw, dict) and raw.get("rec_texts") is not None:
            texts = raw.get("rec_texts") or []
            scores = raw.get("rec_scores") or []
            boxes = raw.get("rec_boxes") or raw.get("rec_polys") or raw.get("dt_polys") or []
            for idx, text in enumerate(texts):
                box = json_safe(boxes[idx]) if idx < len(boxes) else []
                score = float(scores[idx]) if idx < len(scores) else None
                item = ocr_item_from_box(text, score, box, ox, oy)
                if item:
                    items.append(item)
            continue

        # PaddleOCR 2.x shape: [[box, (text, score)], ...]
        if isinstance(raw, list):
            for entry in raw:
                if not isinstance(entry, (list, tuple)) or len(entry) < 2:
                    continue
                box = json_safe(entry[0])
                rec = entry[1]
                if isinstance(rec, (list, tuple)) and rec:
                    text = rec[0]
                    score = float(rec[1]) if len(rec) > 1 else None
                    item = ocr_item_from_box(text, score, box, ox, oy)
                    if item:
                        items.append(item)

    return items


def ocr_item_from_box(text, score, box, ox=0, oy=0):
    text = str(text or "").strip()
    if not text:
        return None
    if len(box) == 4 and all(isinstance(v, (int, float)) for v in box):
        x1, y1, x2, y2 = [int(round(float(v))) for v in box]
        quad = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
    else:
        quad = [[float(point[0]), float(point[1])] for point in box if isinstance(point, (list, tuple)) and len(point) >= 2]
        if not quad:
            return None
        xs = [point[0] for point in quad]
        ys = [point[1] for point in quad]
        x1, y1, x2, y2 = [int(round(v)) for v in (min(xs), min(ys), max(xs), max(ys))]
    full_bbox = [x1 + ox, y1 + oy, x2 + ox, y2 + oy]
    center = [(full_bbox[0] + full_bbox[2]) // 2, (full_bbox[1] + full_bbox[3]) // 2]
    return {
        "text": text,
        "normalized": normalize_match_text(text),
        "confidence": score,
        "bbox": full_bbox,
        "crop_bbox": [x1, y1, x2, y2],
        "center": center,
        "quad": [[point[0] + ox, point[1] + oy] for point in quad],
    }


def find_ocr_target(items, target_text, match_mode="contains"):
    target_norm = normalize_match_text(target_text)
    if not target_norm:
        return None
    match_mode = str(match_mode or "contains").lower()

    def area(item):
        x1, y1, x2, y2 = item.get("bbox") or [0, 0, 0, 0]
        return max(0, x2 - x1) * max(0, y2 - y1)

    pairs = [(item, item.get("normalized") or normalize_match_text(item.get("text"))) for item in items]
    exact = [item for item, text_norm in pairs if text_norm == target_norm]
    if exact:
        return max(exact, key=area)

    if match_mode in ("exact", "eq"):
        return None

    substring = [item for item, text_norm in pairs if target_norm in text_norm or text_norm in target_norm]
    if substring:
        return max(substring, key=area)

    if match_mode in ("prefix", "startswith"):
        prefix = [item for item, text_norm in pairs if text_norm.startswith(target_norm)]
        return max(prefix, key=area) if prefix else None

    threshold = 0.8
    if isinstance(match_mode, str) and match_mode.startswith("fuzzy:"):
        try:
            threshold = float(match_mode.split(":", 1)[1])
        except Exception:
            threshold = 0.8
    elif match_mode not in ("contains", "substring", "fuzzy", "auto"):
        try:
            threshold = float(match_mode)
        except Exception:
            threshold = 0.8

    scored = []
    for item, text_norm in pairs:
        if not text_norm:
            continue
        ratio = difflib.SequenceMatcher(None, target_norm, text_norm).ratio()
        if ratio >= threshold:
            scored.append((item, ratio))
    if scored:
        return max(scored, key=lambda pair: (pair[1], area(pair[0])))[0]
    return None


def type_text_via_clipboard_or_pyautogui(pyautogui, text):
    text = str(text or "")
    commit = text.endswith("\n")
    if commit:
        text = text[:-1]
    if text:
        clipboard_used = False
        if IS_WINDOWS:
            try:
                import pyperclip

                pyperclip.copy(text)
                pyautogui.hotkey("ctrl", "v")
                clipboard_used = True
            except Exception:
                clipboard_used = False
        elif shutil.which("xclip"):
            try:
                proc = subprocess.run(
                    ["xclip", "-selection", "clipboard"],
                    input=text,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    timeout=3,
                    env=platform_env(),
                )
                if proc.returncode == 0:
                    pyautogui.hotkey("ctrl", "v")
                    clipboard_used = True
            except Exception:
                clipboard_used = False
        if not clipboard_used:
            pyautogui.write(text, interval=0.01)
    if commit:
        pyautogui.press("enter")


def codeact_intent_postamble():
    return r'''

# ---- Nubian CodeAct target-intent postamble ----
try:
    _nubian_target = globals().get("target")
    if isinstance(_nubian_target, str) and _nubian_target.strip():
        _nubian_action = str(globals().get("target_action") or "click").lower()
        _nubian_payload = {
            "target_text": _nubian_target,
            "bbox": globals().get("target_region"),
            "action": _nubian_action,
            "type_text": globals().get("target_text"),
            "match": "contains",
            "model": "mobile",
        }
        _nubian_response = _computer_agent_post("/codeact/ocr", _nubian_payload, timeout=30)
        _nubian_intent = {
            "ok": bool(_nubian_response.get("ok")),
            "target": _nubian_target,
            "region": globals().get("target_region"),
            "action": _nubian_action,
            "matched": (_nubian_response.get("match") or {}).get("text"),
            "center": (_nubian_response.get("match") or {}).get("center"),
            "bbox": (_nubian_response.get("match") or {}).get("bbox"),
            "ocr_score": (_nubian_response.get("match") or {}).get("confidence"),
            "boxes_seen": _nubian_response.get("count"),
            "action_result": _nubian_response.get("action_result"),
            "elapsed_ms": _nubian_response.get("elapsed_ms"),
            "artifacts": _nubian_response.get("artifacts"),
        }
        if not _nubian_intent["ok"]:
            _nubian_intent["reason"] = (_nubian_response.get("action_result") or {}).get("error") or "no_ocr_match"
            _nubian_intent["samples"] = [item.get("text") for item in (_nubian_response.get("items") or [])[:8]]
        print("<<<CODEACT_INTENT_APPLIED>>>" + json.dumps(_nubian_intent, ensure_ascii=False))
except Exception as _nubian_intent_exc:
    print("<<<CODEACT_INTENT_APPLIED>>>" + json.dumps({
        "ok": False,
        "reason": "intent_exception",
        "error": str(_nubian_intent_exc),
        "target": globals().get("target"),
        "region": globals().get("target_region"),
        "action": globals().get("target_action"),
    }, ensure_ascii=False))
'''


def draw_ocr_annotation(image, items, match=None, crop_bbox=None):
    from PIL import ImageDraw

    annotated = image.convert("RGB")
    draw = ImageDraw.Draw(annotated)
    if crop_bbox:
        draw.rectangle(crop_bbox, outline=(255, 230, 0), width=4)
    for idx, item in enumerate(items, 1):
        x1, y1, x2, y2 = item["bbox"]
        is_match = match and item.get("bbox") == match.get("bbox") and item.get("text") == match.get("text")
        color = (0, 255, 255) if is_match else (255, 0, 0)
        width = 5 if is_match else 2
        draw.rectangle([x1, y1, x2, y2], outline=color, width=width)
        label = str(idx)
        draw.rectangle([x1, max(0, y1 - 16), x1 + 8 * len(label) + 6, y1], fill=color)
        draw.text((x1 + 3, max(0, y1 - 15)), label, fill=(0, 0, 0))
    if match:
        cx, cy = match["center"]
        draw.line((cx - 45, cy, cx + 45, cy), fill=(0, 255, 255), width=4)
        draw.line((cx, cy - 45, cx, cy + 45), fill=(0, 255, 255), width=4)
        draw.ellipse((cx - 16, cy - 16, cx + 16, cy + 16), outline=(0, 255, 255), width=4)
    return annotated


def proxy_get(url, timeout=30):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.headers.get("content-type", "application/octet-stream"), resp.read()


def post_json_to_url(url, payload, timeout=30):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        text = raw.decode("utf-8", errors="replace")
        content_type = resp.headers.get("content-type", "")
        if "json" in content_type or text.lstrip().startswith(("{", "[")):
            return json.loads(text)
        return {"ok": True, "text": text}


def omniparser_parse_url():
    if not OMNIPARSER_URL:
        return ""
    if OMNIPARSER_URL.endswith("/parse") or OMNIPARSER_URL.endswith("/parse/"):
        return OMNIPARSER_URL if OMNIPARSER_URL.endswith("/") else OMNIPARSER_URL + "/"
    return OMNIPARSER_URL.rstrip("/") + "/parse/"


def image_to_base64_png(image):
    out = io.BytesIO()
    image.convert("RGB").save(out, format="PNG")
    return base64.b64encode(out.getvalue()).decode("ascii")


def normalize_omniparser_boxes(parsed_content_list, width, height):
    boxes = []
    for index, item in enumerate(parsed_content_list or []):
        if not isinstance(item, dict):
            continue
        bbox = item.get("bbox") or item.get("box")
        if not isinstance(bbox, (list, tuple)) or len(bbox) < 4:
            continue
        try:
            vals = [float(v) for v in bbox[:4]]
        except Exception:
            continue
        # OmniParser emits ratio boxes. Accept absolute boxes too for custom servers.
        if max(vals) <= 1.5:
            x1, y1, x2, y2 = vals[0] * width, vals[1] * height, vals[2] * width, vals[3] * height
        else:
            x1, y1, x2, y2 = vals
        x1, y1 = clamp_framebuffer_point(x1, y1)
        x2, y2 = clamp_framebuffer_point(x2, y2)
        left, right = sorted((x1, x2))
        top, bottom = sorted((y1, y2))
        text = item.get("content", item.get("text", item.get("label", item.get("alt", ""))))
        role = item.get("type", item.get("role", item.get("class", "element")))
        cx = int(round((left + right) / 2))
        cy = int(round((top + bottom) / 2))
        boxes.append(
            {
                "id": int(item.get("id", index)) if str(item.get("id", index)).isdigit() else index,
                "index": index,
                "type": str(role or "element"),
                "text": str(text or ""),
                "bbox": [int(left), int(top), int(right), int(bottom)],
                "center": [cx, cy],
                "raw_bbox": vals,
                "raw": item,
            }
        )
    return boxes


def omniparser_screen_info(boxes):
    lines = []
    for box in boxes:
        tag = "p" if box.get("type") == "text" else "img"
        klass = "text" if box.get("type") == "text" else "icon"
        alt = html.escape(str(box.get("text") or box.get("type") or ""), quote=True)
        lines.append(f'<{tag} id="{box["id"]}" class="{klass}" alt="{alt}"> </{tag}>')
    return "\n".join(lines)


def cached_omniparser_box(box_id):
    boxes = LAST_OMNIPARSER_PARSE.get("boxes") or []
    try:
        wanted = int(box_id)
    except Exception:
        return None
    for box in boxes:
        if int(box.get("id", -1)) == wanted or int(box.get("index", -1)) == wanted:
            return box
    return None


def apply_omniparser_box_action(pyautogui, action, box=None, text="", button="left"):
    action = str(action or "").strip().lower()
    if action in ("none", "done"):
        return {"ok": True, "action": action}
    if action in ("wait", "hold"):
        seconds = 1.0
        try:
            seconds = float(text) if text else 1.0
        except Exception:
            pass
        time.sleep(max(0, seconds))
        return {"ok": True, "action": action, "seconds": seconds}
    if action == "key":
        if not text:
            return {"ok": False, "error": "key action requires key or value"}
        pyautogui.press(str(text))
        return {"ok": True, "action": "key", "key": str(text)}
    if action == "type" and not box:
        if text:
            pyautogui.write(str(text), interval=0.01)
        return {"ok": True, "action": "type", "chars": len(str(text or ""))}
    if action in ("scroll_up", "scroll-down-up"):
        pyautogui.scroll(5)
        return {"ok": True, "action": "scroll_up"}
    if action in ("scroll_down", "scroll"):
        pyautogui.scroll(-5)
        return {"ok": True, "action": "scroll_down"}
    if not box:
        return {"ok": False, "error": f"action {action!r} requires box_id"}
    x, y = box["center"]
    dx, dy = framebuffer_to_display_point(x, y)
    pointer_bridge_to(pyautogui, x, y)
    if action in ("hover", "move", "mouse_move"):
        pyautogui.moveTo(dx, dy, duration=0)
        remember_pointer_event("move", x, y)
    elif action in ("left_click", "click"):
        pyautogui_human_click(pyautogui, dx, dy, button=button or "left")
        remember_pointer_event("click", x, y)
    elif action == "right_click":
        pyautogui_human_click(pyautogui, dx, dy, button="right")
        remember_pointer_event("right_click", x, y)
    elif action == "double_click":
        pyautogui_human_click(pyautogui, dx, dy, clicks=2)
        remember_pointer_event("double_click", x, y)
    elif action == "type":
        pyautogui_human_click(pyautogui, dx, dy)
        remember_pointer_event("click", x, y)
        if text:
            pyautogui.write(str(text), interval=0.01)
    else:
        return {"ok": False, "error": f"unknown omniparser action {action!r}"}
    return {"ok": True, "action": action, "box": box, "center": [x, y]}


def skills_connect():
    SKILLS_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(SKILLS_DB))
    con.row_factory = sqlite3.Row
    return con


def ensure_skills_db():
    now = time.time()
    con = skills_connect()
    try:
        con.execute(
            """
            CREATE TABLE IF NOT EXISTS skills (
              id TEXT PRIMARY KEY,
              task_pattern TEXT NOT NULL,
              description TEXT NOT NULL,
              macro_type TEXT NOT NULL,
              template_json TEXT NOT NULL,
              ok_count INTEGER NOT NULL DEFAULT 0,
              fail_count INTEGER NOT NULL DEFAULT 0,
              created_at REAL NOT NULL,
              updated_at REAL NOT NULL
            )
            """
        )
        con.execute("CREATE TABLE IF NOT EXISTS skill_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        for skill in SEED_SKILLS:
            con.execute(
                """
                INSERT INTO skills (id, task_pattern, description, macro_type, template_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  task_pattern=excluded.task_pattern,
                  description=excluded.description,
                  macro_type=excluded.macro_type,
                  template_json=excluded.template_json,
                  updated_at=excluded.updated_at
                """,
                (
                    skill["id"],
                    skill["task_pattern"],
                    skill["description"],
                    skill["macro_type"],
                    json.dumps(skill.get("template", {}), sort_keys=True),
                    now,
                    now,
                ),
            )
        try:
            con.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS skills_fts USING fts5(skill_id UNINDEXED, task_pattern, description)"
            )
            con.execute("DELETE FROM skills_fts")
            con.execute(
                """
                INSERT INTO skills_fts (skill_id, task_pattern, description)
                SELECT id, task_pattern, description FROM skills
                """
            )
            con.execute(
                "INSERT OR REPLACE INTO skill_meta (key, value) VALUES ('fts5_enabled', 'true')"
            )
        except Exception as exc:
            con.execute(
                "INSERT OR REPLACE INTO skill_meta (key, value) VALUES ('fts5_enabled', ?)",
                (f"false: {exc}",),
            )
        con.commit()
    finally:
        con.close()


def skill_from_row(row):
    item = dict(row)
    try:
        item["template"] = json.loads(item.pop("template_json") or "{}")
    except Exception:
        item["template"] = {}
    return item


def normalize_skill_text(value):
    tokens = []
    current = []
    for ch in (value or "").lower():
        if ch.isalnum():
            current.append(ch)
        elif current:
            token = "".join(current)
            if len(token) >= 2:
                tokens.append(token)
            current = []
    if current:
        token = "".join(current)
        if len(token) >= 2:
            tokens.append(token)
    return tokens


def skill_match_score(query, skill):
    query_tokens = normalize_skill_text(query)
    if not query_tokens:
        return 1.0
    haystack = " ".join(
        [
            skill.get("id", ""),
            skill.get("task_pattern", ""),
            skill.get("description", ""),
            json.dumps(skill.get("template", {}), sort_keys=True),
        ]
    )
    skill_tokens = set(normalize_skill_text(haystack))
    score = 0.0
    for token in query_tokens:
        if token in skill_tokens:
            score += 2.0
            continue
        prefix = token[:4]
        if len(prefix) >= 3 and any(other.startswith(prefix) or token.startswith(other[:4]) for other in skill_tokens):
            score += 1.0
            continue
        if any(difflib.SequenceMatcher(None, token, other).ratio() >= 0.78 for other in skill_tokens):
            score += 0.75
    return score / max(1, len(query_tokens))


def list_skills():
    ensure_skills_db()
    con = skills_connect()
    try:
        rows = con.execute("SELECT * FROM skills ORDER BY id").fetchall()
        return [skill_from_row(row) for row in rows]
    finally:
        con.close()


def get_skill(skill_id):
    ensure_skills_db()
    con = skills_connect()
    try:
        row = con.execute("SELECT * FROM skills WHERE id = ?", (skill_id,)).fetchone()
        return skill_from_row(row) if row else None
    finally:
        con.close()


def search_skills(query, limit=5):
    skills = list_skills()
    for skill in skills:
        skill["score"] = skill_match_score(query, skill)
    skills.sort(key=lambda item: (item.get("score", 0), item.get("ok_count", 0), item.get("id", "")), reverse=True)
    if query:
        skills = [skill for skill in skills if skill.get("score", 0) > 0]
    return skills[: max(1, min(int(limit), 50))]


def record_skill_result(skill_id, ok):
    con = skills_connect()
    try:
        field = "ok_count" if ok else "fail_count"
        con.execute(f"UPDATE skills SET {field} = {field} + 1, updated_at = ? WHERE id = ?", (time.time(), skill_id))
        con.commit()
    finally:
        con.close()


def clean_desktop_exec(value):
    value = value or ""
    value = re.sub(r"\s+%[A-Za-z]", "", value)
    value = value.replace("%%", "%").strip()
    return value


def parse_desktop_file(path):
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    parser.optionxform = str
    try:
        parser.read(path, encoding="utf-8")
        section = parser["Desktop Entry"]
    except Exception:
        return None
    if section.get("Type", "Application") != "Application":
        return None
    if section.get("NoDisplay", "false").lower() == "true" or section.get("Hidden", "false").lower() == "true":
        return None
    name = section.get("Name") or section.get("GenericName") or path.stem
    exec_cmd = clean_desktop_exec(section.get("Exec", ""))
    if not exec_cmd:
        return None
    categories = [item for item in section.get("Categories", "").split(";") if item]
    return {
        "name": name,
        "generic_name": section.get("GenericName", ""),
        "comment": section.get("Comment", ""),
        "exec": exec_cmd,
        "icon": section.get("Icon", ""),
        "categories": categories,
        "desktop_file": str(path),
        "source": str(path.parent),
    }


def list_installed_apps(limit=500):
    if IS_WINDOWS:
        result = run_command(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress",
            ],
            timeout_ms=5000,
            shell=False,
        )
        apps = []
        if result.get("ok"):
            try:
                raw = json.loads(result.get("stdout") or "[]")
            except Exception:
                raw = []
            if isinstance(raw, dict):
                raw = [raw]
            for item in raw:
                name = str(item.get("Name") or "").strip()
                app_id = str(item.get("AppID") or "").strip()
                if not name:
                    continue
                apps.append(
                    {
                        "name": name,
                        "generic_name": "",
                        "comment": "",
                        "exec": app_id or name,
                        "icon": "",
                        "categories": [],
                        "desktop_file": app_id,
                        "source": "Get-StartApps",
                    }
                )
        apps.sort(key=lambda item: item["name"].lower())
        return apps[: max(1, min(int(limit), 2000))]

    apps = []
    seen = set()
    for folder in DESKTOP_APP_DIRS:
        if not folder.exists():
            continue
        for path in sorted(folder.glob("*.desktop")):
            item = parse_desktop_file(path)
            if not item:
                continue
            key = (item["name"].lower(), item["exec"])
            if key in seen:
                continue
            seen.add(key)
            apps.append(item)
    apps.sort(key=lambda item: item["name"].lower())
    return apps[: max(1, min(int(limit), 2000))]


def app_score(query, app):
    tokens = normalize_skill_text(query)
    if not tokens:
        return 1.0
    haystack = " ".join(
        [
            app.get("name", ""),
            app.get("generic_name", ""),
            app.get("comment", ""),
            app.get("exec", ""),
            " ".join(app.get("categories", [])),
        ]
    )
    hay_tokens = set(normalize_skill_text(haystack))
    score = 0.0
    for token in tokens:
        if token in hay_tokens:
            score += 2.0
        elif any(other.startswith(token[:4]) or token.startswith(other[:4]) for other in hay_tokens if len(token) >= 3):
            score += 1.0
        elif any(difflib.SequenceMatcher(None, token, other).ratio() >= 0.78 for other in hay_tokens):
            score += 0.75
    return score / max(1, len(tokens))


def search_installed_apps(query, limit=20):
    apps = list_installed_apps(limit=2000)
    for app in apps:
        app["score"] = app_score(query, app)
    apps.sort(key=lambda item: (item.get("score", 0), item.get("name", "")), reverse=True)
    if query:
        apps = [app for app in apps if app.get("score", 0) > 0]
    return apps[: max(1, min(int(limit), 200))]


def app_launch_query(data):
    return str(data.get("name") or data.get("app_name") or data.get("app") or data.get("desktop_file") or data.get("exec") or "").strip()


def app_suggestions(query, limit=8):
    matches = search_installed_apps(query, limit=limit) if query else []
    suggestions = []
    for app in matches:
        suggestions.append(
            {
                "name": app.get("name", ""),
                "desktop_file": app.get("desktop_file", ""),
                "exec": app.get("exec", ""),
                "generic_name": app.get("generic_name", ""),
                "score": app.get("score", 0),
                "use": {"name": app.get("name", ""), "desktop_file": app.get("desktop_file", "")},
            }
        )
    return suggestions


def normalize_xid(xid):
    if not xid:
        return ""
    text = str(xid).strip().lower()
    try:
        return hex(int(text, 16 if text.startswith("0x") else 10))
    except Exception:
        return text


def window_by_xid(xid):
    if not xid:
        return None
    xid_text = normalize_xid(xid)
    for window in running_windows_snapshot():
        if normalize_xid(window.get("xid")) == xid_text or normalize_xid(window.get("id")) == xid_text:
            return window
    return None


def text_similarity_score(query, values):
    query_tokens = normalize_skill_text(query)
    if not query_tokens:
        return 0.0
    hay = " ".join(str(value or "") for value in values)
    hay_tokens = set(normalize_skill_text(hay))
    if not hay_tokens:
        return 0.0
    score = 0.0
    for token in query_tokens:
        if token in hay_tokens:
            score += 2.0
        elif any(token in other or other in token for other in hay_tokens if len(token) >= 3 and len(other) >= 3):
            score += 1.0
        elif any(difflib.SequenceMatcher(None, token, other).ratio() >= 0.78 for other in hay_tokens):
            score += 0.75
    return score / max(1, len(query_tokens))


def window_score(query, window):
    q_norm = normalized_window_text(query)
    app_title = normalized_window_text(" ".join([window.get("app", ""), window.get("title", "")]))
    hay = [
        window.get("app", ""),
        window.get("proc", ""),
        window.get("title", ""),
        window.get("xid", ""),
        window.get("id", ""),
    ]
    score = text_similarity_score(query, hay)
    if q_norm and app_title:
        if q_norm == app_title:
            score += 5.0
        elif q_norm in app_title or app_title in q_norm:
            score += 2.0
    if window.get("minimized"):
        score -= 0.25
    return score


def window_suggestions(query, limit=8):
    windows = running_windows_snapshot()
    scored = []
    for window in windows:
        score = window_score(query, window)
        if score <= 0:
            continue
        scored.append((score, window))
    scored.sort(
        key=lambda item: (
            item[0],
            not bool(item[1].get("minimized")),
            int(item[1].get("w") or 0) * int(item[1].get("h") or 0),
        ),
        reverse=True,
    )
    suggestions = []
    for score, window in scored[: max(1, min(int(limit), 50))]:
        suggestions.append(
            {
                "score": round(float(score), 3),
                "xid": window.get("xid") or window.get("id"),
                "app": window.get("app", ""),
                "proc": window.get("proc", ""),
                "title": window.get("title", ""),
                "bbox": window.get("bbox"),
                "minimized": bool(window.get("minimized")),
                "use": {
                    "xid": window.get("xid") or window.get("id"),
                    "app": window.get("app", ""),
                    "title": window.get("title", ""),
                },
                "window": window,
            }
        )
    return suggestions


def display_geometry():
    if VNC_BACKEND_ENABLED:
        return framebuffer_size()
    if IS_WINDOWS:
        try:
            size = import_pyautogui().size()
            return int(size[0]), int(size[1])
        except Exception:
            pass
    if shutil.which("xdotool"):
        result = run_command(["xdotool", "getdisplaygeometry"], shell=False, timeout_ms=2000)
        if result["ok"]:
            parts = result["stdout"].strip().split()
            if len(parts) >= 2:
                try:
                    return int(parts[0]), int(parts[1])
                except Exception:
                    pass
    if shutil.which("xdpyinfo"):
        result = run_command(["xdpyinfo"], shell=False, timeout_ms=2000)
        if result["ok"]:
            match = re.search(r"dimensions:\s+(\d+)x(\d+)", result["stdout"])
            if match:
                return int(match.group(1)), int(match.group(2))
    return 1920, 1080


def preferred_display_mode_name(width=None, height=None, refresh=None):
    width = int(width or PREFERRED_DISPLAY_WIDTH)
    height = int(height or PREFERRED_DISPLAY_HEIGHT)
    refresh = int(refresh or PREFERRED_DISPLAY_REFRESH)
    return f"{width}x{height}_{refresh}.00"


def xrandr_primary_output(xrandr_stdout):
    for line in str(xrandr_stdout or "").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "connected":
            return parts[0]
    return "Virtual-1"


def xrandr_existing_mode_for_resolution(xrandr_stdout, width, height):
    prefix = f"{int(width)}x{int(height)}"
    for line in str(xrandr_stdout or "").splitlines():
        stripped = line.strip()
        if not stripped.startswith(prefix):
            continue
        parts = stripped.split()
        if parts:
            return parts[0]
    return None


def cvt_modeline(width, height, refresh):
    if not shutil.which("cvt"):
        # CVT modelines for common VM test resolutions. Good fallbacks for this VM image.
        if int(width) == 1024 and int(height) == 1024 and int(refresh) == 60:
            return [86.50, 1024, 1088, 1192, 1360, 1024, 1027, 1037, 1063, "-hsync", "+vsync"]
        if int(width) == 1600 and int(height) == 900 and int(refresh) == 60:
            return [118.25, 1600, 1696, 1856, 2112, 900, 903, 908, 934, "-hsync", "+vsync"]
        return None
    result = run_command(["cvt", str(width), str(height), str(refresh)], shell=False, timeout_ms=3000)
    if not result.get("ok"):
        return None
    for line in result.get("stdout", "").splitlines():
        if "Modeline" not in line:
            continue
        tokens = shlex.split(line)
        # Example: Modeline "1024x1024_60.00" 86.50 1024 ...
        if len(tokens) >= 4:
            return tokens[2:]
    return None


def ensure_preferred_display_geometry():
    if not PREFERRED_DISPLAY_ENABLED:
        return {"ok": True, "enabled": False}
    if VNC_BACKEND_ENABLED:
        width, height = display_geometry()
        return {
            "ok": True,
            "enabled": False,
            "backend": BACKEND,
            "platform": VNC_TARGET_PLATFORM,
            "width": width,
            "height": height,
            "reason": "display resizing is not managed by the vnc backend",
        }
    if IS_WINDOWS:
        width, height = display_geometry()
        return {
            "ok": True,
            "enabled": False,
            "platform": "windows",
            "width": width,
            "height": height,
            "reason": "display resizing is not managed with xrandr on Windows",
        }
    width = int(PREFERRED_DISPLAY_WIDTH)
    height = int(PREFERRED_DISPLAY_HEIGHT)
    refresh = int(PREFERRED_DISPLAY_REFRESH)
    if width <= 0 or height <= 0 or not shutil.which("xrandr"):
        return {"ok": False, "enabled": True, "error": "invalid preferred display or xrandr unavailable"}
    current_w, current_h = display_geometry()
    if current_w == width and current_h == height:
        return {"ok": True, "changed": False, "width": current_w, "height": current_h}

    current = run_command(["xrandr", "--current"], shell=False, timeout_ms=3000)
    output = xrandr_primary_output(current.get("stdout", ""))
    mode = xrandr_existing_mode_for_resolution(current.get("stdout", ""), width, height) or preferred_display_mode_name(width, height, refresh)
    modeline = cvt_modeline(width, height, refresh)
    newmode_result = None
    addmode_result = None
    if modeline and mode == preferred_display_mode_name(width, height, refresh):
        newmode_result = run_command(["xrandr", "--newmode", mode, *[str(part) for part in modeline]], shell=False, timeout_ms=5000)
        # BadName means the mode already exists in the X server. That is fine.
        addmode_result = run_command(["xrandr", "--addmode", output, mode], shell=False, timeout_ms=5000)
    setmode_result = None
    after_w, after_h = display_geometry()
    for attempt in range(3):
        setmode_result = run_command(["xrandr", "--output", output, "--mode", mode], shell=False, timeout_ms=5000)
        time.sleep(0.7 + (attempt * 0.3))
        after_w, after_h = display_geometry()
        if after_w == width and after_h == height:
            break
    ok = after_w == width and after_h == height
    return {
        "ok": ok,
        "enabled": True,
        "changed": ok,
        "output": output,
        "mode": mode,
        "width": after_w,
        "height": after_h,
        "newmode": newmode_result,
        "addmode": addmode_result,
        "setmode": setmode_result,
    }


def window_looks_maximized(window, screen_w=None, screen_h=None):
    if not window:
        return False
    screen_w, screen_h = screen_w or display_geometry()[0], screen_h or display_geometry()[1]
    return int(window.get("w", 0)) >= int(screen_w * 0.82) and int(window.get("h", 0)) >= int(screen_h * 0.75)


def window_looks_fullscreen(window, screen_w=None, screen_h=None):
    if not window:
        return False
    screen_w, screen_h = screen_w or display_geometry()[0], screen_h or display_geometry()[1]
    try:
        x = int(window.get("x", 0))
        y = int(window.get("y", 0))
        w = int(window.get("w", 0))
        h = int(window.get("h", 0))
    except Exception:
        return False
    return x <= 8 and y <= 8 and w >= int(screen_w * 0.94) and h >= int(screen_h * 0.90)


def normalized_window_text(value):
    return " ".join(re.findall(r"[a-z0-9]+", str(value or "").lower()))


def app_match_terms(app):
    values = [
        app.get("canonical_name"),
        app.get("name"),
        app.get("generic_name"),
    ]
    if app.get("categories"):
        values.extend(app.get("categories") or [])
    terms = []
    for value in values:
        text = normalized_window_text(value)
        if text:
            terms.append(text)
    desktop_stem = normalized_window_text(pathlib.Path(str(app.get("desktop_file") or "")).stem)
    if desktop_stem:
        terms.append(desktop_stem)
    exec_head = str(app.get("exec") or "").split()
    if exec_head:
        exec_stem = normalized_window_text(pathlib.Path(exec_head[0]).stem)
        if exec_stem:
            terms.append(exec_stem)
    seen = set()
    out = []
    for term in terms:
        if term not in seen:
            seen.add(term)
            out.append(term)
    return out


def window_matches_app(window, app):
    hay = normalized_window_text(
        " ".join(
            [
                window.get("app", ""),
                window.get("proc", ""),
                window.get("title", ""),
                window.get("xid", ""),
            ]
        )
    )
    if not hay:
        return False
    for term in app_match_terms(app):
        if term and (term in hay or hay in term):
            return True
        parts = term.split()
        if len(parts) > 1 and all(part in hay for part in parts):
            return True
    return False


def find_launched_window(app, before_xids=None):
    before_xids = {normalize_xid(x) for x in (before_xids or set()) if x}
    windows = running_windows_snapshot()
    new_windows = [w for w in windows if normalize_xid(w.get("xid")) not in before_xids]
    for window in new_windows:
        if window_matches_app(window, app):
            return window
    for window in windows:
        if window_matches_app(window, app):
            return window
    return None


def wait_for_launched_window(app, before_xids=None, timeout_sec=15.0, poll_sec=0.25):
    deadline = time.time() + max(0.1, float(timeout_sec))
    last_window = None
    while time.time() < deadline:
        window = find_launched_window(app, before_xids=before_xids)
        if window:
            last_window = window
            # Give the window manager one tick to populate WM_CLASS/title/geometry.
            if window.get("w", 0) and window.get("h", 0):
                return window
        time.sleep(max(0.05, float(poll_sec)))
    return last_window


def parse_wait_stages(data, default=(5, 10, 15)):
    raw = data.get("wait_stages_sec", data.get("wait_stages"))
    if raw is None and ("wait_timeout_sec" in data or "timeout_sec" in data):
        raw = [data.get("wait_timeout_sec", data.get("timeout_sec"))]
    if raw is None:
        return [float(x) for x in default]
    if isinstance(raw, str):
        raw = [item for item in re.split(r"[, ]+", raw) if item]
    if not isinstance(raw, (list, tuple)):
        raw = [raw]
    stages = []
    for item in raw:
        try:
            value = float(item)
        except Exception:
            continue
        if value > 0:
            stages.append(min(value, 120.0))
    return stages or [float(x) for x in default]


def wait_for_launched_window_staged(app, before_xids=None, stages=None):
    stages = stages or [5, 10, 15]
    total = 0.0
    attempts = []
    last_window = None
    for stage in stages:
        started = time.time()
        window = wait_for_launched_window(app, before_xids=before_xids, timeout_sec=stage)
        elapsed = time.time() - started
        total += elapsed
        attempts.append({"timeout_sec": stage, "elapsed_sec": round(elapsed, 3), "found": bool(window)})
        if window:
            return window, {"ok": True, "stages": attempts, "elapsed_sec": round(total, 3)}
        last_window = window or last_window
    return last_window, {"ok": bool(last_window), "stages": attempts, "elapsed_sec": round(total, 3)}


def activate_and_maximize_window(window, maximize=True, force_maximize=True, fullscreen=True):
    if not window:
        return {"ok": False, "error": "window not found"}
    if IS_WINDOWS:
        before_geometry = window_by_xid(window.get("xid") or window.get("id")) or window
        target = pygetwindow_object_for_window(window)
        if not target:
            return {"ok": False, "error": "pygetwindow could not locate window", "window": window}
        activate_error = None
        maximize_error = None
        try:
            target.activate()
        except Exception as exc:
            activate_error = str(exc)
        if maximize:
            try:
                if force_maximize or not bool(getattr(target, "isMaximized", False)):
                    target.maximize()
            except Exception as exc:
                maximize_error = str(exc)
        time.sleep(0.2)
        after_geometry = window_by_xid(window.get("xid") or window.get("id")) or {
            **window,
            "x": int(getattr(target, "left", window.get("x", 0)) or 0),
            "y": int(getattr(target, "top", window.get("y", 0)) or 0),
            "w": int(getattr(target, "width", window.get("w", 0)) or 0),
            "h": int(getattr(target, "height", window.get("h", 0)) or 0),
        }
        maximized = window_looks_maximized(after_geometry)
        return {
            "ok": activate_error is None,
            "xid": window.get("xid") or window.get("id"),
            "window": window,
            "maximize": maximize,
            "force_maximize": force_maximize,
            "fullscreen": fullscreen,
            "fullscreened": False,
            "maximized": maximized,
            "geometry_normalized": maximized,
            "geometry_verified": bool(after_geometry),
            "warning": maximize_error or ("generic Windows fullscreen is not a window-manager state; maximized instead" if fullscreen else None),
            "before_geometry": before_geometry,
            "after_geometry": after_geometry,
            "command": {"ok": activate_error is None, "error": activate_error},
            "fullscreen_command": None,
            "maximize_command": {"ok": maximize_error is None, "error": maximize_error} if maximize else None,
            "fallback_maximize_command": None,
        }
    xid = window.get("xid") or window.get("id")
    if not xid:
        return {"ok": False, "error": "window has no xid", "window": window}
    if shutil.which("wmctrl"):
        activate_result = run_command(["wmctrl", "-i", "-a", xid], shell=False, timeout_ms=5000)
    elif shutil.which("xdotool"):
        activate_result = run_command(["xdotool", "windowactivate", xid], shell=False, timeout_ms=5000)
    else:
        return {"ok": False, "error": "neither wmctrl nor xdotool is installed", "window": window}

    fullscreen_result = None
    maximize_result = None
    fallback_maximize_result = None
    before_geometry = window_by_xid(xid) or window
    after_geometry = None
    if activate_result.get("ok") and fullscreen and shutil.which("wmctrl"):
        fullscreen_result = run_command(
            ["wmctrl", "-i", "-r", xid, "-b", "add,fullscreen"],
            shell=False,
            timeout_ms=5000,
        )
        time.sleep(0.35)
        after_geometry = window_by_xid(xid)
    if activate_result.get("ok") and maximize and shutil.which("wmctrl") and (
        not fullscreen or not window_looks_fullscreen(after_geometry)
    ):
        maximize_result = run_command(
            ["wmctrl", "-i", "-r", xid, "-b", "add,maximized_vert,maximized_horz"],
            shell=False,
            timeout_ms=5000,
        )
        time.sleep(0.25)
        after_geometry = window_by_xid(xid)
        if force_maximize and not window_looks_maximized(after_geometry):
            screen_w, screen_h = display_geometry()
            fallback_maximize_result = run_command(
                ["wmctrl", "-i", "-r", xid, "-e", f"0,0,0,{screen_w},{screen_h}"],
                shell=False,
                timeout_ms=5000,
            )
            time.sleep(0.25)
            after_geometry = window_by_xid(xid)
    else:
        time.sleep(0.1)
        after_geometry = window_by_xid(xid)

    fullscreened = window_looks_fullscreen(after_geometry)
    maximized = window_looks_maximized(after_geometry)
    geometry_verified = bool(after_geometry)
    geometry_normalized = fullscreened or maximized
    ok = bool(activate_result.get("ok"))
    warning = None
    if activate_result.get("ok") and (fullscreen or maximize) and not geometry_verified:
        warning = "activation succeeded, but post-activation geometry was unavailable"
    elif activate_result.get("ok") and fullscreen and not fullscreened:
        warning = "activation succeeded, but fullscreen verification failed; maximize fallback used"
    elif activate_result.get("ok") and maximize and not maximized:
        warning = "activation succeeded, but maximize verification failed"
    return {
        "ok": ok,
        "xid": xid,
        "window": window,
        "maximize": maximize,
        "force_maximize": force_maximize,
        "fullscreen": fullscreen,
        "fullscreened": fullscreened,
        "maximized": maximized,
        "geometry_normalized": geometry_normalized,
        "geometry_verified": geometry_verified,
        "warning": warning,
        "before_geometry": before_geometry,
        "after_geometry": after_geometry,
        "command": activate_result,
        "fullscreen_command": fullscreen_result,
        "maximize_command": maximize_result,
        "fallback_maximize_command": fallback_maximize_result,
    }


def parse_wmctrl_windows(output):
    windows = []
    for line in output.splitlines():
        parts = line.split(None, 8)
        if len(parts) < 9:
            continue
        xid, desktop, pid, x, y, w, h, host, title = parts
        try:
            desktop_value = int(desktop)
        except Exception:
            desktop_value = desktop
        try:
            pid_value = int(pid)
        except Exception:
            pid_value = None
        windows.append(
            {
                "xid": xid,
                "id": xid,
                "desktop": desktop_value,
                "pid": pid_value,
                "x": int(float(x)),
                "y": int(float(y)),
                "w": int(float(w)),
                "h": int(float(h)),
                "host": host,
                "title": title,
                "bbox": [int(float(x)), int(float(y)), int(float(w)), int(float(h))],
                "minimized": desktop_value == -1,
            }
        )
    return windows


def proc_name_for_pid(pid):
    if not pid:
        return ""
    if IS_WINDOWS:
        return ""
    try:
        return pathlib.Path(f"/proc/{pid}/comm").read_text(encoding="utf-8", errors="replace").strip()
    except Exception:
        return ""


def wm_class_for_xid(xid):
    if not xid or not shutil.which("xprop"):
        return ""
    try:
        result = subprocess.run(
            ["xprop", "-id", xid, "WM_CLASS"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=1,
            env=platform_env(),
        )
        value = result.stdout.strip()
        if "=" in value:
            classes = re.findall(r'"([^"]+)"', value)
            if classes:
                return classes[-1]
        return ""
    except Exception:
        return ""


def running_windows_snapshot_windows(limit=200):
    try:
        import pygetwindow

        windows = []
        for index, window in enumerate(pygetwindow.getAllWindows()):
            title = str(getattr(window, "title", "") or "")
            if not title:
                continue
            try:
                x = int(getattr(window, "left", 0) or 0)
                y = int(getattr(window, "top", 0) or 0)
                w = int(getattr(window, "width", 0) or 0)
                h = int(getattr(window, "height", 0) or 0)
            except Exception:
                x, y, w, h = 0, 0, 0, 0
            xid = f"pygetwindow:{index}"
            windows.append(
                {
                    "xid": xid,
                    "id": xid,
                    "handle": getattr(window, "_hWnd", None),
                    "desktop": 0,
                    "pid": None,
                    "x": x,
                    "y": y,
                    "w": w,
                    "h": h,
                    "host": platform.node(),
                    "title": title,
                    "app": title,
                    "proc": "",
                    "bbox": [x, y, w, h],
                    "minimized": bool(getattr(window, "isMinimized", False)),
                    "_backend": "pygetwindow",
                }
            )
            if len(windows) >= int(limit):
                break
        return windows
    except Exception:
        return []


def pygetwindow_object_for_window(window):
    if not IS_WINDOWS:
        return None
    try:
        import pygetwindow

        handle = window.get("handle")
        title = str(window.get("title") or "")
        for candidate in pygetwindow.getAllWindows():
            if handle is not None and getattr(candidate, "_hWnd", None) == handle:
                return candidate
            if title and str(getattr(candidate, "title", "") or "") == title:
                return candidate
    except Exception:
        return None
    return None


def running_windows_snapshot():
    if IS_WINDOWS:
        return running_windows_snapshot_windows()
    if shutil.which("wmctrl"):
        result = run_command("wmctrl -l -p -G", timeout_ms=3000)
        if result["ok"]:
            windows = parse_wmctrl_windows(result["stdout"])
            for window in windows:
                window["proc"] = proc_name_for_pid(window.get("pid"))
                window["app"] = wm_class_for_xid(window.get("xid")) or window.get("proc") or ""
            return windows
    return []


def recent_files_snapshot(limit=20, max_scan=2000):
    files = []
    scanned = 0
    for root in OBSERVE_FILE_DIRS:
        if not root.exists():
            continue
        try:
            iterator = root.rglob("*")
            for path in iterator:
                if scanned >= max_scan:
                    break
                scanned += 1
                try:
                    if not path.is_file():
                        continue
                    stat = path.stat()
                    files.append(
                        {
                            "path": str(path),
                            "size": stat.st_size,
                            "mtime": stat.st_mtime,
                            "modified_ago_sec": max(0, round(time.time() - stat.st_mtime, 3)),
                        }
                    )
                except Exception:
                    continue
        except Exception:
            continue
    files.sort(key=lambda item: item["mtime"], reverse=True)
    return files[: max(1, min(int(limit), 200))]


def file_stat_payload(raw_path):
    path = agent_path(raw_path)
    payload = {"ok": True, "path": str(path), "exists": path.exists()}
    if not path.exists():
        return payload
    stat = path.stat()
    payload.update(
        {
            "name": path.name,
            "parent": str(path.parent),
            "type": "directory" if path.is_dir() else "file",
            "size": stat.st_size,
            "mtime": int(stat.st_mtime),
            "mtime_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(stat.st_mtime)),
        }
    )
    return payload


def directory_listing_payload(raw_path, limit=100, create=False):
    root = agent_path(raw_path, default="/workspace")
    if create:
        root.mkdir(parents=True, exist_ok=True)
    payload = {"ok": True, "path": str(root), "exists": root.exists(), "entries": []}
    if not root.exists():
        return payload
    if not root.is_dir():
        payload.update({"ok": False, "error": "path is not a directory"})
        return payload
    entries = []
    for item in sorted(root.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))[: max(1, min(int(limit), 500))]:
        stat = item.stat()
        entries.append(
            {
                "name": item.name,
                "path": str(item),
                "type": "directory" if item.is_dir() else "file",
                "size": stat.st_size,
                "mtime": int(stat.st_mtime),
                "mtime_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(stat.st_mtime)),
            }
        )
    payload["entries"] = entries
    return payload


def parse_since_timestamp(value):
    if value in (None, ""):
        return 0.0
    text = str(value).strip()
    try:
        return float(text)
    except Exception:
        pass
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return time.mktime(time.strptime(text, fmt))
        except Exception:
            continue
    return 0.0


def find_recent_payload(raw_dir, since=0, limit=100):
    root = agent_path(raw_dir, default="/workspace")
    since_ts = parse_since_timestamp(since)
    payload = {"ok": True, "path": str(root), "exists": root.exists(), "since": since_ts, "entries": []}
    if not root.exists():
        return payload
    if not root.is_dir():
        payload.update({"ok": False, "error": "path is not a directory"})
        return payload
    entries = []
    for item in root.iterdir():
        try:
            stat = item.stat()
        except Exception:
            continue
        if stat.st_mtime < since_ts:
            continue
        entries.append(
            {
                "name": item.name,
                "path": str(item),
                "type": "directory" if item.is_dir() else "file",
                "size": stat.st_size,
                "mtime": int(stat.st_mtime),
                "mtime_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(stat.st_mtime)),
            }
        )
    entries.sort(key=lambda item: item["mtime"], reverse=True)
    payload["entries"] = entries[: max(1, min(int(limit), 500))]
    payload["count"] = len(payload["entries"])
    return payload


def text_matches_name(query, *values):
    q = str(query or "").strip().casefold()
    if not q:
        return False
    compact_q = re.sub(r"[^a-z0-9]+", "", q)
    for value in values:
        text = str(value or "").casefold()
        compact_text = re.sub(r"[^a-z0-9]+", "", text)
        if q in text or text in q:
            return True
        if compact_q and compact_q in compact_text:
            return True
    return False


def process_running_payload(name, limit=20):
    processes = running_processes_snapshot(limit=200)
    matches = [
        item
        for item in processes
        if text_matches_name(name, item.get("comm"), item.get("args"), item.get("proc"), item.get("name"))
    ]
    return {"ok": True, "name": str(name or ""), "running": bool(matches), "matches": matches[: max(1, min(int(limit), 100))]}


def window_open_payload(name, limit=20):
    windows = running_windows_snapshot()
    matches = [
        item
        for item in windows
        if text_matches_name(name, item.get("title"), item.get("app"), item.get("proc"))
    ]
    return {"ok": True, "name": str(name or ""), "open": bool(matches), "matches": matches[: max(1, min(int(limit), 100))]}


def app_installed_payload(name, limit=10):
    matches = search_installed_apps(name, limit=limit)
    installed = bool(matches) and (not name or matches[0].get("score", 0) > 0)
    if IS_WINDOWS and not installed and name:
        result = run_command(["where.exe", str(name)], timeout_ms=3000, shell=False)
        if result.get("ok") and result.get("stdout", "").strip():
            matches = [
                {
                    "name": str(name),
                    "exec": result.get("stdout", "").splitlines()[0].strip(),
                    "source": "where.exe",
                    "score": 1.0,
                }
            ]
            installed = True
    return {"ok": True, "name": str(name or ""), "installed": installed, "matches": matches[: max(1, min(int(limit), 100))]}


def running_processes_snapshot(exclude_pids=None, limit=50):
    exclude_pids = set(pid for pid in (exclude_pids or []) if pid)
    if IS_WINDOWS:
        result = run_command(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-Process | Select-Object Id,ProcessName,Path | ConvertTo-Json -Compress",
            ],
            timeout_ms=5000,
            shell=False,
        )
        if not result["ok"]:
            return []
        try:
            raw = json.loads(result["stdout"] or "[]")
        except Exception:
            return []
        if isinstance(raw, dict):
            raw = [raw]
        rows = []
        for item in raw:
            try:
                pid = int(item.get("Id"))
            except Exception:
                continue
            if pid in exclude_pids:
                continue
            rows.append(
                {
                    "pid": pid,
                    "comm": str(item.get("ProcessName") or ""),
                    "args": str(item.get("Path") or "")[:300],
                }
            )
        rows.sort(key=lambda item: item["pid"], reverse=True)
        return rows[: max(1, min(int(limit), 200))]
    result = run_command("ps -eo pid=,comm=,args=", timeout_ms=3000)
    if not result["ok"]:
        return []
    rows = []
    for line in result["stdout"].splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) < 2:
            continue
        try:
            pid = int(parts[0])
        except Exception:
            continue
        if pid in exclude_pids:
            continue
        comm = parts[1]
        args = parts[2] if len(parts) > 2 else ""
        if comm in ("ps", "sh", "bash", "zsh") or "computer_agent.py" in args:
            continue
        rows.append({"pid": pid, "comm": comm, "args": args[:300]})
    rows.sort(key=lambda item: item["pid"], reverse=True)
    return rows[: max(1, min(int(limit), 200))]


class Handler(BaseHTTPRequestHandler):
    server_version = "NubianUniversalComputerAgent/0.1"

    def log_message(self, fmt, *args):
        append_log({"event": "http_log", "client": self.client_address[0], "message": fmt % args})

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def read_json(self):
        length = int(self.headers.get("content-length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def send_json(self, payload, status=200):
        body = json_bytes(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, content_type="application/octet-stream", status=200, filename=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()
        self.wfile.write(body)
        try:
            self.wfile.flush()
        except Exception:
            pass

    def fail(self, message, status=500, **extra):
        payload = {"ok": False, "error": message}
        payload.update(extra)
        self.send_json(payload, status)

    def route_info(self):
        return {
            "ok": True,
            "service": "nubian-universal-computer-agent",
            "version": "0.1",
            "model": "eyes+hands+shell+memory+viewer",
            "routes": {
                "health": "GET /health",
                "codeact_contract": "GET /codeact",
                "codeact_screenshot": "GET /codeact/screenshot",
                "codeact_python": "POST /codeact/python",
                "codeact_ocr": "POST /codeact/ocr",
                "omniparser_contract": "GET /omniparser",
                "omniparser_parse": "POST /omniparser/parse",
                "omniparser_latest": "GET /omniparser/latest",
                "omniparser_som": "GET /omniparser/som",
                "omniparser_action": "POST /omniparser/action",
                "screenshot": "GET /eyes/screenshot",
                "ocr": "POST /eyes/ocr",
                "framebuffer": "GET /eyes/framebuffer",
                "accessibility": "GET /eyes/accessibility",
                "accessibility_overlay": "GET /eyes/accessibility-overlay?limit=120&labels=true",
                "accessibility_at_point": "GET /eyes/accessibility-at-point?x=100&y=100",
                "event_screenshot": "GET /logs/event-screenshots/<shot-id>.png or /eyes/event-screenshots/<shot-id>.png",
                "ocr_artifact": "GET /logs/ocr/<artifact>.png",
                "windows": "GET /eyes/windows",
                "input_events": "GET /eyes/input-events",
                "input_trace": "GET /eyes/input-trace?seconds=10",
                "input_devices": "GET /eyes/input-devices",
                "accessibility_events": "GET /eyes/accessibility-events",
                "merged_events": "GET /eyes/events",
                "observe": "GET /eyes/observe",
                "evidence": "GET /eyes/evidence?dir=/workspace&file=/workspace/result.svg",
                "process_running": "GET /system/process-running?name=gimp",
                "window_open": "GET /eyes/window-open?name=writer",
                "sql": "GET /eyes/sql?q=SELECT... or POST /eyes/sql",
                "event_index_status": "GET /eyes/events/index/status",
                "event_recent": "GET /eyes/events/recent?limit=50&app=Google-chrome",
                "event_around": "GET /eyes/events/around?seq=123&before=3&after=3",
                "event_active_window": "GET /eyes/events/active-window",
                "agent_context": "GET /eyes/events/context?limit=30",
                "pyautogui": "POST /hands/pyautogui",
                "python": "POST /hands/python",
                "structured_action": "POST /hands/action",
                "scroll": "POST /hands/scroll",
                "xdotool": "POST /hands/xdotool",
                "exec": "POST /shell/exec or POST /exec",
                "files_list": "GET /memory/files/list?path=/workspace",
                "files_stat": "GET /memory/files/stat?path=/workspace/result.md",
                "files_recent": "GET /memory/files/recent?dir=/downloads&since=1770000000",
                "files_read": "GET /memory/files?path=/workspace/result.md",
                "files_write": "POST /memory/files",
                "clipboard_read": "GET /memory/clipboard",
                "clipboard_write": "POST /memory/clipboard {\"text\":\"...\"}",
                "skills_list": "GET /skills",
                "skills_search": "GET /skills/search?q=create+spreadsheet+students",
                "skills_run": "POST /skills/run",
                "apps_list": "GET /apps",
                "apps_catalog": "GET /apps/catalog",
                "apps_names": "GET /apps/names",
                "apps_search": "GET /apps/search?q=spreadsheet",
                "apps_installed": "GET /apps/is-installed?name=libreoffice",
                "apps_launch": "POST /apps/launch",
                "windows_activate": "POST /windows/activate",
                "vnc": "GET /viewer/vnc",
                "logs": "GET /logs/actions",
            },
        }

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = urllib.parse.parse_qs(parsed.query)
        try:
            if path == "/":
                self.send_json(self.route_info())
            elif path == "/codeact":
                self.send_json(codeact_contract())
            elif path in ("/omniparser", "/omniparser/contract"):
                self.handle_omniparser_contract()
            elif path == "/omniparser/latest":
                self.handle_omniparser_latest()
            elif path == "/omniparser/som":
                self.handle_omniparser_som()
            elif path == "/health":
                self.handle_health()
            elif path in ("/eyes/screenshot", "/screenshot", "/codeact/screenshot"):
                append_log({"event": "screenshot"})
                wait_before_screenshot_api()
                self.send_bytes(capture_png_bytes(), "image/png")
            elif path.startswith("/eyes/event-screenshots/") or path.startswith("/logs/event-screenshots/"):
                self.handle_event_screenshot_file(path)
            elif path.startswith("/logs/ocr/"):
                self.handle_ocr_artifact_file(path)
            elif path in ("/eyes/ocr", "/codeact/ocr"):
                self.handle_ocr(query=query, allow_action=False)
            elif path in ("/eyes/framebuffer", "/eyes/screen"):
                self.handle_framebuffer()
            elif path == "/eyes/accessibility":
                status, content_type, body = proxy_get(OSWORLD_API.rstrip("/") + "/accessibility")
                self.send_bytes(body, content_type, status)
            elif path in ("/eyes/accessibility-overlay", "/eyes/a11y-overlay"):
                self.handle_accessibility_overlay(query)
            elif path in ("/eyes/accessibility-at-point", "/eyes/a11y-at-point"):
                self.handle_accessibility_at_point(query)
            elif path == "/eyes/windows":
                self.handle_windows(query)
            elif path == "/eyes/input-events":
                self.handle_event_log(query, INPUT_LOG, "input")
            elif path in ("/eyes/input-trace", "/eyes/mouse-trace", "/eyes/action-trace"):
                self.handle_input_trace(query)
            elif path == "/eyes/input-devices":
                self.handle_input_devices()
            elif path == "/eyes/accessibility-events":
                self.handle_event_log(query, ATSPI_LOG, "accessibility")
            elif path == "/eyes/events":
                self.handle_merged_events(query)
            elif path in ("/eyes/observe", "/screen/observe", "/observe"):
                self.handle_observe(query)
            elif path in ("/eyes/evidence", "/eyes/evidence-bundle", "/evidence"):
                self.handle_evidence(query)
            elif path in ("/eyes/window-open", "/windows/open", "/window_open"):
                self.handle_window_open(query)
            elif path in ("/system/process-running", "/process/running", "/process_running"):
                self.handle_process_running(query)
            elif path == "/eyes/sql":
                self.handle_sql_query(query)
            elif path == "/eyes/events/index/status":
                self.handle_event_index_status()
            elif path == "/eyes/events/recent":
                self.handle_event_index_recent(query)
            elif path == "/eyes/events/around":
                self.handle_event_index_around(query)
            elif path == "/eyes/events/active-window":
                self.handle_event_index_active_window()
            elif path == "/eyes/events/context":
                self.handle_event_context(query)
            elif path in ("/memory/files/list", "/files/list"):
                self.handle_files_list(query)
            elif path in ("/memory/files/stat", "/files/stat"):
                self.handle_file_stat(query)
            elif path in ("/memory/files/recent", "/files/recent", "/find_recent"):
                self.handle_find_recent(query)
            elif path in ("/memory/clipboard", "/memory/clipboard/text", "/clipboard", "/clipboard_text", "/eyes/clipboard"):
                self.handle_clipboard(query)
            elif path in ("/memory/files", "/files"):
                self.handle_file_read(query)
            elif path == "/skills":
                self.handle_skills_list(query)
            elif path == "/skills/search":
                self.handle_skills_search(query)
            elif path == "/apps":
                self.handle_apps_list(query)
            elif path in ("/apps/catalog", "/apps/names", "/apps/installed"):
                self.handle_apps_catalog(query)
            elif path in ("/apps/is-installed", "/apps/installed/check", "/app_installed"):
                self.handle_app_installed(query)
            elif path == "/apps/search":
                self.handle_apps_search(query)
            elif path == "/viewer/vnc":
                self.handle_viewer()
            elif path == "/logs/actions":
                self.handle_logs(query)
            elif path == "/browser/cdp/json/version":
                self.proxy_cdp("/json/version")
            elif path == "/browser/cdp/json/list":
                self.proxy_cdp("/json/list")
            else:
                self.fail("not found", 404, path=path)
        except urllib.error.HTTPError as exc:
            self.fail(str(exc), exc.code)
        except Exception as exc:
            self.fail(str(exc), 500, traceback=traceback.format_exc())

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        try:
            if path in ("/shell/exec", "/exec"):
                self.handle_exec()
            elif path == "/hands/pyautogui":
                self.handle_pyautogui()
            elif path in ("/hands/python", "/hands/code", "/hands/python/exec", "/hands/pyautogui-code"):
                self.handle_python_exec()
            elif path in ("/codeact", "/codeact/python", "/codeact/pyautogui"):
                self.handle_python_exec(mode="codeact", event_name="codeact_python")
            elif path in ("/codeact/cdp", "/codeact/browser"):
                self.handle_python_exec(mode="codeact", event_name="codeact_python")
            elif path == "/omniparser/parse":
                self.handle_omniparser_parse()
            elif path in ("/omniparser/action", "/omniparser/execute", "/omniparser/click_box"):
                self.handle_omniparser_action()
            elif path in ("/eyes/ocr", "/codeact/ocr"):
                self.handle_ocr(data=self.read_json(), allow_action=True)
            elif path == "/hands/action":
                self.handle_structured_action()
            elif path == "/hands/scroll":
                self.handle_scroll()
            elif path == "/hands/xdotool":
                self.handle_xdotool()
            elif path in ("/memory/clipboard", "/memory/clipboard/text", "/clipboard", "/clipboard_text", "/eyes/clipboard"):
                self.handle_clipboard_write()
            elif path in ("/memory/files", "/files"):
                self.handle_file_write()
            elif path == "/skills/run":
                self.handle_skill_run()
            elif path == "/apps/launch":
                self.handle_app_launch()
            elif path == "/windows/activate":
                self.handle_window_activate()
            elif path == "/eyes/sql":
                self.handle_sql_post()
            elif path == "/browser/cdp/command":
                self.fail("CDP command proxy is not enabled in this OSWorld image", 501)
            else:
                self.fail("not found", 404, path=path)
        except subprocess.TimeoutExpired as exc:
            self.fail("command timeout", 504, detail=str(exc))
        except Exception as exc:
            self.fail(str(exc), 500, traceback=traceback.format_exc())

    def handle_omniparser_contract(self):
        self.send_json(
            {
                "ok": True,
                "configured": bool(omniparser_parse_url()),
                "parse_url": omniparser_parse_url() or None,
                "contract": {
                    "parse": {
                        "method": "POST",
                        "path": "/omniparser/parse",
                        "runs_pipeline": True,
                        "body": {
                            "base64_image": "optional PNG/JPEG base64; omitted means capture current framebuffer",
                            "timeout_ms": 30000,
                            "cache": True,
                        },
                    },
                    "latest": {"method": "GET", "path": "/omniparser/latest", "runs_pipeline": False},
                    "som": {"method": "GET", "path": "/omniparser/som", "runs_pipeline": False},
                    "action": {
                        "method": "POST",
                        "path": "/omniparser/action",
                        "runs_pipeline": False,
                        "body": {
                            "action": "left_click | right_click | double_click | hover | type | key | scroll_up | scroll_down | wait | left_click_drag | None",
                            "box_id": "required for click/hover; optional for type; accepts Box ID too",
                            "text": "used for type; key/value used for key; to_box_id or to_x/to_y used for drag",
                        },
                    },
                },
                "note": "This VM wrapper does not load OmniParser. It proxies /parse to COMPUTER_AGENT_OMNIPARSER_URL and executes actions from the cached boxes.",
            }
        )

    def handle_omniparser_latest(self):
        payload = dict(LAST_OMNIPARSER_PARSE)
        payload["age_sec"] = round(time.time() - float(payload.get("t") or 0), 3) if payload.get("t") else None
        payload["has_som"] = bool(payload.get("som_image_base64"))
        if not bool_param(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("include_som", ["false"])[0], default=False):
            payload.pop("som_image_base64", None)
        self.send_json(payload, 200 if payload.get("ok") else 404)

    def handle_omniparser_som(self):
        som = LAST_OMNIPARSER_PARSE.get("som_image_base64")
        if not som:
            self.fail("no cached OmniParser SOM image; call POST /omniparser/parse first", 404)
            return
        try:
            self.send_bytes(base64.b64decode(som), "image/png")
        except Exception as exc:
            self.fail("cached SOM image is invalid base64", 500, detail=str(exc))

    def handle_omniparser_parse(self):
        parse_url = omniparser_parse_url()
        if not parse_url:
            self.fail(
                "OmniParser server is not configured",
                503,
                env="COMPUTER_AGENT_OMNIPARSER_URL",
                expected="http://<gpu-host>:<port>/parse/",
            )
            return
        data = self.read_json()
        timeout_ms = safe_timeout_ms(data.get("timeout_ms", data.get("timeout", 30000)))
        start = time.time()
        width = data.get("width")
        height = data.get("height")
        base64_image = data.get("base64_image") or data.get("image_base64")
        if not base64_image:
            image = capture_framebuffer_image().convert("RGB")
            width, height = image.size
            base64_image = image_to_base64_png(image)
        else:
            if not width or not height:
                try:
                    from PIL import Image

                    raw = base64.b64decode(str(base64_image).split(",", 1)[-1])
                    with Image.open(io.BytesIO(raw)) as img:
                        width, height = img.size
                except Exception:
                    width, height = framebuffer_size()
        width, height = int(width), int(height)
        response = post_json_to_url(parse_url, {"base64_image": base64_image}, timeout=max(1, timeout_ms / 1000.0))
        parsed = response.get("parsed_content_list") or response.get("elements") or response.get("boxes") or []
        boxes = normalize_omniparser_boxes(parsed, width, height)
        payload = {
            "ok": True,
            "source": parse_url,
            "width": width,
            "height": height,
            "count": len(boxes),
            "boxes": boxes,
            "screen_info": omniparser_screen_info(boxes),
            "latency": response.get("latency"),
            "elapsed_ms": int((time.time() - start) * 1000),
            "raw_keys": sorted(response.keys()) if isinstance(response, dict) else [],
        }
        if response.get("som_image_base64"):
            payload["som_image_base64"] = response.get("som_image_base64")
        if bool_param(data.get("cache"), default=True):
            LAST_OMNIPARSER_PARSE.clear()
            LAST_OMNIPARSER_PARSE.update(payload)
            LAST_OMNIPARSER_PARSE["t"] = time.time()
        if not bool_param(data.get("include_som"), default=False):
            payload.pop("som_image_base64", None)
        self.send_json(payload)

    def handle_omniparser_action(self):
        data = self.read_json()
        action = data.get("action", data.get("Next Action", data.get("type", "")))
        box_id = data.get("box_id", data.get("Box ID", data.get("box", data.get("id"))))
        text = data.get("text", data.get("value", data.get("key", "")))
        box = cached_omniparser_box(box_id) if box_id is not None else None
        pyautogui = import_pyautogui()
        if str(action or "").strip().lower() == "left_click_drag":
            to_box_id = data.get("to_box_id", data.get("target_box_id", data.get("to_box")))
            to_box = cached_omniparser_box(to_box_id) if to_box_id is not None else None
            if not box:
                result = {"ok": False, "error": "left_click_drag requires box_id/from_box_id"}
            elif to_box:
                sx, sy = box["center"]
                ex, ey = to_box["center"]
                sdx, sdy = framebuffer_to_display_point(sx, sy)
                edx, edy = framebuffer_to_display_point(ex, ey)
                pointer_bridge_to(pyautogui, sx, sy)
                pyautogui.moveTo(sdx, sdy, duration=0)
                pyautogui.dragTo(edx, edy, duration=float(data.get("duration", 0.3)), button="left")
                remember_pointer_event("drag", ex, ey)
                result = {"ok": True, "action": "left_click_drag", "from": box, "to": to_box, "center": [ex, ey]}
            elif data.get("to_x") is not None and data.get("to_y") is not None:
                sx, sy = box["center"]
                ex, ey = clamp_framebuffer_point(data.get("to_x"), data.get("to_y"))
                sdx, sdy = framebuffer_to_display_point(sx, sy)
                edx, edy = framebuffer_to_display_point(ex, ey)
                pointer_bridge_to(pyautogui, sx, sy)
                pyautogui.moveTo(sdx, sdy, duration=0)
                pyautogui.dragTo(edx, edy, duration=float(data.get("duration", 0.3)), button="left")
                remember_pointer_event("drag", ex, ey)
                result = {"ok": True, "action": "left_click_drag", "from": box, "to": [ex, ey], "center": [ex, ey]}
            else:
                result = {"ok": False, "error": "left_click_drag requires to_box_id or to_x/to_y"}
        else:
            result = apply_omniparser_box_action(pyautogui, action, box=box, text=text, button=data.get("button", "left"))
        payload = {
            "ok": bool(result.get("ok")),
            "action": action,
            "box_id": box_id,
            "result": result,
            "cache_age_sec": round(time.time() - float(LAST_OMNIPARSER_PARSE.get("t") or 0), 3)
            if LAST_OMNIPARSER_PARSE.get("t")
            else None,
        }
        self.send_json(payload, 200 if payload["ok"] else 400)

    def handle_health(self):
        checks = {}
        checks["platform"] = VNC_TARGET_PLATFORM if VNC_BACKEND_ENABLED else PLATFORM_SYSTEM or os.name
        checks["backend"] = BACKEND
        checks["is_windows"] = IS_WINDOWS
        checks["display"] = None if (IS_WINDOWS or VNC_BACKEND_ENABLED) else os.environ.get("DISPLAY", DISPLAY)
        fb = framebuffer_geometry()
        checks["framebuffer"] = bool(fb.get("ok"))
        checks["framebuffer_geometry"] = fb
        for root in (DEFAULT_WORKSPACE_DIR, DEFAULT_DOWNLOADS_DIR, DEFAULT_UPLOADS_DIR, LOG_DIR):
            try:
                root.mkdir(parents=True, exist_ok=True)
            except Exception:
                pass
        checks["workspace_path"] = str(DEFAULT_WORKSPACE_DIR)
        checks["downloads_path"] = str(DEFAULT_DOWNLOADS_DIR)
        checks["uploads_path"] = str(DEFAULT_UPLOADS_DIR)
        checks["logs_path"] = str(LOG_DIR)
        checks["workspace_writable"] = os.access(DEFAULT_WORKSPACE_DIR, os.W_OK)
        checks["downloads_writable"] = os.access(DEFAULT_DOWNLOADS_DIR, os.W_OK)
        checks["uploads_writable"] = os.access(DEFAULT_UPLOADS_DIR, os.W_OK)
        checks["logs_writable"] = os.access(LOG_DIR, os.W_OK)
        checks["python"] = sys.version.split()[0]
        checks["xdotool"] = False if (IS_WINDOWS or VNC_BACKEND_ENABLED) else bool(shutil.which("xdotool"))
        checks["wmctrl"] = False if (IS_WINDOWS or VNC_BACKEND_ENABLED) else bool(shutil.which("wmctrl"))
        checks["xprop"] = False if (IS_WINDOWS or VNC_BACKEND_ENABLED) else bool(shutil.which("xprop"))
        checks["xinput"] = False if (IS_WINDOWS or VNC_BACKEND_ENABLED) else bool(shutil.which("xinput"))
        checks["pygetwindow"] = False
        checks["vnc_backend"] = VNC_BACKEND_ENABLED
        checks["vnc_server"] = VNC_SERVER if VNC_BACKEND_ENABLED else None
        checks["vncdo"] = VNCDO if VNC_BACKEND_ENABLED else None
        checks["clipboard"] = bool(shutil.which("xclip") or shutil.which("xsel"))
        checks["window_log"] = WINDOW_LOG.exists()
        checks["input_log"] = INPUT_LOG.exists()
        checks["atspi_log"] = ATSPI_LOG.exists()
        checks["event_db"] = EVENT_DB.exists()
        checks["skills_db"] = SKILLS_DB.exists()
        checks["skills_count"] = 0
        checks["duckdb"] = False
        checks["pyautogui"] = False
        checks["screenshot"] = False
        checks["pyatspi"] = False
        checks["paddleocr"] = False
        if VNC_BACKEND_ENABLED:
            checks["pyautogui"] = False
            checks["pyautogui_error"] = "not used by vnc backend"
        else:
            try:
                import_pyautogui()
                checks["pyautogui"] = True
            except Exception as exc:
                checks["pyautogui_error"] = str(exc)
        if VNC_BACKEND_ENABLED:
            checks["pyatspi_error"] = "AT-SPI is not used by vnc backend"
        elif IS_WINDOWS:
            try:
                import pygetwindow  # noqa: F401

                checks["pygetwindow"] = True
            except Exception as exc:
                checks["pygetwindow_error"] = str(exc)
            checks["clipboard"] = True
            checks["pyatspi_error"] = "AT-SPI is Linux-only"
        else:
            try:
                import pyatspi  # noqa: F401

                checks["pyatspi"] = True
            except Exception as exc:
                checks["pyatspi_error"] = str(exc)
        try:
            import duckdb  # noqa: F401

            checks["duckdb"] = True
        except Exception as exc:
            checks["duckdb_error"] = str(exc)
        try:
            import paddleocr  # noqa: F401

            checks["paddleocr"] = True
        except Exception as exc:
            checks["paddleocr_error"] = str(exc)
        try:
            ensure_skills_db()
            checks["skills_db"] = SKILLS_DB.exists()
            checks["skills_count"] = len(list_skills())
        except Exception as exc:
            checks["skills_error"] = str(exc)
        try:
            checks["screenshot"] = len(capture_png_bytes()) > 0
        except Exception as exc:
            checks["screenshot_error"] = str(exc)

        self.send_json(
            {
                "ok": bool(checks["workspace_writable"] and (checks["pyautogui"] or (VNC_BACKEND_ENABLED and checks["screenshot"]))),
                "service": "nubian-universal-computer-agent",
                "version": "0.1",
                "platform": checks["platform"],
                "display": checks["display"],
                "framebuffer": fb,
                "checks": checks,
                "ports": {
                    "agent_internal": PORT,
                    "agent_public": PUBLIC_AGENT_PORT,
                    "vnc_public": PUBLIC_VNC_PORT,
                },
                "urls": {
                    "agent_public": public_agent_base_url(),
                    "vnc_public": f"http://{PUBLIC_HOST}:{PUBLIC_VNC_PORT}/",
                    "screenshot": public_agent_base_url().rstrip("/") + "/eyes/screenshot",
                },
            }
        )

    def handle_framebuffer(self):
        fb = framebuffer_geometry()
        display_w, display_h = display_geometry()
        mouse = current_mouse_location()
        display_mouse = current_mouse_location_display()
        self.send_json(
            {
                "ok": bool(fb.get("width") and fb.get("height")),
                "framebuffer": fb,
                "display_geometry": {
                    "width": display_w,
                    "height": display_h,
                    "source": "vnc_capture" if VNC_BACKEND_ENABLED else "pyautogui" if IS_WINDOWS else "xdotool_or_xdpyinfo",
                },
                "mouse": {"x": mouse[0], "y": mouse[1]} if mouse else None,
                "display_mouse": {"x": display_mouse[0], "y": display_mouse[1]} if display_mouse else None,
                "coordinate_space": {
                    "x_min": 0,
                    "y_min": 0,
                    "x_max": max(0, int(fb.get("width", display_w)) - 1),
                    "y_max": max(0, int(fb.get("height", display_h)) - 1),
                },
            },
            200 if fb.get("ok") else 500,
        )

    def handle_ocr_artifact_file(self, path):
        filename = urllib.parse.unquote(path.rsplit("/", 1)[-1])
        if not filename or "/" in filename or "\\" in filename or not re.match(r"^[A-Za-z0-9_.-]+$", filename):
            self.fail("invalid ocr artifact filename", 400)
            return
        file_path = OCR_LOG_DIR / filename
        try:
            resolved = file_path.resolve()
            root = OCR_LOG_DIR.resolve()
            if root not in resolved.parents and resolved != root:
                self.fail("invalid ocr artifact path", 400)
                return
        except Exception:
            self.fail("invalid ocr artifact path", 400)
            return
        if not file_path.exists():
            self.fail("ocr artifact not found", 404, path=str(file_path))
            return
        content_type = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
        self.send_bytes(file_path.read_bytes(), content_type, filename=filename)

    def handle_ocr(self, query=None, data=None, allow_action=True):
        query = query or {}
        data = data if isinstance(data, dict) else {key: values[0] for key, values in query.items() if values}
        start = time.time()
        timings = {}
        OCR_LOG_DIR.mkdir(parents=True, exist_ok=True)
        stamp = now_ms()

        capture_start = time.time()
        image = capture_framebuffer_image().convert("RGB")
        timings["capture_ms"] = int((time.time() - capture_start) * 1000)
        width, height = image.size
        crop_bbox = parse_crop_bbox(data, query, width, height)
        left, top, right, bottom = crop_bbox
        crop = image.crop((left, top, right, bottom))

        crop_name = f"ocr-{stamp}-crop.png"
        crop_path = OCR_LOG_DIR / crop_name
        crop.save(crop_path)

        model = data.get("model", data.get("preset", "mobile"))
        try:
            ocr, init_ms, cached_model = get_paddle_ocr(model=model, data=data)
        except ImportError as exc:
            self.fail(
                "PaddleOCR is not installed in this guest Python",
                503,
                detail=str(exc),
                install="python3 -m pip install --user paddleocr paddlepaddle",
            )
            return

        ocr_start = time.time()
        result = ocr.predict(str(crop_path))
        timings["ocr_predict_ms"] = int((time.time() - ocr_start) * 1000)
        timings["ocr_init_ms"] = init_ms
        items = paddle_ocr_items(result, origin=(left, top))

        target_text = data.get("target_text", data.get("target", data.get("text", "")))
        match_mode = data.get("match", data.get("match_mode", "contains"))
        match = find_ocr_target(items, target_text, match_mode=match_mode) if target_text else None

        annotated = draw_ocr_annotation(image, items, match=match, crop_bbox=crop_bbox)
        annotated_name = f"ocr-{stamp}-annotated.png"
        annotated_path = OCR_LOG_DIR / annotated_name
        annotated.save(annotated_path)

        crop_annotated_name = f"ocr-{stamp}-crop-annotated.png"
        crop_annotated_path = OCR_LOG_DIR / crop_annotated_name
        annotated.crop((left, top, right, bottom)).save(crop_annotated_path)

        action = str(data.get("target_action", data.get("action", data.get("do", "none"))) or "none").lower()
        action_result = {"ok": True, "action": "none"}
        if action in ("", "none", "ocr", "detect"):
            action = "none"
        elif not allow_action:
            action_result = {"ok": False, "action": action, "error": "actions are only allowed with POST"}
        elif not match:
            action_result = {"ok": False, "action": action, "error": "target text not found"}
        elif action == "type" and data.get("type_text", data.get("text_to_type")) is None:
            action_result = {"ok": False, "action": action, "error": "target_action='type' requires target_text/type_text"}
        else:
            pyautogui = import_pyautogui()
            cx, cy = match["center"]
            dx, dy = framebuffer_to_display_point(cx, cy)
            click_start = time.time()
            click_points = []
            hover_result = None
            hover_snap = None
            center_snap = None
            if action in ("click", "tap", "left_click"):
                click_result = pyautogui_fat_click(
                    pyautogui,
                    cx,
                    cy,
                    clicks=int(data.get("clicks", 1)),
                    button=str(data.get("button", "left")),
                    enabled=bool_param(data.get("fat_click"), default=FAT_CLICK_ENABLED),
                    radius=int(data.get("fat_click_radius", FAT_CLICK_RADIUS)),
                    delay=float(data.get("fat_click_delay", FAT_CLICK_DELAY)),
                )
                click_points = click_result["points"]
                hover_result = click_result.get("hover_probe")
                hover_snap = click_result.get("hover_snap")
                center_snap = click_result.get("center_snap")
            elif action in ("double_click", "dblclick"):
                pointer_bridge_to(pyautogui, cx, cy)
                pyautogui_human_click(pyautogui, dx, dy, clicks=2, button=str(data.get("button", "left")))
                click_points = [{"x": int(cx), "y": int(cy)}]
            elif action in ("right_click", "context_click"):
                pointer_bridge_to(pyautogui, cx, cy)
                pyautogui_human_click(pyautogui, dx, dy, button="right")
                click_points = [{"x": int(cx), "y": int(cy)}]
            elif action in ("move", "move_to", "hover"):
                pointer_bridge_to(pyautogui, cx, cy, duration=float(data.get("duration", POINTER_BRIDGE_MOVE_DURATION)))
                pyautogui.moveTo(dx, dy, duration=float(data.get("duration", 0)))
            elif action == "type":
                pointer_bridge_to(pyautogui, cx, cy)
                pyautogui_human_click(pyautogui, dx, dy)
                click_points = [{"x": int(cx), "y": int(cy)}]
                type_text_via_clipboard_or_pyautogui(pyautogui, data.get("type_text", data.get("text_to_type")))
            else:
                action_result = {"ok": False, "action": action, "error": "unsupported target_action"}
            if action_result.get("ok", True):
                LAST_POINTER_EVENT.update({"kind": f"ocr_{action}", "x": cx, "y": cy, "t": time.time()})
                action_result = {
                    "ok": True,
                    "action": action,
                    "x": cx,
                    "y": cy,
                    "display_x": dx,
                    "display_y": dy,
                    "click_points": click_points,
                    "center_snap": center_snap,
                    "hover_probe": hover_result,
                    "hover_snap": hover_snap,
                    "fat_click": bool(action in ("click", "tap", "left_click") and len(click_points) > 1),
                    "elapsed_ms": int((time.time() - click_start) * 1000),
                }

        text_lines = [f"{item['bbox'][0]} {item['bbox'][1]} {item['bbox'][2]} {item['bbox'][3]} -> {item['text']}" for item in items]
        txt_name = f"ocr-{stamp}.txt"
        txt_path = OCR_LOG_DIR / txt_name
        txt_path.write_text("\n".join(text_lines) + ("\n" if text_lines else ""), encoding="utf-8")

        payload = {
            "ok": bool((not target_text or match) and action_result.get("ok")),
            "engine": "paddleocr",
            "model": model,
            "cached_model": cached_model,
            "screen": {"width": width, "height": height},
            "crop": {
                "bbox": crop_bbox,
                "width": right - left,
                "height": bottom - top,
                "path": str(crop_path),
                "url": ocr_artifact_url(crop_name),
            },
            "target_text": target_text,
            "match_mode": match_mode,
            "match": match,
            "action_result": action_result,
            "count": len(items),
            "items": items,
            "artifacts": {
                "text": {"path": str(txt_path), "url": ocr_artifact_url(txt_name)},
                "annotated": {"path": str(annotated_path), "url": ocr_artifact_url(annotated_name)},
                "crop_annotated": {"path": str(crop_annotated_path), "url": ocr_artifact_url(crop_annotated_name)},
            },
            "timings": timings,
            "elapsed_ms": int((time.time() - start) * 1000),
        }
        json_name = f"ocr-{stamp}.json"
        json_path = OCR_LOG_DIR / json_name
        payload["artifacts"]["json"] = {"path": str(json_path), "url": ocr_artifact_url(json_name)}
        json_path.write_text(json.dumps(json_safe(payload), indent=2, ensure_ascii=False), encoding="utf-8")
        append_log(
            {
                "event": "paddleocr",
                "crop_bbox": crop_bbox,
                "target_text": target_text,
                "action": action,
                "match": match,
                "action_result": action_result,
                "elapsed_ms": payload["elapsed_ms"],
            }
        )
        self.send_json(payload, 200 if payload["ok"] else 404)

    def handle_input_devices(self):
        devices = xinput_device_map()
        self.send_json(
            {
                "ok": True,
                "devices": list(devices.values()),
                "by_id": {str(device_id): info for device_id, info in devices.items()},
                "instruction": "Join input events on event.device == devices[*].id. use=pointer means mouse/tablet, use=keyboard means keyboard.",
            }
        )

    def handle_event_screenshot_file(self, path):
        prune_event_screenshots()
        filename = urllib.parse.unquote(path.rsplit("/", 1)[-1])
        if not filename or "/" in filename or "\\" in filename or not filename.endswith(".png"):
            self.fail("invalid screenshot filename", 400)
            return
        file_path = EVENT_SCREENSHOT_DIR / filename
        try:
            resolved = file_path.resolve()
            root = EVENT_SCREENSHOT_DIR.resolve()
            if root not in resolved.parents and resolved != root:
                self.fail("invalid screenshot path", 400)
                return
        except Exception:
            self.fail("invalid screenshot path", 400)
            return
        if not file_path.exists():
            self.fail("screenshot not found or expired", 404)
            return
        self.send_bytes(file_path.read_bytes(), "image/png", filename=filename)

    def atspi_object_info(self, obj, pyatspi):
        def safe_call(fn, default=None):
            try:
                value = fn()
                return default if value is None else value
            except Exception:
                return default

        app = safe_call(lambda: obj.getApplication(), None)
        info = {
            "app": safe_call(lambda: app.name, ""),
            "role": safe_call(lambda: obj.getRoleName(), ""),
            "name": safe_call(lambda: obj.name, ""),
            "description": safe_call(lambda: obj.description, ""),
            "child_count": safe_call(lambda: obj.childCount, 0),
            "interfaces": [],
        }
        component = safe_call(lambda: obj.queryComponent(), None)
        if component:
            info["interfaces"].append("component")
            extents = safe_call(lambda: component.getExtents(pyatspi.DESKTOP_COORDS), None)
            if extents:
                info["x"] = getattr(extents, "x", None)
                info["y"] = getattr(extents, "y", None)
                info["w"] = getattr(extents, "width", None)
                info["h"] = getattr(extents, "height", None)
                info["bbox"] = [info.get("x"), info.get("y"), info.get("w"), info.get("h")]
        text_iface = safe_call(lambda: obj.queryText(), None)
        if text_iface:
            info["interfaces"].append("text")
            char_count = safe_call(lambda: text_iface.characterCount, 0)
            sample_end = min(int(char_count or 0), 500)
            info["text_chars"] = char_count
            info["text_sample"] = safe_call(lambda: text_iface.getText(0, sample_end), "")
            info["caret_offset"] = safe_call(lambda: text_iface.caretOffset, None)
        action_iface = safe_call(lambda: obj.queryAction(), None)
        if action_iface:
            info["interfaces"].append("action")
            count = safe_call(lambda: action_iface.nActions, 0)
            actions = []
            for i in range(min(int(count or 0), 20)):
                actions.append(
                    {
                        "index": i,
                        "name": safe_call(lambda i=i: action_iface.getName(i), ""),
                        "description": safe_call(lambda i=i: action_iface.getDescription(i), ""),
                        "key_binding": safe_call(lambda i=i: action_iface.getKeyBinding(i), ""),
                    }
                )
            info["actions"] = actions
        return info

    def accessibility_overlay_items(self, image_size, limit=120, active_only=False):
        if IS_WINDOWS:
            return []
        import pyatspi

        width, height = image_size
        windows = running_windows_snapshot()
        active_window = self.active_window_from_xprop(windows)
        active_bbox = None
        if active_window and active_window.get("w") and active_window.get("h"):
            ax, ay = display_to_framebuffer_point(active_window.get("x", 0), active_window.get("y", 0))
            ar, ab = display_to_framebuffer_point(
                int(active_window.get("x", 0)) + int(active_window.get("w", 0)),
                int(active_window.get("y", 0)) + int(active_window.get("h", 0)),
            )
            active_bbox = [min(ax, ar), min(ay, ab), max(ax, ar), max(ay, ab)]

        interesting_roles = {
            "alert",
            "check box",
            "combo box",
            "dialog",
            "entry",
            "frame",
            "link",
            "list item",
            "menu",
            "menu bar",
            "menu item",
            "page tab",
            "push button",
            "radio button",
            "slider",
            "spin button",
            "table cell",
            "terminal",
            "text",
            "toggle button",
            "tool bar",
            "tool tip",
            "tree item",
        }
        control_roles = interesting_roles - {"frame", "tool bar", "menu bar", "terminal"}
        items = []
        visited = set()
        desktop = pyatspi.Registry.getDesktop(0)

        def safe_call(fn, default=None):
            try:
                value = fn()
                return default if value is None else value
            except Exception:
                return default

        def intersects(a, b):
            return a[0] < b[2] and a[2] > b[0] and a[1] < b[3] and a[3] > b[1]

        def add_obj(obj, depth):
            role = str(safe_call(lambda: obj.getRoleName(), "") or "")
            name = str(safe_call(lambda: obj.name, "") or "")
            description = str(safe_call(lambda: obj.description, "") or "")
            child_count = int(safe_call(lambda: obj.childCount, 0) or 0)
            if role not in interesting_roles and not name:
                return
            component = safe_call(lambda: obj.queryComponent(), None)
            if not component:
                return
            extents = safe_call(lambda: component.getExtents(pyatspi.DESKTOP_COORDS), None)
            if not extents:
                return
            x = int(getattr(extents, "x", 0) or 0)
            y = int(getattr(extents, "y", 0) or 0)
            w = int(getattr(extents, "width", 0) or 0)
            h = int(getattr(extents, "height", 0) or 0)
            if w <= 1 or h <= 1:
                return
            x1, y1 = display_to_framebuffer_point(x, y)
            x2, y2 = display_to_framebuffer_point(x + w, y + h)
            left, top, right, bottom = min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)
            if right <= 0 or bottom <= 0 or left >= width or top >= height:
                return
            bbox = [max(0, left), max(0, top), min(width - 1, right), min(height - 1, bottom)]
            if active_only and active_bbox and not intersects(bbox, active_bbox):
                return
            bw, bh = bbox[2] - bbox[0], bbox[3] - bbox[1]
            area = bw * bh
            if area <= 12:
                return
            full_screenish = bw > width * 0.95 and bh > height * 0.95
            if full_screenish and role not in ("dialog", "frame"):
                return
            action_iface = safe_call(lambda: obj.queryAction(), None)
            action_count = int(safe_call(lambda: action_iface.nActions, 0) or 0) if action_iface else 0
            text_iface = safe_call(lambda: obj.queryText(), None)
            text_sample = ""
            if text_iface:
                char_count = int(safe_call(lambda: text_iface.characterCount, 0) or 0)
                if char_count:
                    text_sample = str(safe_call(lambda: text_iface.getText(0, min(char_count, 80)), "") or "")
            score = 0
            if role in control_roles:
                score += 50
            if name:
                score += 20
            if action_count:
                score += 20
            if text_sample:
                score += 10
            if role in ("frame", "dialog"):
                score += 8
            score -= min(depth, 30)
            items.append(
                {
                    "bbox": bbox,
                    "role": role,
                    "name": name,
                    "description": description,
                    "text": text_sample,
                    "actions": action_count,
                    "child_count": child_count,
                    "depth": depth,
                    "area": area,
                    "score": score,
                }
            )

        def walk(obj, depth=0):
            if obj is None or depth > 18 or len(visited) > 3000:
                return
            key = repr(obj)
            if key in visited:
                return
            visited.add(key)
            add_obj(obj, depth)
            child_count = int(safe_call(lambda: obj.childCount, 0) or 0)
            for index in range(min(child_count, 400)):
                child = safe_call(lambda index=index: obj[index], None)
                if child is not None:
                    walk(child, depth + 1)

        walk(desktop)
        items.sort(key=lambda item: (item["score"], -item["area"]), reverse=True)
        deduped = []
        seen = set()
        for item in items:
            key = tuple(item["bbox"] + [item["role"], item["name"][:40]])
            if key in seen:
                continue
            seen.add(key)
            deduped.append(item)
            if len(deduped) >= int(limit):
                break
        return deduped

    def handle_accessibility_overlay(self, query):
        if IS_WINDOWS:
            self.fail("accessibility overlay is not implemented on Windows yet", 501, platform="windows")
            return
        try:
            image = overlay_cursor_marker(capture_framebuffer_image()).convert("RGB")
            width, height = image.size
            limit = int(query.get("limit", ["120"])[0])
            labels = str(query.get("labels", ["true"])[0]).strip().lower() not in ("0", "false", "no", "off")
            active_only = str(query.get("active_only", ["false"])[0]).strip().lower() in ("1", "true", "yes", "on")
            items = self.accessibility_overlay_items((width, height), limit=max(1, min(limit, 250)), active_only=active_only)
            from PIL import ImageDraw, ImageFont

            draw = ImageDraw.Draw(image)
            font = ImageFont.load_default()
            palette = {
                "push button": (0, 255, 255),
                "toggle button": (0, 255, 255),
                "entry": (255, 230, 0),
                "text": (255, 230, 0),
                "spin button": (255, 230, 0),
                "combo box": (255, 128, 0),
                "menu": (255, 0, 255),
                "menu item": (255, 0, 255),
                "page tab": (0, 255, 120),
                "check box": (0, 255, 120),
                "radio button": (0, 255, 120),
                "dialog": (255, 80, 80),
                "frame": (120, 180, 255),
            }
            for index, item in enumerate(items, 1):
                x1, y1, x2, y2 = item["bbox"]
                color = palette.get(item["role"], (120, 180, 255))
                draw.rectangle((x1, y1, x2, y2), outline=(0, 0, 0), width=3)
                draw.rectangle((x1, y1, x2, y2), outline=color, width=1)
                if labels:
                    raw_label = item.get("name") or item.get("text") or item.get("description") or item.get("role") or ""
                    raw_label = " ".join(str(raw_label).split())
                    label = f"{index}:{item['role']}"
                    if raw_label:
                        label += f" {raw_label[:36]}"
                    tx, ty = x1 + 2, max(0, y1 - 12)
                    try:
                        box = draw.textbbox((tx, ty), label, font=font)
                    except Exception:
                        box = (tx, ty, tx + len(label) * 6, ty + 10)
                    draw.rectangle((box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1), fill=(0, 0, 0))
                    draw.text((tx, ty), label, fill=color, font=font)
            append_log({"event": "accessibility_overlay", "items": len(items), "limit": limit, "active_only": active_only})
            out = io.BytesIO()
            image.save(out, format="PNG")
            self.send_bytes(out.getvalue(), "image/png")
        except Exception as exc:
            self.fail("accessibility overlay failed", 500, detail=str(exc))

    def deepest_accessible_at_point(self, x, y):
        if IS_WINDOWS:
            raise RuntimeError("accessibility-at-point is not implemented on Windows yet")
        import pyatspi

        x, y = framebuffer_to_display_point(x, y)
        desktop = pyatspi.Registry.getDesktop(0)
        best = desktop
        visited = set()

        def descend(obj, depth=0):
            nonlocal best
            if obj is None or depth > 20:
                return
            key = repr(obj)
            if key in visited:
                return
            visited.add(key)
            best = obj
            try:
                component = obj.queryComponent()
                child = component.getAccessibleAtPoint(int(x), int(y), pyatspi.DESKTOP_COORDS)
            except Exception:
                child = None
            if child is not None and repr(child) not in visited:
                descend(child, depth + 1)

        descend(desktop)
        return best, pyatspi

    def handle_accessibility_at_point(self, query):
        if IS_WINDOWS:
            self.fail("accessibility at point is not implemented on Windows yet", 501, platform="windows")
            return
        mouse = current_mouse_location()
        try:
            x = int(float(query.get("x", [mouse[0] if mouse else 0])[0]))
            y = int(float(query.get("y", [mouse[1] if mouse else 0])[0]))
        except Exception:
            self.fail("x/y must be numbers", 400)
            return
        try:
            obj, pyatspi = self.deepest_accessible_at_point(x, y)
            self.send_json(
                {
                    "ok": True,
                    "x": x,
                    "y": y,
                    "mouse": {"x": mouse[0], "y": mouse[1]} if mouse else None,
                    "accessible": self.atspi_object_info(obj, pyatspi),
                    "instruction": "This is the deepest AT-SPI object under the desktop coordinate. Some apps expose richer A11 data than others.",
                }
            )
        except Exception as exc:
            self.fail("accessibility at point failed", 500, detail=str(exc))

    def handle_exec(self):
        data = self.read_json()
        cmd = data.get("cmd", data.get("command", ""))
        shell = bool(data.get("shell", True))
        cwd = data.get("cwd", data.get("working_dir", "/workspace"))
        timeout_ms = safe_timeout_ms(data.get("timeout_ms", data.get("timeout", 30000)))
        append_log({"event": "exec", "cmd": cmd, "cwd": cwd, "timeout_ms": timeout_ms})
        result = run_command(cmd, cwd=cwd, timeout_ms=timeout_ms, shell=shell)
        self.send_json(result, 200 if result["ok"] else 500)

    def handle_pyautogui(self):
        if VNC_BACKEND_ENABLED:
            self.fail("pyautogui execution is unavailable in vnc backend; use /hands/action", 501, backend=BACKEND)
            return
        data = self.read_json()
        action = data.get("action", "")
        if not action:
            self.fail("missing action", 400)
            return
        append_log({"event": "pyautogui", "action": action})
        start = time.time()
        pyautogui = import_pyautogui()
        namespace = {
            "pyautogui": pyautogui,
            "time": time,
            "os": os,
            "subprocess": subprocess,
            "pathlib": pathlib,
        }
        exec(action, namespace, namespace)
        self.send_json({"ok": True, "elapsed_ms": int((time.time() - start) * 1000)})

    def read_python_exec_request(self):
        length = int(self.headers.get("content-length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        text = raw.decode("utf-8", errors="replace")
        content_type = self.headers.get("content-type", "").lower()
        data = {}
        code_value = text
        if "json" in content_type or text.lstrip().startswith(("{", "[")):
            parsed = json.loads(text or "{}")
            if isinstance(parsed, dict):
                data = parsed
                code_value = (
                    parsed.get("code")
                    or parsed.get("python")
                    or parsed.get("script")
                    or parsed.get("action")
                    or parsed.get("content")
                    or ""
                )
            else:
                code_value = parsed
        code = extract_python_code(code_value)
        return data, code

    def handle_python_exec(self, mode="pyautogui", event_name="python_exec"):
        if VNC_BACKEND_ENABLED:
            self.fail("python execution is unavailable in vnc backend; use /hands/action", 501, backend=BACKEND)
            return
        data, code = self.read_python_exec_request()
        if not code:
            self.fail("missing python code; send JSON {\"code\":\"...\"} or raw Python text", 400)
            return
        if mode not in ("pyautogui", "cdp", "codeact"):
            self.fail("invalid codeact mode", 400, mode=mode)
            return
        timeout_ms = safe_timeout_ms(data.get("timeout_ms", data.get("timeout", 10000)), default=10000)
        setup_timeout_ms = safe_timeout_ms(data.get("setup_timeout_ms", 120000), default=120000)
        cwd = agent_path_string(data.get("cwd", data.get("working_dir", "/workspace")) or "/workspace")
        pathlib.Path(cwd).mkdir(parents=True, exist_ok=True)
        run_dir = agent_path("/tmp/agent/python-runs")
        run_dir.mkdir(parents=True, exist_ok=True)
        env = platform_env(
            {
                "PATH": os.path.expanduser("~/.local/bin") + os.pathsep + os.environ.get("PATH", ""),
                "PYTHONUNBUFFERED": "1",
            }
        )
        if IS_LINUX:
            env.update(
                {
                    "XDG_RUNTIME_DIR": os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000"),
                    "DBUS_SESSION_BUS_ADDRESS": os.environ.get("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus"),
                }
            )
        extra_env = data.get("env")
        if isinstance(extra_env, dict):
            env.update({str(k): str(v) for k, v in extra_env.items()})
        normalization = {
            "input_chars": len(code),
            "changes": [],
            "syntax_repair_error": None,
            "syntax_ok": None,
        }
        qwen_result = {"enabled": False, "mode": "off", "called": False, "ok": False}
        code, repair_changes, repair_error = repair_python_code_for_execution(code)
        normalization["changes"].extend(repair_changes)
        normalization["syntax_repair_error"] = repair_error
        user_code_path = run_dir / f"user-{now_ms()}.py"
        user_code_path.write_text(code + "\n", encoding="utf-8")
        ruff_result = run_ruff_rewrite(
            user_code_path,
            cwd,
            env,
            enabled=truthy_setting(data.get("ruff", data.get("use_ruff")), default=True),
        )
        if ruff_result.get("changed"):
            code = user_code_path.read_text(encoding="utf-8").strip()
            normalization["changes"].append("ruff check --fix / ruff format")
        try:
            compile(code, "<agent-code>", "exec")
            normalization["syntax_ok"] = True
        except SyntaxError as exc:
            normalization["syntax_ok"] = False
            normalization["syntax_repair_error"] = normalization["syntax_repair_error"] or {"final": syntax_error_summary(exc)}
        qwen_mode = qwen_repair_mode(data)
        qwen_result = {
            "enabled": qwen_mode != "off",
            "mode": qwen_mode,
            "url": QWEN_REPAIR_URL,
            "called": False,
            "ok": False,
        }
        if qwen_mode == "always" or (qwen_mode == "auto" and not normalization["syntax_ok"]):
            qwen_result = call_qwen_code_repair(
                code,
                mode=qwen_mode,
                timeout=data.get("qwen_timeout_ms", data.get("qwen_timeout", 45000)) / 1000
                if isinstance(data.get("qwen_timeout_ms", data.get("qwen_timeout", None)), (int, float))
                else data.get("qwen_timeout", 45),
                max_new_tokens=data.get("qwen_max_new_tokens", 320),
            )
            if qwen_result.get("ok") and qwen_result.get("code"):
                code = qwen_result["code"]
                normalization["changes"].append("qwen code repair")
                user_code_path.write_text(code + "\n", encoding="utf-8")
                ruff_after_qwen = run_ruff_rewrite(
                    user_code_path,
                    cwd,
                    env,
                    enabled=truthy_setting(data.get("ruff", data.get("use_ruff")), default=True),
                )
                qwen_result["ruff_after_qwen"] = ruff_after_qwen
                if ruff_after_qwen.get("changed"):
                    code = user_code_path.read_text(encoding="utf-8").strip()
                    normalization["changes"].append("ruff check --fix / ruff format after qwen")
                try:
                    compile(code, "<agent-code>", "exec")
                    normalization["syntax_ok"] = True
                    normalization["syntax_repair_error"] = None
                except SyntaxError as exc:
                    normalization["syntax_ok"] = False
                    normalization["syntax_repair_error"] = {"after_qwen": syntax_error_summary(exc)}
        normalization["output_chars"] = len(code)
        code_hash = hashlib.sha256(code.encode("utf-8", errors="replace")).hexdigest()[:16]
        script_path = run_dir / f"run-{now_ms()}-{code_hash}.py"
        prelude = python_prelude(mode)
        postamble = codeact_intent_postamble() if mode in ("pyautogui", "codeact", "cdp") else ""
        script_path.write_text(prelude + "\n" + code + "\n" + postamble + "\n", encoding="utf-8")
        requirements = data.get("requirements") or []
        if isinstance(requirements, str):
            requirements = [part for part in re.split(r"[\s,]+", requirements.strip()) if part]
        if not isinstance(requirements, list):
            self.fail("requirements must be a list of package specifiers or a whitespace/comma-separated string", 400)
            return
        pip_result = None
        if requirements:
            pip_start = time.time()
            pip = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "pip",
                    "install",
                    "--user",
                    "--disable-pip-version-check",
                    *[str(item) for item in requirements],
                ],
                cwd=cwd,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=setup_timeout_ms / 1000.0,
            )
            pip_result = {
                "ok": pip.returncode == 0,
                "rc": pip.returncode,
                "stdout": pip.stdout,
                "stderr": pip.stderr,
                "elapsed_ms": int((time.time() - pip_start) * 1000),
                "requirements": requirements,
            }
            if pip.returncode != 0:
                self.send_json(
                    {
                        "ok": False,
                        "error": "requirements install failed",
                        "pip": pip_result,
                        "cwd": cwd,
                        "script_path": str(script_path),
                        "user_code_path": str(user_code_path),
                        "code_hash": code_hash,
                        "mode": mode,
                        "python": sys.executable,
                        "normalization": normalization,
                        "ruff": ruff_result,
                        "qwen_repair": qwen_result,
                    },
                    200,
                )
                return
        append_log(
            {
                "event": event_name,
                "mode": mode,
                "code_hash": code_hash,
                "code_chars": len(code),
                "cwd": cwd,
                "timeout_ms": timeout_ms,
                "requirements": requirements,
                "script_path": str(script_path),
                "user_code_path": str(user_code_path),
                "normalization": normalization,
                "ruff": ruff_result,
                "qwen_repair": qwen_result,
            }
        )
        start = time.time()
        try:
            result = subprocess.run(
                [sys.executable, str(script_path)],
                cwd=cwd,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout_ms / 1000.0,
            )
            elapsed_ms = int((time.time() - start) * 1000)
            ok = result.returncode == 0
            intent_applied = None
            marker = "<<<CODEACT_INTENT_APPLIED>>>"
            for line in (result.stdout or "").splitlines():
                if line.startswith(marker):
                    try:
                        intent_applied = json.loads(line[len(marker) :])
                    except Exception as exc:
                        intent_applied = {"ok": False, "reason": "intent_parse_error", "error": str(exc), "raw": line}
            self.send_json(
                {
                    "ok": ok,
                    "rc": result.returncode,
                    "stdout": result.stdout,
                    "stderr": result.stderr,
                    "intent_applied": intent_applied,
                    "elapsed_ms": elapsed_ms,
                    "timeout_ms": timeout_ms,
                    "cwd": cwd,
                    "script_path": str(script_path),
                    "user_code_path": str(user_code_path),
                    "code_hash": code_hash,
                    "mode": mode,
                    "blocked_pyautogui": [],
                    "python": sys.executable,
                    "pip": pip_result,
                    "normalization": normalization,
                    "ruff": ruff_result,
                    "qwen_repair": qwen_result,
                },
                200,
            )
        except subprocess.TimeoutExpired as exc:
            elapsed_ms = int((time.time() - start) * 1000)
            self.send_json(
                {
                    "ok": False,
                    "error": "python code timed out",
                    "stdout": exc.stdout or "",
                    "stderr": exc.stderr or "",
                    "elapsed_ms": elapsed_ms,
                    "timeout_ms": timeout_ms,
                    "cwd": cwd,
                    "script_path": str(script_path),
                    "user_code_path": str(user_code_path),
                    "code_hash": code_hash,
                    "mode": mode,
                    "blocked_pyautogui": [],
                    "python": sys.executable,
                    "pip": pip_result,
                    "normalization": normalization,
                    "ruff": ruff_result,
                    "qwen_repair": qwen_result,
                },
                200,
            )

    def item_button(self, item, default="left"):
        return str(item.get("button", default)).lower()

    def item_hold_seconds(self, item, default=0.5):
        if "duration_ms" in item:
            return max(0, float(item.get("duration_ms", 0)) / 1000.0)
        if "hold_ms" in item:
            return max(0, float(item.get("hold_ms", 0)) / 1000.0)
        if "ms" in item:
            return max(0, float(item.get("ms", 0)) / 1000.0)
        return max(0, float(item.get("duration", item.get("seconds", default))))

    def item_point(self, item, x_key="x", y_key="y"):
        return clamp_framebuffer_point(item[x_key], item[y_key])

    def clamp_point_to_display(self, x, y):
        return framebuffer_to_display_point(x, y)

    def clamp_point_to_framebuffer(self, x, y):
        return clamp_framebuffer_point(x, y)

    def active_window_center(self):
        window = self.active_window_from_xprop(running_windows_snapshot())
        if window and window.get("w") and window.get("h"):
            x = int(window.get("x", 0)) + int(window.get("w", 0)) // 2
            y = int(window.get("y", 0)) + int(window.get("h", 0)) // 2
            return display_to_framebuffer_point(x, y), window
        fb_w, fb_h = framebuffer_size()
        return (fb_w // 2, fb_h // 2), window

    def scroll_details(self, pyautogui, item):
        direction = str(item.get("direction", "")).strip().lower()
        raw_amount = int(item.get("amount", item.get("clicks", 3)))
        ticks = max(1, abs(raw_amount))
        if direction:
            if direction not in ("up", "down"):
                raise ValueError("scroll direction must be 'up' or 'down'")
            pyautogui_amount = -ticks if direction == "down" else ticks
            logical_direction = direction
        else:
            # Computer-agent callers use positive amount for down and negative for up.
            pyautogui_amount = -raw_amount
            logical_direction = "down" if raw_amount >= 0 else "up"

        if item.get("x") is not None and item.get("y") is not None:
            x, y = self.clamp_point_to_framebuffer(item.get("x"), item.get("y"))
            target_window = None
        else:
            (x, y), target_window = self.active_window_center()

        display_x, display_y = self.clamp_point_to_display(x, y)
        pointer_bridge_to(pyautogui, x, y, duration=float(item.get("move_duration", POINTER_BRIDGE_MOVE_DURATION)))
        pyautogui.moveTo(display_x, display_y, duration=float(item.get("move_duration", 0)))
        time.sleep(float(item.get("before_scroll_seconds", 0.05)))
        pyautogui.scroll(pyautogui_amount, x=display_x, y=display_y)
        remember_pointer_event("scroll", x, y)
        time.sleep(float(item.get("after_scroll_seconds", 0.15)))
        return {
            "direction": logical_direction,
            "ticks": ticks,
            "pyautogui_amount": pyautogui_amount,
            "x": x,
            "y": y,
            "display_x": display_x,
            "display_y": display_y,
            "target": "explicit_point" if item.get("x") is not None and item.get("y") is not None else "active_window_center",
            "window": target_window,
        }

    def handle_scroll(self):
        data = self.read_json()
        pyautogui = import_pyautogui()
        details = self.scroll_details(pyautogui, data)
        append_log({"event": "scroll", **details})
        self.send_json({"ok": True, **details})

    def move_window_action(self, item):
        if IS_WINDOWS:
            window = self.find_window_for_activation(item)
            if not window:
                raise ValueError("window not found")
            target = pygetwindow_object_for_window(window)
            if not target:
                raise ValueError("pygetwindow could not locate window")
            x = int(item.get("x", item.get("to_x", window.get("x", -1))))
            y = int(item.get("y", item.get("to_y", window.get("y", -1))))
            w = int(item.get("w", item.get("width", window.get("w", -1))))
            h = int(item.get("h", item.get("height", window.get("h", -1))))
            if w > 0 and h > 0:
                target.resizeTo(w, h)
            target.moveTo(x, y)
            return {"xid": window.get("xid") or window.get("id"), "geometry": f"{x},{y},{w},{h}", "window": window}
        if not shutil.which("wmctrl"):
            raise ValueError("wmctrl not installed")
        window = self.find_window_for_activation(item)
        if not window:
            raise ValueError("window not found")
        xid = window.get("xid") or window.get("id")
        x = int(item.get("x", item.get("to_x", window.get("x", -1))))
        y = int(item.get("y", item.get("to_y", window.get("y", -1))))
        w = int(item.get("w", item.get("width", window.get("w", -1))))
        h = int(item.get("h", item.get("height", window.get("h", -1))))
        geometry = f"0,{x},{y},{w},{h}"
        result = run_command(["wmctrl", "-i", "-r", xid, "-e", geometry], shell=False, timeout_ms=5000)
        if not result["ok"]:
            raise ValueError(f"wmctrl move failed: {result.get('stderr') or result.get('stdout')}")
        return {"xid": xid, "geometry": geometry, "window": window}

    def target_window_xid(self, item):
        window = self.find_window_for_activation(item)
        if not window:
            raise ValueError("window not found")
        xid = window.get("xid") or window.get("id")
        if not xid:
            raise ValueError("window has no xid")
        return xid, window

    def window_state_action(self, item, state):
        if IS_WINDOWS:
            xid, window = self.target_window_xid(item)
            target = pygetwindow_object_for_window(window)
            if not target:
                raise ValueError("pygetwindow could not locate window")
            state_text = str(state or "")
            try:
                if "maximized" in state_text:
                    if state_text.startswith("remove"):
                        target.restore()
                    else:
                        target.maximize()
                elif "hidden" in state_text or "minimized" in state_text:
                    if state_text.startswith("remove"):
                        target.restore()
                    else:
                        target.minimize()
                else:
                    raise ValueError(f"unsupported Windows window state: {state_text}")
            except Exception as exc:
                raise ValueError(f"window state failed: {exc}") from exc
            return {"xid": xid, "state": state, "window": window}
        if not shutil.which("wmctrl"):
            raise ValueError("wmctrl not installed")
        xid, window = self.target_window_xid(item)
        result = run_command(["wmctrl", "-i", "-r", xid, "-b", state], shell=False, timeout_ms=5000)
        if not result["ok"]:
            raise ValueError(f"wmctrl state failed: {result.get('stderr') or result.get('stdout')}")
        return {"xid": xid, "state": state, "window": window}

    def close_window_action(self, item):
        xid, window = self.target_window_xid(item)
        if IS_WINDOWS:
            target = pygetwindow_object_for_window(window)
            if not target:
                raise ValueError("pygetwindow could not locate window")
            target.close()
            return {"xid": xid, "window": window}
        if shutil.which("wmctrl"):
            result = run_command(["wmctrl", "-i", "-c", xid], shell=False, timeout_ms=5000)
        elif shutil.which("xdotool"):
            result = run_command(["xdotool", "windowclose", xid], shell=False, timeout_ms=5000)
        else:
            raise ValueError("neither wmctrl nor xdotool is installed")
        if not result["ok"]:
            raise ValueError(f"window close failed: {result.get('stderr') or result.get('stdout')}")
        return {"xid": xid, "window": window}

    def human_type_grid_action(self, pyautogui, item):
        rows = []
        if "columns" in item:
            rows.append(item.get("columns") or [])
        rows.extend(item.get("rows") or item.get("values") or [])
        if not rows:
            raise ValueError("type_grid requires rows, values, or columns")
        rows = [[str(cell) for cell in row] if isinstance(row, (list, tuple)) else [str(row)] for row in rows]
        x = item.get("x")
        y = item.get("y")
        if x is not None and y is not None:
            fx, fy = self.clamp_point_to_framebuffer(x, y)
            dx, dy = self.clamp_point_to_display(fx, fy)
            pointer_bridge_to(pyautogui, fx, fy)
            pyautogui_human_click(pyautogui, dx, dy, button=self.item_button(item))
            remember_pointer_event("click", fx, fy)
            time.sleep(float(item.get("after_click_seconds", 0.25)))
        if bool_param(item.get("home_first"), default=False):
            pyautogui.hotkey("ctrl", "home")
            time.sleep(float(item.get("after_home_seconds", 0.1)))
        char_interval = float(item.get("interval", item.get("char_interval", 0.02)))
        key_pause = float(item.get("key_pause", 0.05))
        total_cells = 0
        for row_index, row in enumerate(rows):
            for col_index, value in enumerate(row):
                if value:
                    pyautogui.write(value, interval=char_interval)
                total_cells += 1
                if col_index < len(row) - 1:
                    pyautogui.press("tab")
                    time.sleep(key_pause)
            if row_index < len(rows) - 1:
                pyautogui.press("enter")
                time.sleep(key_pause)
                if bool_param(item.get("home_after_row"), default=True):
                    pyautogui.press("home")
                    time.sleep(key_pause)
        if bool_param(item.get("commit_final"), default=True):
            pyautogui.press("enter")
        return {
            "rows": len(rows),
            "cols": max((len(row) for row in rows), default=0),
            "cells": total_cells,
            "method": "human_keyboard_tab_enter",
        }

    def handle_structured_action(self):
        data = self.read_json()
        actions = data.get("actions")
        if actions is None:
            actions = [data]
        if not isinstance(actions, list):
            self.fail("actions must be a list", 400)
            return
        if VNC_BACKEND_ENABLED:
            results = []
            start = time.time()
            try:
                for item in actions:
                    if not isinstance(item, dict):
                        raise ValueError("each action must be an object")
                    result = vnc_action_one(item)
                    results.append(result)
                append_log({"event": "structured_action", "backend": BACKEND, "actions": actions, "results": results})
                self.send_json({"ok": True, "results": results, "elapsed_ms": int((time.time() - start) * 1000)})
            except Exception as exc:
                append_log({"event": "structured_action_failed", "backend": BACKEND, "actions": actions, "error": str(exc)})
                self.fail(str(exc), 400, backend=BACKEND)
            return
        pyautogui = import_pyautogui()
        results = []
        start = time.time()
        for item in actions:
            typ = item.get("type", item.get("action"))
            if typ in ("move", "move_to"):
                fx, fy = self.item_point(item)
                dx, dy = self.clamp_point_to_display(fx, fy)
                pointer_bridge_to(pyautogui, fx, fy, duration=float(item.get("duration", POINTER_BRIDGE_MOVE_DURATION)))
                pyautogui.moveTo(dx, dy, duration=float(item.get("duration", 0)))
                remember_pointer_event("move", fx, fy)
            elif typ in ("mouse_down", "down", "press_mouse"):
                x = item.get("x")
                y = item.get("y")
                kwargs = {"button": self.item_button(item)}
                if x is not None and y is not None:
                    fx, fy = self.clamp_point_to_framebuffer(x, y)
                    dx, dy = self.clamp_point_to_display(fx, fy)
                    pointer_bridge_to(pyautogui, fx, fy)
                    kwargs.update({"x": dx, "y": dy})
                    x, y = fx, fy
                pyautogui.mouseDown(**kwargs)
                remember_pointer_event("mouse_down", x, y)
            elif typ in ("mouse_up", "up", "release_mouse"):
                x = item.get("x")
                y = item.get("y")
                kwargs = {"button": self.item_button(item)}
                if x is not None and y is not None:
                    fx, fy = self.clamp_point_to_framebuffer(x, y)
                    dx, dy = self.clamp_point_to_display(fx, fy)
                    pointer_bridge_to(pyautogui, fx, fy)
                    kwargs.update({"x": dx, "y": dy})
                    x, y = fx, fy
                pyautogui.mouseUp(**kwargs)
                remember_pointer_event("mouse_up", x, y)
            elif typ == "click":
                fx, fy = self.item_point(item)
                click_result = pyautogui_fat_click(
                    pyautogui,
                    fx,
                    fy,
                    clicks=int(item.get("clicks", 1)),
                    button=self.item_button(item),
                    enabled=bool_param(item.get("fat_click"), default=FAT_CLICK_ENABLED),
                    radius=int(item.get("fat_click_radius", FAT_CLICK_RADIUS)),
                    delay=float(item.get("fat_click_delay", FAT_CLICK_DELAY)),
                )
                remember_pointer_event("click", fx, fy)
                item["_fat_click_points"] = click_result["points"]
                item["_center_snap"] = click_result.get("center_snap")
                item["_hover_probe"] = click_result.get("hover_probe")
                item["_hover_snap"] = click_result.get("hover_snap")
            elif typ in ("double_click", "dblclick"):
                fx, fy = self.item_point(item)
                dx, dy = self.clamp_point_to_display(fx, fy)
                pointer_bridge_to(pyautogui, fx, fy)
                pyautogui_human_click(pyautogui, dx, dy, clicks=2, button=self.item_button(item))
                remember_pointer_event("double_click", fx, fy)
            elif typ in ("right_click", "context_click"):
                fx, fy = self.item_point(item)
                dx, dy = self.clamp_point_to_display(fx, fy)
                pointer_bridge_to(pyautogui, fx, fy)
                pyautogui_human_click(pyautogui, dx, dy, button="right")
                remember_pointer_event("right_click", fx, fy)
            elif typ == "middle_click":
                fx, fy = self.item_point(item)
                dx, dy = self.clamp_point_to_display(fx, fy)
                pointer_bridge_to(pyautogui, fx, fy)
                pyautogui_human_click(pyautogui, dx, dy, button="middle")
                remember_pointer_event("middle_click", fx, fy)
            elif typ in ("long_click", "click_hold", "hold_click"):
                x, y = self.item_point(item)
                button = self.item_button(item)
                if button == "left":
                    center_snap = atspi_center_snap(x, y)
                    item["_center_snap"] = center_snap
                    if center_snap and center_snap.get("to"):
                        x, y = center_snap["to"]
                    snapped_x, snapped_y, probe, snap = hover_probe_and_snap(pyautogui, x, y)
                    item["_hover_probe"] = probe
                    item["_hover_snap"] = snap
                    x, y = snapped_x, snapped_y
                dx, dy = self.clamp_point_to_display(x, y)
                pointer_bridge_to(pyautogui, x, y, duration=float(item.get("move_duration", POINTER_BRIDGE_MOVE_DURATION)))
                pyautogui.moveTo(dx, dy, duration=float(item.get("move_duration", 0)))
                pyautogui.mouseDown(button=button)
                time.sleep(self.item_hold_seconds(item, default=0.6))
                pyautogui.mouseUp(button=button)
                remember_pointer_event("long_click", x, y)
            elif typ in ("drag", "drag_to"):
                if "start_x" in item and "start_y" in item:
                    start_fx, start_fy = self.item_point(item, "start_x", "start_y")
                    start_dx, start_dy = self.clamp_point_to_display(start_fx, start_fy)
                    pointer_bridge_to(pyautogui, start_fx, start_fy, duration=float(item.get("move_duration", POINTER_BRIDGE_MOVE_DURATION)))
                    pyautogui.moveTo(start_dx, start_dy, duration=float(item.get("move_duration", 0)))
                end_fx = item.get("x", item.get("to_x"))
                end_fy = item.get("y", item.get("to_y"))
                end_fx, end_fy = self.clamp_point_to_framebuffer(end_fx, end_fy)
                end_dx, end_dy = self.clamp_point_to_display(end_fx, end_fy)
                pyautogui.dragTo(
                    end_dx,
                    end_dy,
                    duration=float(item.get("duration", 0.2)),
                    button=self.item_button(item),
                )
                remember_pointer_event("drag", end_fx, end_fy)
            elif typ in ("drag_between", "drag_from_to", "drag_drop", "drag_window"):
                start_x, start_y = self.clamp_point_to_framebuffer(
                    item.get("start_x", item.get("from_x")),
                    item.get("start_y", item.get("from_y")),
                )
                end_x, end_y = self.clamp_point_to_framebuffer(
                    item.get("end_x", item.get("to_x", item.get("x"))),
                    item.get("end_y", item.get("to_y", item.get("y"))),
                )
                start_dx, start_dy = self.clamp_point_to_display(start_x, start_y)
                end_dx, end_dy = self.clamp_point_to_display(end_x, end_y)
                pointer_bridge_to(pyautogui, start_x, start_y, duration=float(item.get("move_duration", POINTER_BRIDGE_MOVE_DURATION)))
                pyautogui.moveTo(start_dx, start_dy, duration=float(item.get("move_duration", 0)))
                pyautogui.dragTo(end_dx, end_dy, duration=float(item.get("duration", 0.3)), button=self.item_button(item))
                remember_pointer_event("drag", end_x, end_y)
            elif typ in ("move_window", "window_move"):
                details = self.move_window_action(item)
            elif typ in ("minimize_window", "window_minimize"):
                details = self.window_state_action(item, "add,hidden")
            elif typ in ("maximize_window", "window_maximize"):
                details = self.window_state_action(item, "add,maximized_vert,maximized_horz")
            elif typ in ("unmaximize_window", "restore_window", "window_restore"):
                details = self.window_state_action(item, "remove,maximized_vert,maximized_horz,hidden")
            elif typ in ("close_window", "window_close", "exit_window"):
                details = self.close_window_action(item)
            elif typ in ("activate_window", "window_activate", "focus_window", "activate_app", "app_activate", "focus_app"):
                details = self.activate_window_action(item)
            elif typ in ("type_grid", "human_type_grid", "human_type_table", "keyboard_table", "spreadsheet_type_grid"):
                details = self.human_type_grid_action(pyautogui, item)
            elif typ in ("paste_text", "clipboard_paste", "paste", "paste_tsv", "type_block", "write_block"):
                if "rows" in item:
                    rows = []
                    if "columns" in item:
                        rows.append(item.get("columns") or [])
                    rows.extend(item.get("rows") or [])
                    text = rows_to_tsv(rows)
                else:
                    text = str(item.get("text", item.get("content", item.get("tsv", ""))))
                x = item.get("x")
                y = item.get("y")
                if x is not None and y is not None:
                    fx, fy = self.clamp_point_to_framebuffer(x, y)
                    dx, dy = self.clamp_point_to_display(fx, fy)
                    pointer_bridge_to(pyautogui, fx, fy)
                    pyautogui_human_click(pyautogui, dx, dy, button=self.item_button(item))
                    remember_pointer_event("click", fx, fy)
                    time.sleep(float(item.get("after_click_seconds", 0.05)))
                clipboard = set_clipboard_text(text)
                if not clipboard.get("ok"):
                    raise ValueError(f"clipboard set failed: {clipboard.get('error') or clipboard}")
                pyautogui.hotkey("ctrl", "v")
                if bool_param(item.get("enter_after"), default=False):
                    pyautogui.press("enter")
                details = {
                    "chars": len(text),
                    "lines": text.count("\n") + 1 if text else 0,
                    "clipboard": clipboard,
                }
            elif typ in ("type", "write"):
                pyautogui.write(str(item.get("text", "")), interval=float(item.get("interval", 0)))
            elif typ in ("press", "key"):
                keys = pyautogui_key_combo(item.get("key", item.get("combo", item.get("keys", ""))))
                if not keys:
                    raise ValueError("press requires key/combo/keys")
                if len(keys) == 1:
                    pyautogui.press(keys[0])
                else:
                    pyautogui.hotkey(*keys)
                details = {"keys": keys, "combo": "+".join(keys)}
            elif typ == "hotkey":
                keys = pyautogui_key_combo(item.get("keys", item.get("combo", item.get("key", ""))))
                if not keys:
                    raise ValueError("hotkey requires keys/combo/key")
                if len(keys) == 1:
                    pyautogui.press(keys[0])
                else:
                    pyautogui.hotkey(*keys)
                details = {"keys": keys, "combo": "+".join(keys)}
            elif typ == "scroll":
                details = self.scroll_details(pyautogui, item)
            elif typ in ("wait", "wait_ms"):
                if typ == "wait_ms":
                    time.sleep(max(0, float(item.get("ms", item.get("duration_ms", 0))) / 1000.0))
                else:
                    time.sleep(float(item.get("seconds", 1)))
            elif typ == "hold":
                time.sleep(float(item.get("seconds", 1)))
            else:
                raise ValueError(f"unknown action type: {typ}")
            result_item = {"ok": True, "type": typ}
            if typ in (
                "move_window",
                "window_move",
                "minimize_window",
                "window_minimize",
                "maximize_window",
                "window_maximize",
                "unmaximize_window",
                "restore_window",
                "window_restore",
                "close_window",
                "window_close",
                "exit_window",
                "activate_window",
                "window_activate",
                "focus_window",
                "activate_app",
                "app_activate",
                "focus_app",
                "paste_text",
                "clipboard_paste",
                "paste",
                "paste_tsv",
                "type_block",
                "write_block",
                "type_grid",
                "human_type_grid",
                "human_type_table",
                "keyboard_table",
                "spreadsheet_type_grid",
                "scroll",
                "press",
                "key",
                "hotkey",
            ):
                result_item["details"] = details
            if item.get("_fat_click_points") is not None:
                result_item["fat_click"] = len(item["_fat_click_points"]) > 1
                result_item["click_points"] = item["_fat_click_points"]
            if item.get("_center_snap") is not None:
                result_item["center_snap"] = item["_center_snap"]
                if item["_center_snap"].get("to"):
                    result_item["actual_x"] = item["_center_snap"]["to"][0]
                    result_item["actual_y"] = item["_center_snap"]["to"][1]
            if item.get("_hover_probe") is not None:
                result_item["hover_probe"] = item["_hover_probe"]
            if item.get("_hover_snap") is not None:
                result_item["hover_snap"] = item["_hover_snap"]
                result_item["actual_x"] = item["_hover_snap"]["to"][0]
                result_item["actual_y"] = item["_hover_snap"]["to"][1]
            results.append(result_item)
        append_log({"event": "structured_action", "actions": actions})
        self.send_json({"ok": True, "results": results, "elapsed_ms": int((time.time() - start) * 1000)})

    def handle_xdotool(self):
        data = self.read_json()
        command = data.get("command", data.get("args", ""))
        if isinstance(command, list):
            cmd = ["xdotool"] + command
            shell = False
        else:
            cmd = "xdotool " + str(command)
            shell = True
        if IS_WINDOWS or VNC_BACKEND_ENABLED:
            self.fail("xdotool is Linux/X11-only; use /hands/action", 501, platform=VNC_TARGET_PLATFORM if VNC_BACKEND_ENABLED else "windows")
            return
        if not shutil.which("xdotool"):
            self.fail("xdotool not installed", 501)
            return
        append_log({"event": "xdotool", "command": command})
        result = run_command(cmd, timeout_ms=data.get("timeout_ms", 30000), shell=shell)
        self.send_json(result, 200 if result["ok"] else 500)

    def handle_files_list(self, query):
        limit = self.event_limit({"limit": query.get("limit", ["500"])}, default=500)
        self.send_json(directory_listing_payload(query.get("path", ["/workspace"])[0], limit=limit, create=True))

    def handle_file_stat(self, query):
        raw_path = query.get("path", [""])[0]
        if not raw_path:
            self.fail("missing path", 400)
            return
        self.send_json(file_stat_payload(raw_path))

    def handle_find_recent(self, query):
        raw_dir = query.get("dir", query.get("path", ["/workspace"]))[0]
        since = query.get("since", query.get("baseline_ts", ["0"]))[0]
        try:
            limit = int(query.get("limit", ["100"])[0])
        except Exception:
            limit = 100
        self.send_json(find_recent_payload(raw_dir, since=since, limit=limit))

    def handle_clipboard(self, query):
        try:
            max_chars = int(query.get("max_chars", ["10000"])[0])
        except Exception:
            max_chars = 10000
        self.send_json(read_clipboard_text(max_chars=max_chars))

    def handle_clipboard_write(self):
        data = self.read_json()
        if "text" not in data and "content" not in data and "value" not in data:
            self.fail("missing text", 400)
            return
        text = str(data.get("text", data.get("content", data.get("value", ""))))
        result = set_clipboard_text(text)
        result.update({"text_length": len(text)})
        self.send_json(result, 200 if result.get("ok") else 500)

    def handle_evidence(self, query):
        try:
            dir_limit = int(query.get("dir_limit", query.get("limit", ["80"]))[0])
        except Exception:
            dir_limit = 80
        try:
            files_limit = int(query.get("files_limit", ["20"])[0])
        except Exception:
            files_limit = 20
        try:
            max_clipboard_chars = int(query.get("clipboard_chars", ["4000"])[0])
        except Exception:
            max_clipboard_chars = 4000

        running_windows = running_windows_snapshot()
        active_window = self.active_window_from_xprop(running_windows)
        fb = framebuffer_geometry()
        display_w, display_h = display_geometry()
        mouse = current_mouse_location()

        dirs = query_values(query, "dir", "dirs")
        if not dirs:
            defaults = [
                str(DEFAULT_WORKSPACE_DIR),
                str(DEFAULT_DOWNLOADS_DIR),
                str(DEFAULT_UPLOADS_DIR),
                str(pathlib.Path.home() / "Desktop"),
                str(pathlib.Path.home() / "Downloads"),
            ]
            dirs = list(dict.fromkeys(defaults))
        files = query_values(query, "file", "files", "path")

        screenshot = {
            "included": False,
            "hint": "attach the last screenshots separately, or call /eyes/evidence?screenshot=true for a current screenshot hash",
        }
        if bool_param(query.get("screenshot", query.get("include_screenshot", ["false"]))[0], default=False):
            try:
                png = capture_png_bytes()
                screenshot = {
                    "included": True,
                    "bytes": len(png),
                    "sha256": hashlib.sha256(png).hexdigest(),
                    "content_type": "image/png",
                }
                if bool_param(query.get("screenshot_base64", ["false"])[0], default=False):
                    screenshot["base64"] = base64.b64encode(png).decode("ascii")
            except Exception as exc:
                screenshot = {"included": False, "error": str(exc)}

        payload = {
            "ok": True,
            "screen": {
                "width": int(fb.get("width", display_w)),
                "height": int(fb.get("height", display_h)),
                "source": fb.get("source", "framebuffer"),
                "framebuffer_ok": bool(fb.get("ok")),
                "display_geometry": {"width": display_w, "height": display_h},
                "coordinate_space": {
                    "x_min": 0,
                    "y_min": 0,
                    "x_max": max(0, int(fb.get("width", display_w)) - 1),
                    "y_max": max(0, int(fb.get("height", display_h)) - 1),
                },
                "mouse": {"x": mouse[0], "y": mouse[1]} if mouse else None,
            },
            "screenshot": screenshot,
            "active_window": active_window,
            "running_windows": running_windows,
            "running_processes_no_window": running_processes_snapshot(
                exclude_pids=[item.get("pid") for item in running_windows], limit=50
            ),
            "directories": [directory_listing_payload(path, limit=dir_limit, create=False) for path in dirs],
            "files": [file_stat_payload(path) for path in files],
            "recent_files": recent_files_snapshot(limit=files_limit),
            "clipboard": read_clipboard_text(max_chars=max_clipboard_chars)
            if bool_param(query.get("clipboard", ["true"])[0], default=True)
            else {"ok": False, "skipped": True},
            "channels": {
                "visual": "GET /eyes/screenshot, plus optional screenshot hash in this response",
                "windows": "active_window + running_windows + running_processes_no_window",
                "files": "directories + files + recent_files",
                "clipboard": "clipboard text when native OS access is available",
            },
            "instruction": (
                "Use this as verifier evidence beside the last screenshots. "
                "For open/close/focus claims, trust running_windows. "
                "For save/export claims, trust file stats and directory listings. "
                "For copy/paste claims, trust clipboard when ok=true. "
                "For transient UI changes, trust screenshots/visual delta."
            ),
        }
        self.send_json(payload)

    def handle_process_running(self, query):
        name = query.get("name", query.get("q", [""]))[0]
        if not name:
            self.fail("missing name", 400)
            return
        try:
            limit = int(query.get("limit", ["20"])[0])
        except Exception:
            limit = 20
        self.send_json(process_running_payload(name, limit=limit))

    def handle_window_open(self, query):
        name = query.get("name", query.get("q", [""]))[0]
        if not name:
            self.fail("missing name", 400)
            return
        try:
            limit = int(query.get("limit", ["20"])[0])
        except Exception:
            limit = 20
        self.send_json(window_open_payload(name, limit=limit))

    def handle_file_read(self, query):
        raw_path = query.get("path", [""])[0]
        if not raw_path:
            self.fail("missing path", 400)
            return
        path = agent_path(raw_path)
        if not path.exists() or not path.is_file():
            self.fail("path not found", 404, path=str(path))
            return
        body = path.read_bytes()
        as_json = query.get("format", [""])[0] in ("json", "text")
        if as_json:
            self.send_json({"ok": True, "path": str(path), "content": body.decode("utf-8", errors="replace")})
            return
        content_type = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        self.send_bytes(body, content_type, filename=path.name)

    def handle_file_write(self):
        data = self.read_json()
        raw_path = data.get("path", "")
        if not raw_path:
            self.fail("missing path", 400)
            return
        path = agent_path(raw_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        if "base64" in data:
            content = base64.b64decode(data["base64"])
            path.write_bytes(content)
        else:
            path.write_text(str(data.get("content", "")), encoding=data.get("encoding", "utf-8"))
        append_log({"event": "file_write", "path": str(path), "size": path.stat().st_size})
        self.send_json({"ok": True, "path": str(path), "size": path.stat().st_size})

    def handle_skills_list(self, query):
        include_template = query.get("include_template", ["true"])[0].lower() != "false"
        skills = list_skills()
        if not include_template:
            for skill in skills:
                skill.pop("template", None)
        self.send_json({"ok": True, "db": str(SKILLS_DB), "skills": skills})

    def handle_skills_search(self, query):
        q = query.get("q", query.get("task", [""]))[0]
        limit = self.event_limit(query, default=5)
        matches = search_skills(q, limit=limit)
        self.send_json({"ok": True, "db": str(SKILLS_DB), "query": q, "matches": matches})

    def resolve_skill_for_run(self, data):
        skill_id = data.get("skill_id", data.get("id", ""))
        if skill_id:
            return get_skill(skill_id), skill_id, None
        task = data.get("task", data.get("query", ""))
        if task:
            matches = search_skills(task, limit=1)
            if matches:
                return matches[0], matches[0]["id"], matches
        return None, skill_id, None

    def skill_write_csv(self, skill, data):
        template = skill.get("template", {})
        path = agent_path(data.get("path", template.get("path", "/workspace/agent-demo/result.csv")))
        columns = data.get("columns", template.get("columns", []))
        rows = data.get("rows", template.get("rows", []))
        include_header = data.get("include_header", True)
        if not columns and rows and isinstance(rows[0], dict):
            columns = list(rows[0].keys())
        if not isinstance(columns, list) or not isinstance(rows, list):
            raise ValueError("columns and rows must be lists")
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding=data.get("encoding", "utf-8"), newline="") as f:
            writer = csv.writer(f)
            if include_header and columns:
                writer.writerow(columns)
            for row in rows:
                if isinstance(row, dict):
                    writer.writerow([row.get(column, "") for column in columns])
                else:
                    writer.writerow(list(row))
        return {
            "ok": True,
            "path": str(path),
            "size": path.stat().st_size,
            "columns": columns,
            "row_count": len(rows),
            "summary": f"wrote CSV spreadsheet {path} with {len(rows)} rows and {len(columns)} columns",
        }

    def skill_write_text(self, skill, data):
        template = skill.get("template", {})
        path = agent_path(data.get("path", template.get("path", "/workspace/agent-demo/result.md")))
        content = data.get("content", template.get("content", ""))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(str(content), encoding=data.get("encoding", "utf-8"))
        return {
            "ok": True,
            "path": str(path),
            "size": path.stat().st_size,
            "summary": f"wrote text artifact {path}",
        }

    def handle_skill_run(self):
        data = self.read_json()
        skill, skill_id, matches = self.resolve_skill_for_run(data)
        if not skill:
            self.fail("skill not found", 404, skill_id=skill_id or None)
            return
        try:
            macro_type = skill.get("macro_type")
            if macro_type == "write_csv":
                result = self.skill_write_csv(skill, data)
            elif macro_type == "write_text":
                result = self.skill_write_text(skill, data)
            elif macro_type == "guidance":
                result = {
                    "ok": True,
                    "summary": "guidance skill selected; use the preferred tools in template",
                    "template": skill.get("template", {}),
                }
            else:
                raise ValueError(f"unsupported macro_type: {macro_type}")
            result["skill_id"] = skill["id"]
            result["macro_type"] = macro_type
            if matches is not None:
                result["matches"] = matches
            record_skill_result(skill["id"], True)
            append_log({"event": "skill_run", "skill_id": skill["id"], "macro_type": macro_type, "result": result})
            self.send_json(result)
        except Exception as exc:
            record_skill_result(skill["id"], False)
            append_log({"event": "skill_run_failed", "skill_id": skill["id"], "error": str(exc)})
            self.fail(str(exc), 500, skill_id=skill["id"], traceback=traceback.format_exc())

    def handle_apps_list(self, query):
        limit = self.event_limit(query, default=200)
        self.send_json({"ok": True, "apps": list_installed_apps(limit=limit), "sources": [str(path) for path in DESKTOP_APP_DIRS]})

    def handle_apps_catalog(self, query):
        if query.get("name") or query.get("q"):
            self.handle_app_installed(query)
            return
        limit = self.event_limit(query, default=2000)
        apps = list_installed_apps(limit=limit)
        catalog = [
            {
                "name": app.get("name", ""),
                "desktop_file": app.get("desktop_file", ""),
                "exec": app.get("exec", ""),
                "generic_name": app.get("generic_name", ""),
                "categories": app.get("categories", []),
                "comment": app.get("comment", ""),
                "icon": app.get("icon", ""),
            }
            for app in apps
        ]
        self.send_json(
            {
                "ok": True,
                "count": len(catalog),
                "contract": "These are exact installed application names. Launch with POST /apps/launch using one returned name or desktop_file. No shortcut names.",
                "official_names": [app["name"] for app in catalog],
                "apps": catalog,
                "sources": [str(path) for path in DESKTOP_APP_DIRS],
            }
        )

    def handle_apps_search(self, query):
        q = query.get("q", query.get("query", [""]))[0]
        limit = self.event_limit(query, default=20)
        self.send_json({"ok": True, "query": q, "matches": search_installed_apps(q, limit=limit)})

    def handle_app_installed(self, query):
        name = query.get("name", query.get("q", [""]))[0]
        if not name:
            self.fail("missing name", 400)
            return
        try:
            limit = int(query.get("limit", ["10"])[0])
        except Exception:
            limit = 10
        self.send_json(app_installed_payload(name, limit=limit))

    def resolve_app_for_launch(self, data):
        if data.get("exec"):
            return {
                "name": data.get("name", "custom"),
                "canonical_name": data.get("name", "custom"),
                "exec": clean_desktop_exec(data["exec"]),
                "custom": True,
                "resolved_from": "exec",
            }
        desktop_file = str(data.get("desktop_file") or data.get("desktop") or "").strip()
        if desktop_file:
            for app in list_installed_apps(limit=2000):
                if app.get("desktop_file") == desktop_file:
                    app["canonical_name"] = app.get("name", "")
                    app["resolved_from"] = "desktop_file"
                    return app
        raw_name = data.get("name") or data.get("app_name") or data.get("app") or ""
        name = str(raw_name or "").lower()
        if name:
            for app in list_installed_apps(limit=2000):
                if app.get("name", "").lower() == name:
                    app["canonical_name"] = app.get("name", "")
                    app["resolved_from"] = "name"
                    return app
            if IS_WINDOWS:
                return {
                    "name": str(raw_name),
                    "canonical_name": str(raw_name),
                    "exec": str(raw_name),
                    "custom": True,
                    "resolved_from": "windows_name",
                }
        return None

    def handle_app_launch(self):
        data = self.read_json()
        app = self.resolve_app_for_launch(data)
        if not app:
            query = app_launch_query(data)
            suggestions = app_suggestions(query)
            self.fail(
                "app not found: use one did_you_mean entry exactly, preferably its desktop_file",
                404,
                query=query,
                did_you_mean=suggestions,
                examples=[suggestion.get("use") for suggestion in suggestions[:5]],
            )
            return
        exec_cmd = clean_desktop_exec(app.get("exec", ""))
        if not exec_cmd:
            self.fail("app has no executable command", 400, app=app)
            return
        before_xids = {normalize_xid(w.get("xid")) for w in running_windows_snapshot() if w.get("xid")}
        # Controller policy: launched apps are always normalized to a full work window.
        maximize = True
        force_maximize = True
        wait_stages_sec = parse_wait_stages(data)
        fullscreen = True
        stdout_path = agent_path("/tmp/agent/app-launch.log")
        stdout_path.parent.mkdir(parents=True, exist_ok=True)
        unit = f"nubian-app-{int(time.time() * 1000)}"
        launch_result = None
        process = None
        if IS_WINDOWS:
            with stdout_path.open("ab") as out:
                process = subprocess.Popen(
                    exec_cmd,
                    cwd=agent_path_string("/workspace"),
                    env=platform_env(),
                    stdout=out,
                    stderr=out,
                    shell=True,
                )
            launch_result = {"ok": True, "platform": "windows", "method": "subprocess_shell"}
        elif shutil.which("systemd-run"):
            launch_result = run_command(
                [
                    "systemd-run",
                    "--user",
                    "--collect",
                    "--quiet",
                    f"--unit={unit}",
                    f"--setenv=DISPLAY={os.environ.get('DISPLAY', DISPLAY)}",
                    f"--setenv=XAUTHORITY={os.environ.get('XAUTHORITY', '/run/user/1000/gdm/Xauthority')}",
                    f"--setenv=XDG_RUNTIME_DIR={os.environ.get('XDG_RUNTIME_DIR', '/run/user/1000')}",
                    f"--setenv=DBUS_SESSION_BUS_ADDRESS={os.environ.get('DBUS_SESSION_BUS_ADDRESS', 'unix:path=/run/user/1000/bus')}",
                    f"--working-directory={agent_path_string('/workspace')}",
                    "/bin/sh",
                    "-lc",
                    f"exec {exec_cmd} >>{shlex.quote(str(stdout_path))} 2>&1",
                ],
                shell=False,
                timeout_ms=5000,
            )
        if not launch_result or not launch_result["ok"]:
            with stdout_path.open("ab") as out:
                process = subprocess.Popen(
                    shlex.split(exec_cmd),
                    cwd=agent_path_string("/workspace"),
                    env=platform_env(),
                    stdout=out,
                    stderr=out,
                    start_new_session=True,
                )
        launched_window, wait_result = wait_for_launched_window_staged(
            app,
            before_xids=before_xids,
            stages=wait_stages_sec,
        )
        activation = activate_and_maximize_window(
            launched_window,
            maximize=maximize,
            force_maximize=force_maximize,
            fullscreen=fullscreen,
        ) if launched_window else {"ok": False, "error": "launched app window did not appear before timeout"}
        ok = bool(activation.get("ok"))
        result = {
            "ok": ok,
            "app": app,
            "canonical_name": app.get("name", app.get("canonical_name", "")),
            "desktop_file": app.get("desktop_file", ""),
            "resolved_from": app.get("resolved_from", ""),
            "exec": exec_cmd,
            "pid": process.pid if process else None,
            "unit": unit if launch_result and launch_result["ok"] else None,
            "launch": launch_result,
            "log": str(stdout_path),
            "wait_stages_sec": wait_stages_sec,
            "wait": wait_result,
            "window": launched_window,
            "activation": activation,
            "maximize": maximize,
            "force_maximize": force_maximize,
            "fullscreen": fullscreen,
            "fullscreened": bool(activation.get("fullscreened")),
            "maximized": bool(activation.get("maximized")),
        }
        append_log(
            {
                "event": "app_launch",
                "app": app.get("name"),
                "exec": exec_cmd,
                "pid": result["pid"],
                "unit": result["unit"],
                "window": launched_window,
                "wait": wait_result,
                "activation": activation,
                "ok": ok,
            }
        )
        self.send_json(result)

    def find_window_for_activation(self, data):
        xid = data.get("xid", data.get("id", data.get("window_id", "")))
        if xid:
            return window_by_xid(xid) or {"xid": xid}
        needle = (
            data.get("app")
            or data.get("app_name")
            or data.get("name")
            or data.get("title")
            or data.get("window")
            or data.get("window_title")
            or data.get("process")
            or data.get("proc")
            or data.get("query")
            or ""
        ).lower()
        if not needle:
            return self.active_window_from_xprop(running_windows_snapshot())
        for window in running_windows_snapshot():
            hay = " ".join([window.get("app", ""), window.get("proc", ""), window.get("title", "")]).lower()
            if needle in hay:
                return window
        suggestions = window_suggestions(needle, limit=1)
        if suggestions and suggestions[0].get("score", 0) >= 1.0:
            return suggestions[0].get("window")
        return None

    def activate_window_action(self, item):
        window = self.find_window_for_activation(item)
        if not window:
            query = (
                item.get("app")
                or item.get("app_name")
                or item.get("name")
                or item.get("title")
                or item.get("window")
                or item.get("window_title")
                or item.get("process")
                or item.get("proc")
                or item.get("query")
                or ""
            )
            raise ValueError(f"window not found; did_you_mean={json.dumps(window_suggestions(query, limit=5))}")
        # Controller policy: activation always restores a full work window.
        maximize = True
        force_maximize = True
        fullscreen = True
        activation = activate_and_maximize_window(
            window,
            maximize=maximize,
            force_maximize=force_maximize,
            fullscreen=fullscreen,
        )
        if not activation.get("ok"):
            raise ValueError(f"window activation failed: {activation.get('error') or activation.get('warning') or activation}")
        return {
            "window": window,
            "maximize": maximize,
            "force_maximize": force_maximize,
            "fullscreen": fullscreen,
            "activation": activation,
        }

    def handle_window_activate(self):
        data = self.read_json()
        window = self.find_window_for_activation(data)
        if not window:
            query = (
                data.get("app")
                or data.get("app_name")
                or data.get("name")
                or data.get("title")
                or data.get("window")
                or data.get("window_title")
                or data.get("process")
                or data.get("proc")
                or data.get("query")
                or ""
            )
            suggestions = window_suggestions(query, limit=8)
            self.fail(
                "window not found: use one did_you_mean entry exactly, preferably its xid",
                404,
                query=str(query or ""),
                did_you_mean=suggestions,
                examples=[suggestion.get("use") for suggestion in suggestions[:5]],
            )
            return
        xid = window.get("xid") or window.get("id")
        # Controller policy: activation always restores a full work window.
        maximize = True
        force_maximize = True
        fullscreen = True
        activation = activate_and_maximize_window(
            window,
            maximize=maximize,
            force_maximize=force_maximize,
            fullscreen=fullscreen,
        )
        ok = bool(activation.get("ok"))
        append_log(
            {
                "event": "window_activate",
                "xid": xid,
                "window": window,
                "maximize": maximize,
                "force_maximize": force_maximize,
                "fullscreen": fullscreen,
                "before_geometry": activation.get("before_geometry"),
                "after_geometry": activation.get("after_geometry"),
                "ok": ok,
            }
        )
        self.send_json(
            {
                "ok": ok,
                "window": window,
                "maximize": maximize,
                "force_maximize": force_maximize,
                "fullscreen": fullscreen,
                "before_geometry": activation.get("before_geometry"),
                "after_geometry": activation.get("after_geometry"),
                "fullscreened": activation.get("fullscreened"),
                "maximized": activation.get("maximized"),
                "command": activation.get("command"),
                "fullscreen_command": activation.get("fullscreen_command"),
                "maximize_command": activation.get("maximize_command"),
                "fallback_maximize_command": activation.get("fallback_maximize_command"),
                "activation": activation,
            },
            200 if ok else 500,
        )

    def handle_viewer(self):
        self.send_json(
            {
                "ok": True,
                "vnc_public": f"http://{PUBLIC_HOST}:{PUBLIC_VNC_PORT}/",
                "novnc_page": f"http://{PUBLIC_HOST}:{PUBLIC_VNC_PORT}/vnc.html",
                "agent_public": public_agent_base_url(),
            }
        )

    def handle_logs(self, query):
        limit = int(query.get("limit", ["200"])[0])
        if not ACTION_LOG.exists():
            self.send_json({"ok": True, "events": []})
            return
        lines = ACTION_LOG.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]
        events = []
        for line in lines:
            try:
                events.append(json.loads(line))
            except Exception:
                events.append({"raw": line})
        self.send_json({"ok": True, "events": events})

    def handle_windows(self, query):
        self.handle_event_log(query, WINDOW_LOG, "windows")

    def event_limit(self, query, default=200):
        try:
            value = int(query.get("limit", [str(default)])[0])
        except Exception:
            value = default
        return max(1, min(value, 5000))

    def read_event_log(self, path, limit, stream):
        prune_jsonl_log(path)
        prune_event_screenshots()
        if not path.exists():
            return []
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]
        events = []
        for line in lines:
            try:
                item = json.loads(line)
                item.setdefault("stream", stream)
                enrich_screenshot_refs(item)
                events.append(item)
            except Exception:
                events.append({"stream": stream, "raw": line})
        return events

    def handle_event_log(self, query, path, stream):
        limit = self.event_limit(query)
        events = self.read_event_log(path, limit, stream)
        self.send_json({"ok": True, "path": str(path), "events": events})

    def action_log_events(self, limit):
        if not ACTION_LOG.exists():
            return []
        events = []
        for line in ACTION_LOG.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]:
            try:
                item = json.loads(line)
            except Exception:
                continue
            item.setdefault("stream", "actions")
            events.append(item)
        return events

    def window_summary(self, window):
        if not window:
            return None
        xid = window.get("xid") or window.get("id") or window.get("window_id")
        return {
            "xid": xid,
            "id": xid,
            "app": window.get("app") or window.get("proc") or "",
            "title": window.get("title") or "",
            "pid": window.get("pid"),
            "proc": window.get("proc") or "",
            "x": window.get("x"),
            "y": window.get("y"),
            "w": window.get("w"),
            "h": window.get("h"),
            "bbox": window.get("bbox") or [window.get("x"), window.get("y"), window.get("w"), window.get("h")],
            "event": window.get("event"),
            "source": window.get("stream") or window.get("source") or "windows",
        }

    def window_contains_point(self, window, x, y):
        try:
            wx = int(float(window.get("x")))
            wy = int(float(window.get("y")))
            ww = int(float(window.get("w")))
            wh = int(float(window.get("h")))
            px = int(float(x))
            py = int(float(y))
        except Exception:
            return False
        if ww <= 0 or wh <= 0:
            return False
        return wx <= px < wx + ww and wy <= py < wy + wh

    def window_at_point(self, x, y, windows, preferred=None):
        display_x, display_y = framebuffer_to_display_point(x, y)
        if preferred and self.window_contains_point(preferred, display_x, display_y):
            return preferred
        containing = [
            window
            for window in windows
            if not window.get("minimized") and self.window_contains_point(window, display_x, display_y)
        ]
        if not containing:
            return None
        # wmctrl is not a strict stacking-order API. Prefer the smallest containing
        # window so dialogs/toolboxes beat a fullscreen parent.
        containing.sort(key=lambda item: int(item.get("w") or 0) * int(item.get("h") or 0))
        return containing[0]

    def trace_window_events(self, limit):
        events = self.read_event_log(WINDOW_LOG, max(limit, 2000), "windows")
        events.sort(key=self.event_time)
        return events

    def active_window_at_time(self, t, window_events, fallback=None):
        active = None
        latest_by_id = {}
        for item in window_events:
            if self.event_time(item) > t:
                break
            xid = normalize_xid(item.get("id") or item.get("xid") or item.get("window_id"))
            if xid:
                latest_by_id[xid] = item
            if item.get("event") == "focus" and xid:
                active = latest_by_id.get(xid, item)
        return active or fallback

    def accessibility_summary(self, item):
        if not item:
            return None
        return {
            "event": item.get("event"),
            "app": item.get("app"),
            "role": item.get("role"),
            "name": item.get("name"),
            "description": item.get("description"),
            "text": item.get("text"),
            "cell": item.get("cell") or item.get("child"),
            "child_role": item.get("child_role"),
            **timestamp_fields(self.event_time(item)),
        }

    def accessibility_context_at_time(self, t, atspi_events):
        latest = None
        for item in atspi_events:
            if self.event_time(item) > t:
                break
            if item.get("event") in ("focus", "active_descendant", "selection", "typed", "caret", "window_activate"):
                latest = item
        return self.accessibility_summary(latest)

    def trace_accessibility_events(self, limit):
        events = self.read_event_log(ATSPI_LOG, max(limit, 2000), "accessibility")
        events.sort(key=self.event_time)
        return events

    def enrich_input_trace_events(self, events, limit):
        devices = xinput_device_map()
        windows = running_windows_snapshot()
        current_active = self.active_window_from_xprop(windows)
        window_events = self.trace_window_events(limit)
        atspi_events = self.trace_accessibility_events(limit)
        frames = recorded_frame_index()
        mouse = current_mouse_location()
        accessibility_at_mouse = None
        if mouse:
            try:
                obj, pyatspi = self.deepest_accessible_at_point(mouse[0], mouse[1])
                accessibility_at_mouse = self.atspi_object_info(obj, pyatspi)
            except Exception as exc:
                accessibility_at_mouse = {"error": str(exc)}
        for index, event in enumerate(events):
            attach_xinput_device_info(event, devices)
            t = float(event.get("t") or 0)
            active_window = self.active_window_at_time(t, window_events, current_active)
            event["active_window"] = self.window_summary(active_window)
            if event.get("x") is not None and event.get("y") is not None:
                event["window_at_point"] = self.window_summary(
                    self.window_at_point(event.get("x"), event.get("y"), windows, preferred=active_window)
                )
            else:
                event["window_at_point"] = None
            event["accessibility_focus"] = self.accessibility_context_at_time(t, atspi_events)
            attach_recorded_screenshots(event, frames, index)
        return {
            "devices": list(devices.values()),
            "current_active_window": self.window_summary(current_active),
            "accessibility_at_mouse": accessibility_at_mouse,
            "screen_recorder": {
                "frames": len(frames),
                "retention_sec": EVENT_RETENTION_SECONDS,
                "max_frame_delta_sec": SCREEN_RECORDER_MAX_DELTA_SEC,
                "frames_dir": str(SCREEN_RECORDER_DIR / "frames"),
            },
            "running_windows": [self.window_summary(window) for window in windows],
            "recent_accessibility": [
                self.accessibility_summary(item)
                for item in atspi_events[-20:]
                if item.get("event") in ("focus", "active_descendant", "selection", "typed", "caret", "window_activate")
            ],
        }

    def normalized_input_trace_events(self, query):
        limit = self.event_limit(query, default=1000)
        seconds_text = query.get("seconds", query.get("since_seconds", ["10"]))[0]
        since = query.get("since", [""])[0]
        if since:
            cutoff = float(since)
        else:
            try:
                seconds = float(seconds_text)
            except Exception:
                seconds = 10.0
            cutoff = time.time() - seconds if seconds > 0 else 0.0

        events = []
        for item in self.read_event_log(INPUT_LOG, limit, "input"):
            t = self.event_time(item)
            if t < cutoff:
                continue
            event = item.get("event", "")
            if event not in (
                "Motion",
                "RawMotion",
                "ButtonPress",
                "ButtonRelease",
                "RawButtonPress",
                "RawButtonRelease",
                "KeyPress",
                "KeyRelease",
                "RawKeyPress",
                "RawKeyRelease",
            ):
                continue
            raw_x = item.get("x")
            raw_y = item.get("y")
            if raw_x is not None and raw_y is not None:
                fb_x, fb_y = display_to_framebuffer_point(raw_x, raw_y)
            else:
                fb_x, fb_y = None, None
            normalized = {
                "source": "xinput",
                **timestamp_fields(t),
                "event": event,
                "x": fb_x,
                "y": fb_y,
                "display_x": raw_x,
                "display_y": raw_y,
                "coordinate_space": "framebuffer",
                "button": item.get("detail") if "Button" in event else None,
                "keycode": item.get("detail") if "Key" in event else None,
                "key": item.get("key") if "Key" in event else None,
                "key_base": item.get("key_base") if "Key" in event else None,
                "text": item.get("text") if "Key" in event else None,
                "is_printable": item.get("is_printable") if "Key" in event else None,
                "modifiers": item.get("modifiers") if "Key" in event else None,
                "device": item.get("device"),
                "device_name": item.get("device_name"),
                "device_use": item.get("device_use"),
                "device_role": item.get("device_role"),
                "device_master": item.get("device_master"),
                "device_descriptor": item.get("device_descriptor"),
                "raw": item,
            }
            enrich_screenshot_refs(normalized)
            events.append(normalized)

        for item in self.action_log_events(limit):
            t = self.event_time(item)
            if t < cutoff or item.get("event") != "structured_action":
                continue
            actions = item.get("actions") or []
            if not isinstance(actions, list):
                continue
            for index, action in enumerate(actions):
                if not isinstance(action, dict):
                    continue
                typ = action.get("type") or action.get("action")
                event_t = t + index * 0.001
                base = {
                    "source": "bot_action",
                    **timestamp_fields(event_t),
                    "event": typ,
                    "x": action.get("x"),
                    "y": action.get("y"),
                    "button": action.get("button", "left") if typ in ("click", "double_click", "right_click", "middle_click", "mouse_down", "mouse_up", "drag", "drag_to", "drag_between", "drag_from_to", "drag_drop", "drag_window") else None,
                    "keycode": None,
                    "device": None,
                    "device_name": None,
                    "device_use": None,
                    "device_role": None,
                    "device_master": None,
                    "device_descriptor": None,
                    "raw": action,
                }
                if typ in ("drag_between", "drag_from_to", "drag_drop", "drag_window"):
                    base.update(
                        {
                            "start_x": action.get("start_x", action.get("from_x")),
                            "start_y": action.get("start_y", action.get("from_y")),
                            "end_x": action.get("end_x", action.get("to_x", action.get("x"))),
                            "end_y": action.get("end_y", action.get("to_y", action.get("y"))),
                            "duration": action.get("duration"),
                        }
                    )
                events.append(base)

        events.sort(key=lambda e: e.get("t") or 0)
        return events[-limit:]

    def stroke_metrics(self, points):
        clean = [(float(p["x"]), float(p["y"]), float(p.get("t") or 0)) for p in points if p.get("x") is not None and p.get("y") is not None]
        if not clean:
            return {"points": 0}
        distance = 0.0
        for prev, cur in zip(clean, clean[1:]):
            distance += ((cur[0] - prev[0]) ** 2 + (cur[1] - prev[1]) ** 2) ** 0.5
        xs = [p[0] for p in clean]
        ys = [p[1] for p in clean]
        return {
            "points": len(clean),
            "start": {"x": clean[0][0], "y": clean[0][1], "t": clean[0][2]},
            "end": {"x": clean[-1][0], "y": clean[-1][1], "t": clean[-1][2]},
            "bbox": [min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)],
            "distance_px": round(distance, 3),
            "duration_sec": round(max(0.0, clean[-1][2] - clean[0][2]), 3),
        }

    def build_input_strokes(self, events):
        strokes = []
        active = None
        for event in events:
            name = event.get("event", "")
            button = event.get("button")
            if name == "ButtonPress" and button == 1 and event.get("x") is not None and event.get("y") is not None:
                active = {
                    "source": "xinput",
                    "button": button,
                    "device": event.get("device"),
                    "device_name": event.get("device_name"),
                    "device_use": event.get("device_use"),
                    "active_window": event.get("active_window"),
                    "window_at_point": event.get("window_at_point"),
                    "accessibility_focus": event.get("accessibility_focus"),
                    "screenshot": event.get("screenshot"),
                    "partial_screenshot": event.get("partial_screenshot"),
                    "screenshots": event.get("screenshots"),
                    "points": [
                        {
                            "x": event.get("x"),
                            "y": event.get("y"),
                            "event": name,
                            "event_id": event.get("event_id"),
                            "screenshot": event.get("screenshot"),
                            "partial_screenshot": event.get("partial_screenshot"),
                            **timestamp_fields(event.get("t")),
                        }
                    ],
                }
                continue
            if active and name in ("Motion", "RawMotion") and event.get("x") is not None and event.get("y") is not None:
                active["points"].append(
                    {
                        "x": event.get("x"),
                        "y": event.get("y"),
                        "event": name,
                        "event_id": event.get("event_id"),
                        "screenshot": event.get("screenshot"),
                        "partial_screenshot": event.get("partial_screenshot"),
                        **timestamp_fields(event.get("t")),
                    }
                )
                continue
            if active and name == "ButtonRelease" and button == active.get("button"):
                if event.get("x") is not None and event.get("y") is not None:
                    active["points"].append(
                        {
                            "x": event.get("x"),
                            "y": event.get("y"),
                            "event": name,
                            "event_id": event.get("event_id"),
                            "screenshot": event.get("screenshot"),
                            "partial_screenshot": event.get("partial_screenshot"),
                            **timestamp_fields(event.get("t")),
                        }
                    )
                active["metrics"] = self.stroke_metrics(active["points"])
                strokes.append(active)
                active = None
                continue

            if event.get("source") == "bot_action" and name in ("drag_between", "drag_from_to", "drag_drop", "drag_window"):
                points = []
                if event.get("start_x") is not None and event.get("start_y") is not None:
                    points.append(
                        {
                            "x": event.get("start_x"),
                            "y": event.get("start_y"),
                            "event": "start",
                            **timestamp_fields(event.get("t")),
                        }
                    )
                if event.get("end_x") is not None and event.get("end_y") is not None:
                    points.append(
                        {
                            "x": event.get("end_x"),
                            "y": event.get("end_y"),
                            "event": "end",
                            **timestamp_fields(float(event.get("t") or 0) + float(event.get("duration") or 0)),
                        }
                    )
                if points:
                    stroke = {
                        "source": "bot_action",
                        "button": event.get("button"),
                        "active_window": event.get("active_window"),
                        "window_at_point": event.get("window_at_point"),
                        "accessibility_focus": event.get("accessibility_focus"),
                        "points": points,
                        "raw": event.get("raw"),
                    }
                    stroke["metrics"] = self.stroke_metrics(points)
                    strokes.append(stroke)
        return strokes

    def handle_input_trace(self, query):
        events = self.normalized_input_trace_events(query)
        context = self.enrich_input_trace_events(events, self.event_limit(query, default=1000))
        motion = [e for e in events if e.get("event") in ("Motion", "RawMotion") and e.get("x") is not None and e.get("y") is not None]
        clicks = [
            e
            for e in events
            if e.get("event") in ("ButtonPress", "ButtonRelease", "click", "double_click", "right_click", "middle_click", "long_click")
        ]
        keys = [e for e in events if "Key" in str(e.get("event")) or e.get("event") in ("press", "hotkey", "type", "write")]
        strokes = self.build_input_strokes(events)
        fb = framebuffer_geometry()
        mouse = current_mouse_location()
        self.send_json(
            {
                "ok": True,
                "screen": {
                    "width": fb.get("width"),
                    "height": fb.get("height"),
                    "source": fb.get("source"),
                    "coordinate_space": {
                        "x_min": 0,
                        "y_min": 0,
                        "x_max": max(0, int(fb.get("width", 0)) - 1),
                        "y_max": max(0, int(fb.get("height", 0)) - 1),
                    },
                    "mouse": {"x": mouse[0], "y": mouse[1]} if mouse else None,
                },
                "events": events,
                "motion_points": motion,
                "clicks": clicks,
                "keys": keys,
                "strokes": strokes,
                "context": context,
                "counts": {
                    "events": len(events),
                    "motion_points": len(motion),
                    "clicks": len(clicks),
                    "keys": len(keys),
                    "strokes": len(strokes),
                },
                "instruction": "Use strokes[*].points and metrics for drawing geometry. Coordinates are framebuffer pixels. Each event includes timestamp/ts_unix_ms, device mapping, active_window, window_at_point, and accessibility_focus when known.",
            }
        )

    def event_time(self, item):
        if "t" in item:
            try:
                return float(item["t"])
            except Exception:
                return 0.0
        if "ts_unix_ms" in item:
            try:
                return float(item["ts_unix_ms"]) / 1000.0
            except Exception:
                return 0.0
        return 0.0

    def handle_merged_events(self, query):
        limit = self.event_limit(query, default=500)
        streams = {
            "windows": WINDOW_LOG,
            "input": INPUT_LOG,
            "accessibility": ATSPI_LOG,
            "actions": ACTION_LOG,
        }
        requested = query.get("streams", ["windows,input,accessibility"])[0]
        names = [name.strip() for name in requested.split(",") if name.strip()]
        events = []
        paths = {}
        for name in names:
            path = streams.get(name)
            if path is None:
                continue
            paths[name] = str(path)
            events.extend(self.read_event_log(path, limit, name))
        events.sort(key=self.event_time)
        self.send_json({"ok": True, "paths": paths, "events": events[-limit:]})

    def validate_readonly_sql(self, sql):
        sql = (sql or "").strip()
        if sql.endswith(";"):
            sql = sql[:-1].strip()
        if not sql:
            raise ValueError("missing sql")
        if ";" in sql:
            raise ValueError("only one SELECT statement is allowed")
        first = re.match(r"^\s*([A-Za-z]+)", sql)
        first_word = first.group(1).lower() if first else ""
        if first_word not in ("select", "with"):
            raise ValueError("only SELECT/WITH read-only SQL is allowed")
        forbidden = re.search(r"\b(insert|update|delete|drop|alter|create|copy|attach|detach|install|load|pragma)\b", sql, re.I)
        if forbidden:
            raise ValueError(f"forbidden SQL keyword: {forbidden.group(1)}")
        return sql

    def run_readonly_sql(self, sql, max_rows=1000):
        sql = self.validate_readonly_sql(sql)
        max_rows = max(1, min(int(max_rows), 5000))
        start = time.time()
        con = self.event_index_connect()
        try:
            result = con.execute(f"SELECT * FROM ({sql}) AS _nubian_sql LIMIT ?", [max_rows])
            columns = [desc[0] for desc in result.description]
            rows = [dict(zip(columns, [json_safe(value) for value in row])) for row in result.fetchall()]
            return {"ok": True, "db": str(EVENT_DB), "columns": columns, "rows": rows, "row_count": len(rows), "elapsed_ms": int((time.time() - start) * 1000)}
        finally:
            con.close()

    def handle_sql_query(self, query):
        sql = query.get("q", query.get("sql", [""]))[0]
        limit = self.event_limit(query, default=1000)
        self.send_json(self.run_readonly_sql(sql, max_rows=limit))

    def handle_sql_post(self):
        data = self.read_json()
        sql = data.get("sql", data.get("q", ""))
        try:
            limit = int(data.get("limit", 1000))
        except Exception:
            limit = 1000
        limit = max(1, min(limit, 5000))
        self.send_json(self.run_readonly_sql(sql, max_rows=limit))

    def event_index_columns(self):
        return (
            "seq, session_id, stream, t, event, app, id, window_id, title, role, name, "
            "description, text, cell, child, x, y, w, h, pid, proc, detail, screenshot, source_path, source_line, payload"
        )

    def event_index_connect(self):
        if not EVENT_DB.exists():
            raise FileNotFoundError(f"event index not found: {EVENT_DB}")
        import duckdb

        return duckdb.connect(str(EVENT_DB), read_only=True)

    def normalize_event_row(self, row, columns, include_payload=False):
        item = dict(zip(columns, row))
        payload = item.get("payload")
        if include_payload and payload:
            try:
                item["payload"] = json.loads(payload)
            except Exception:
                pass
        elif not include_payload:
            item.pop("payload", None)
        return item

    def query_event_index(self, sql, params=None, include_payload=False):
        params = params or []
        last_error = None
        for _ in range(10):
            con = None
            try:
                con = self.event_index_connect()
                result = con.execute(sql, params)
                columns = [desc[0] for desc in result.description]
                return [self.normalize_event_row(row, columns, include_payload) for row in result.fetchall()]
            except Exception as exc:
                last_error = exc
                time.sleep(0.1)
            finally:
                if con is not None:
                    con.close()
        raise last_error

    def event_query_filters(self, query, skip=None):
        skip = set(skip or [])
        clauses = []
        params = []
        exact_fields = ("seq", "session_id", "stream", "event", "app", "id", "window_id", "role", "name", "pid", "proc")
        for field_name in exact_fields:
            if field_name in skip:
                continue
            value = query.get(field_name, [""])[0]
            if value != "":
                clauses.append(f"{field_name} = ?")
                params.append(int(value) if field_name in ("seq", "pid") else value)
        contains_fields = ("title", "description", "text", "cell", "child")
        for field_name in contains_fields:
            if field_name in skip:
                continue
            value = query.get(field_name, query.get(f"{field_name}_contains", [""]))[0]
            if value:
                clauses.append(f"lower(coalesce({field_name}, '')) LIKE ?")
                params.append(f"%{value.lower()}%")
        q = query.get("q", [""])[0]
        if q and "q" not in skip:
            like = f"%{q.lower()}%"
            clauses.append(
                "(lower(coalesce(app, '')) LIKE ? OR lower(coalesce(title, '')) LIKE ? "
                "OR lower(coalesce(role, '')) LIKE ? OR lower(coalesce(name, '')) LIKE ? "
                "OR lower(coalesce(description, '')) LIKE ? OR lower(coalesce(text, '')) LIKE ? "
                "OR lower(coalesce(cell, '')) LIKE ?)"
            )
            params.extend([like, like, like, like, like, like, like])
        since = query.get("since", [""])[0]
        until = query.get("until", [""])[0]
        if since and "since" not in skip:
            clauses.append("t >= ?")
            params.append(float(since))
        if until and "until" not in skip:
            clauses.append("t <= ?")
            params.append(float(until))
        seq_min = query.get("seq_min", [""])[0]
        seq_max = query.get("seq_max", [""])[0]
        if seq_min and "seq_min" not in skip:
            clauses.append("seq >= ?")
            params.append(int(seq_min))
        if seq_max and "seq_max" not in skip:
            clauses.append("seq <= ?")
            params.append(int(seq_max))
        where = "WHERE " + " AND ".join(clauses) if clauses else ""
        return where, params

    def handle_event_index_status(self):
        payload = {
            "ok": True,
            "indexed": EVENT_DB.exists(),
            "db": str(EVENT_DB),
            "state": str(EVENT_INDEX_STATE),
            "session_file": str(EVENT_SESSION_ID_FILE),
            "raw_logs": {
                "windows": str(WINDOW_LOG),
                "input": str(INPUT_LOG),
                "accessibility": str(ATSPI_LOG),
                "actions": str(ACTION_LOG),
            },
        }
        if EVENT_SESSION_ID_FILE.exists():
            payload["session_id"] = EVENT_SESSION_ID_FILE.read_text(encoding="utf-8", errors="replace").strip()
        if not EVENT_DB.exists():
            self.send_json(payload)
            return
        rows = self.query_event_index(
            "SELECT count(*) AS count, coalesce(max(seq), 0) AS max_seq, min(t) AS min_t, max(t) AS max_t FROM events"
        )
        payload.update(rows[0] if rows else {})
        by_stream = self.query_event_index("SELECT stream, count(*) AS count FROM events GROUP BY stream ORDER BY stream")
        payload["streams"] = by_stream
        self.send_json(payload)

    def handle_event_index_recent(self, query):
        limit = self.event_limit(query, default=50)
        include_payload = query.get("include_payload", ["false"])[0].lower() == "true"
        where, params = self.event_query_filters(query)
        rows = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM (
              SELECT {self.event_index_columns()}
              FROM events
              {where}
              ORDER BY seq DESC
              LIMIT ?
            )
            ORDER BY seq ASC
            """,
            params + [limit],
            include_payload=include_payload,
        )
        self.send_json({"ok": True, "db": str(EVENT_DB), "events": rows})

    def resolve_target_seq(self, query):
        seq = query.get("seq", [""])[0]
        if seq:
            return int(seq)
        target_t = query.get("t", [""])[0]
        include_payload = False
        if target_t:
            where, params = self.event_query_filters(query, skip={"seq", "t", "before", "after"})
            order = "abs(t - ?), seq DESC"
            rows = self.query_event_index(
                f"SELECT seq FROM events {where} ORDER BY {order} LIMIT 1",
                params + [float(target_t)],
                include_payload=include_payload,
            )
        else:
            where, params = self.event_query_filters(query, skip={"before", "after"})
            rows = self.query_event_index(
                f"SELECT seq FROM events {where} ORDER BY seq DESC LIMIT 1",
                params,
                include_payload=include_payload,
            )
        if not rows:
            return None
        return int(rows[0]["seq"])

    def handle_event_index_around(self, query):
        before = max(0, min(int(query.get("before", ["3"])[0]), 200))
        after = max(0, min(int(query.get("after", ["3"])[0]), 200))
        include_payload = query.get("include_payload", ["false"])[0].lower() == "true"
        target_seq = self.resolve_target_seq(query)
        if target_seq is None:
            self.fail("target event not found", 404)
            return
        rows = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE seq BETWEEN ? AND ?
            ORDER BY seq ASC
            """,
            [target_seq - before, target_seq + after],
            include_payload=include_payload,
        )
        self.send_json({"ok": True, "db": str(EVENT_DB), "target_seq": target_seq, "before": before, "after": after, "events": rows})

    def latest_active_window(self):
        rows = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE stream = 'windows' AND event = 'focus' AND id <> '' AND id <> '0x0'
            ORDER BY t DESC, seq DESC
            LIMIT 1
            """
        )
        if rows:
            return rows[0]
        rows = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE stream = 'accessibility' AND event IN ('window_activate', 'focus')
            ORDER BY t DESC, seq DESC
            LIMIT 1
            """
        )
        return rows[0] if rows else None

    def active_window_from_xprop(self, running_windows):
        if IS_WINDOWS:
            try:
                import pygetwindow

                active = pygetwindow.getActiveWindow()
                if not active:
                    return running_windows[0] if running_windows else None
                title = str(getattr(active, "title", "") or "")
                handle = getattr(active, "_hWnd", None)
                for window in running_windows:
                    if handle is not None and window.get("handle") == handle:
                        return window
                    if title and window.get("title") == title:
                        return window
                return {
                    "xid": "pygetwindow:active",
                    "id": "pygetwindow:active",
                    "handle": handle,
                    "title": title,
                    "x": int(getattr(active, "left", 0) or 0),
                    "y": int(getattr(active, "top", 0) or 0),
                    "w": int(getattr(active, "width", 0) or 0),
                    "h": int(getattr(active, "height", 0) or 0),
                }
            except Exception:
                return running_windows[0] if running_windows else None
        if not shutil.which("xprop"):
            return None
        result = run_command("xprop -root _NET_ACTIVE_WINDOW", timeout_ms=2000)
        if not result["ok"]:
            return None
        match = re.search(r"0x[0-9a-fA-F]+", result["stdout"])
        if not match:
            return None
        xid = normalize_xid(match.group(0))
        for window in running_windows:
            if normalize_xid(window.get("xid")) == xid:
                return window
        return {"xid": xid, "id": xid}

    def latest_ui_focus(self):
        rows = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE stream = 'accessibility'
              AND event IN ('focus', 'active_descendant', 'selection', 'typed', 'caret', 'window_activate')
            ORDER BY seq DESC
            LIMIT 1
            """
        )
        return rows[0] if rows else None

    def recent_focus_rows(self, limit=20):
        return self.query_event_index(
            """
            SELECT app, title, id AS xid, window_id, max(t) AS last_seen, max(seq) AS seq
            FROM events
            WHERE stream = 'windows' AND event = 'focus' AND app <> ''
            GROUP BY app, title, id, window_id
            ORDER BY last_seen DESC
            LIMIT ?
            """,
            [limit],
        )

    def screen_object_rows(self, limit=20):
        return self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM (
              SELECT {self.event_index_columns()}
              FROM events
              WHERE stream = 'accessibility'
                AND event IN ('focus', 'active_descendant', 'selection', 'window_activate')
              ORDER BY seq DESC
              LIMIT ?
            )
            ORDER BY seq ASC
            """,
            [limit],
        )

    def latest_action_result(self):
        actions = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE stream = 'actions'
            ORDER BY seq DESC
            LIMIT 1
            """,
            include_payload=True,
        )
        if not actions:
            return None
        action = actions[0]
        after = self.query_event_index(
            f"""
            SELECT {self.event_index_columns()}
            FROM events
            WHERE seq > ?
            ORDER BY seq ASC
            LIMIT 20
            """,
            [int(action["seq"])],
        )
        return {"action": action, "events_after": after}

    def handle_event_index_active_window(self):
        active = self.latest_active_window()
        self.send_json({"ok": True, "db": str(EVENT_DB), "active_window": active})

    def event_trace_line(self, item):
        bits = [f"#{item.get('seq')} [{item.get('stream')}:{item.get('event')}]"]
        app = item.get("app") or ""
        title = item.get("title") or ""
        role = item.get("role") or ""
        name = item.get("name") or ""
        if app:
            bits.append(f"app={app!r}")
        if title:
            bits.append(f"title={title!r}")
        if role or name:
            bits.append(f"ui={role!r}/{name!r}")
        description = item.get("description") or ""
        if description:
            if len(description) > 120:
                description = description[:120] + "..."
            bits.append(f"desc={description!r}")
        for key in ("text", "cell", "child"):
            value = item.get(key) or ""
            if value:
                bits.append(f"{key}={value!r}")
        if item.get("x") is not None and item.get("y") is not None:
                bits.append(f"xy=({item.get('x')},{item.get('y')})")
        return " ".join(bits)

    def event_context_summary(self, active, rows):
        lines = []
        if active:
            app = active.get("app") or "unknown app"
            title = active.get("title") or active.get("name") or "untitled"
            window_id = active.get("id") or active.get("window_id") or ""
            lines.append(f"Active window: {app} - {title}" + (f" ({window_id})" if window_id else ""))
        else:
            lines.append("Active window: unknown")

        last_focus = None
        last_typed = None
        last_click = None
        last_window = None
        for item in rows:
            stream = item.get("stream")
            event = item.get("event")
            if stream == "accessibility" and event in ("focus", "active_descendant", "selection"):
                last_focus = item
            if stream == "accessibility" and event == "typed" and (item.get("text") or ""):
                last_typed = item
            if stream == "input" and event in ("ButtonPress", "ButtonRelease"):
                last_click = item
            if stream == "windows" and event in ("focus", "open", "close", "hide", "title_change", "move"):
                last_window = item

        if last_focus:
            role = last_focus.get("role") or ""
            name = last_focus.get("name") or last_focus.get("cell") or last_focus.get("child") or ""
            desc = last_focus.get("description") or ""
            focus_line = f"Last UI focus: {role} {name}".strip()
            if desc:
                focus_line += f" - {desc[:160]}"
            lines.append(focus_line)
        if last_typed:
            text = last_typed.get("text") or ""
            role = last_typed.get("role") or ""
            name = last_typed.get("name") or ""
            lines.append(f"Last typed text: {text!r} into {role} {name}".strip())
        if last_click:
            detail = last_click.get("detail")
            x = last_click.get("x")
            y = last_click.get("y")
            lines.append(f"Last pointer event: {last_click.get('event')} button={detail} at ({x}, {y})")
        if last_window:
            title = last_window.get("title") or ""
            lines.append(f"Last window event: {last_window.get('event')} {last_window.get('app') or ''} {title}".strip())

        lines.append(f"Recent indexed events returned: {len(rows)}")
        return "\n".join(lines)

    def app_context_patterns(self, app):
        value = (app or "").lower()
        patterns = []
        for needle, pattern in (
            ("terminal", "%terminal%"),
            ("libreoffice", "%soffice%"),
            ("soffice", "%libreoffice%"),
            ("calc", "%soffice%"),
            ("chrome", "%chrome%"),
            ("code", "%code%"),
            ("nautilus", "%nautilus%"),
            ("eog", "%eog%"),
        ):
            if needle in value and pattern not in patterns:
                patterns.append(pattern)
        return patterns

    def handle_event_context(self, query):
        limit = self.event_limit(query, default=30)
        active = self.latest_active_window()
        include_global = query.get("global", ["false"])[0].lower() == "true"
        if active and not include_global:
            app = active.get("app") or ""
            window_id = active.get("id") or active.get("window_id") or ""
            clauses = []
            params = []
            if app:
                clauses.append("app = ?")
                params.append(app)
                for pattern in self.app_context_patterns(app):
                    clauses.append("lower(app) LIKE ?")
                    params.append(pattern)
            if window_id:
                clauses.append("id = ?")
                params.append(window_id)
                clauses.append("window_id = ?")
                params.append(window_id)
            where = " OR ".join(clauses) if clauses else "1=1"
            rows = self.query_event_index(
                f"""
                SELECT {self.event_index_columns()}
                FROM (
                  SELECT {self.event_index_columns()}
                  FROM events
                  WHERE {where}
                  ORDER BY seq DESC
                  LIMIT ?
                )
                ORDER BY seq ASC
                """,
                params + [limit],
            )
        else:
            rows = self.query_event_index(
                f"""
                SELECT {self.event_index_columns()}
                FROM (
                  SELECT {self.event_index_columns()}
                  FROM events
                  ORDER BY seq DESC
                  LIMIT ?
                )
                ORDER BY seq ASC
                """,
                [limit],
            )
        trace = "\n".join(self.event_trace_line(item) for item in rows)
        summary = self.event_context_summary(active, rows)
        self.send_json(
            {
                "ok": True,
                "db": str(EVENT_DB),
                "active_window": active,
                "events": rows,
                "summary": summary,
                "trace": trace,
                "instruction": "The trace is verified event state. Use it with screenshot vision; trust it over visual guesses for focus, typed text, and selected UI elements.",
            }
        )

    def observe_summary(self, focused, running_windows, recent_focus, available_to_launch, filesystem_recent):
        lines = []
        app = focused.get("app") or "unknown"
        window = focused.get("window") or {}
        title = window.get("title") or "unknown"
        lines.append(f"Focused: {app} - {title}")
        if running_windows:
            names = [f"{item.get('app') or item.get('proc') or 'unknown'}:{item.get('title')}" for item in running_windows[:8]]
            lines.append("Running windows: " + "; ".join(names))
        if recent_focus:
            now = time.time()
            names = []
            for item in recent_focus[:8]:
                ago = max(0, round(now - float(item.get("last_seen") or now), 1))
                item["ago_sec"] = ago
                names.append(f"{item.get('app')} {ago}s ago")
            lines.append("Recent focus: " + "; ".join(names))
        if available_to_launch:
            lines.append("Launchable examples: " + "; ".join(item.get("name", "") for item in available_to_launch[:10]))
        if filesystem_recent:
            lines.append("Recent files: " + "; ".join(item.get("path", "") for item in filesystem_recent[:5]))
        return "\n".join(lines)

    def handle_observe(self, query):
        limit = self.event_limit(query, default=20)
        apps_limit = self.event_limit({"limit": query.get("apps_limit", ["60"])}, default=60)
        files_limit = self.event_limit({"limit": query.get("files_limit", ["20"])}, default=20)
        running_windows = running_windows_snapshot()
        active_from_x = self.active_window_from_xprop(running_windows)
        active = None
        ui_focus = None
        recent_focus = []
        screen_objects = {"from_atspi": [], "from_vlm": [], "from_dock": []}
        last_action = None
        try:
            active = self.latest_active_window()
            ui_focus = self.latest_ui_focus()
            recent_focus = self.recent_focus_rows(limit=limit)
            screen_objects["from_atspi"] = self.screen_object_rows(limit=limit)
            last_action = self.latest_action_result()
        except Exception as exc:
            screen_objects["event_index_error"] = str(exc)
        if active_from_x and (not active or normalize_xid(active_from_x.get("xid")) != normalize_xid(active.get("id"))):
            active = {
                "stream": "snapshot",
                "event": "active_window",
                "app": active_from_x.get("app") or active_from_x.get("proc") or "",
                "title": active_from_x.get("title") or "",
                "id": active_from_x.get("xid") or active_from_x.get("id") or "",
                "window_id": active_from_x.get("xid") or active_from_x.get("id") or "",
                "x": active_from_x.get("x"),
                "y": active_from_x.get("y"),
                "w": active_from_x.get("w"),
                "h": active_from_x.get("h"),
                "pid": active_from_x.get("pid"),
                "proc": active_from_x.get("proc"),
            }
        available_to_launch = list_installed_apps(limit=apps_limit)
        filesystem_recent = recent_files_snapshot(limit=files_limit)
        fb = framebuffer_geometry()
        display_w, display_h = display_geometry()
        mouse = current_mouse_location()
        focused = {
            "app": active.get("app") if active else None,
            "window": {
                "title": active.get("title") if active else None,
                "xid": active.get("id") or active.get("window_id") if active else None,
                "bbox": [active.get("x"), active.get("y"), active.get("w"), active.get("h")] if active else None,
                "pid": active.get("pid") if active else None,
                "proc": active.get("proc") if active else None,
            }
            if active
            else None,
            "widget": {
                "app": ui_focus.get("app"),
                "role": ui_focus.get("role"),
                "name": ui_focus.get("name"),
                "description": ui_focus.get("description"),
                "text": ui_focus.get("text"),
                "cell": ui_focus.get("cell") or ui_focus.get("child"),
                "event": ui_focus.get("event"),
                "seq": ui_focus.get("seq"),
            }
            if ui_focus
            else None,
        }
        payload = {
            "ok": True,
            "screen": {
                "width": int(fb.get("width", display_w)),
                "height": int(fb.get("height", display_h)),
                "source": fb.get("source", "framebuffer"),
                "framebuffer_ok": bool(fb.get("ok")),
                "display_geometry": {"width": display_w, "height": display_h},
                "coordinate_space": {
                    "x_min": 0,
                    "y_min": 0,
                    "x_max": max(0, int(fb.get("width", display_w)) - 1),
                    "y_max": max(0, int(fb.get("height", display_h)) - 1),
                },
                "mouse": {"x": mouse[0], "y": mouse[1]} if mouse else None,
            },
            "focused": focused,
            "running_windows": running_windows,
            "recent_focus": recent_focus,
            "running_processes_no_window": running_processes_snapshot(
                exclude_pids=[item.get("pid") for item in running_windows], limit=50
            ),
            "available_to_launch": available_to_launch,
            "screen_objects": screen_objects,
            "last_action_result": last_action,
            "filesystem_recent": filesystem_recent,
            "summary": self.observe_summary(focused, running_windows, recent_focus, available_to_launch, filesystem_recent),
            "instruction": (
                "This is the state of the desktop world. Prefer running_windows/window activation for already-open apps, "
                "available_to_launch plus /apps/launch for new apps, filesystem_recent for artifacts, and screen_objects/from_atspi "
                "as semantic UI state. Use screenshots only for visual gaps."
            ),
        }
        self.send_json(payload)

    def proxy_cdp(self, suffix):
        try:
            status, content_type, body = proxy_get("http://127.0.0.1:9222" + suffix, timeout=5)
            self.send_bytes(body, content_type, status)
        except Exception as exc:
            self.fail("CDP is not listening in this OSWorld Ubuntu guest", 503, detail=str(exc))


def main():
    ensure_dirs()
    display_result = ensure_preferred_display_geometry()
    append_log(
        {
            "event": "agent_start",
            "port": PORT,
            "platform": PLATFORM_SYSTEM or os.name,
            "display": None if IS_WINDOWS else os.environ.get("DISPLAY", DISPLAY),
            "preferred_display": display_result,
        }
    )
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"nubian universal computer-agent listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
