#!/usr/bin/env python3
"""AT-SPI accessibility event tracker.

Writes one JSON line per app-level accessibility event to stdout and
/logs/atspi-events.jsonl. This sees focus, text, selection, active descendant,
and window activation events for apps that expose AT-SPI accessibility data.
"""
import json
import os
import pathlib
import sys
import threading
import time

LOG_PATH = pathlib.Path(os.environ.get("ATSPI_TRACKER_LOG", "/logs/atspi-events.jsonl"))
MAX_TEXT_LENGTH = int(os.environ.get("ATSPI_MAX_TEXT_LENGTH", "1000"))
RETENTION_SECONDS = float(os.environ.get("COMPUTER_AGENT_EVENT_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "3600")))
PRUNE_INTERVAL_SEC = float(os.environ.get("ATSPI_LOG_PRUNE_INTERVAL_SEC", "5"))
TEXT_POLL_INTERVAL_SEC = float(os.environ.get("ATSPI_TEXT_POLL_INTERVAL_SEC", "0.5"))
FOCUS_SEARCH_MAX_NODES = int(os.environ.get("ATSPI_FOCUS_SEARCH_MAX_NODES", "2000"))
LAST_PRUNE_TS = 0.0
LAST_FOCUS = {"obj": None, "key": "", "text": ""}


def event_time(record):
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


def prune_log(force=False):
    global LAST_PRUNE_TS
    if RETENTION_SECONDS <= 0 or not LOG_PATH.exists():
        return
    now = time.time()
    if not force and now - LAST_PRUNE_TS < PRUNE_INTERVAL_SEC:
        return
    LAST_PRUNE_TS = now
    cutoff = now - RETENTION_SECONDS
    try:
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


def write_event(record):
    prune_log()
    t = float(record.get("t") or time.time())
    ts_unix_ms = int(round(t * 1000))
    record["t"] = t
    record["ts_unix_ms"] = ts_unix_ms
    record["timestamp"] = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts_unix_ms // 1000)) + f".{ts_unix_ms % 1000:03d}Z"
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, ensure_ascii=False, sort_keys=True, default=str)
    print(line, flush=True)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def safe(fn, default=""):
    try:
        value = fn()
        return default if value is None else value
    except Exception:
        return default


def safe_text(value):
    if value is None:
        return ""
    if isinstance(value, bytes):
        value = value.decode("utf-8", "replace")
    value = str(value)
    if len(value) > MAX_TEXT_LENGTH:
        return value[:MAX_TEXT_LENGTH] + f"...<truncated {len(value) - MAX_TEXT_LENGTH} chars>"
    return value


try:
    import pyatspi
except Exception as exc:
    write_event({"t": round(time.time(), 3), "event": "fatal", "error": f"cannot import pyatspi: {exc}"})
    sys.exit(1)


def accessible_info(src):
    app = safe(lambda: src.getApplication())
    return {
        "app": safe(lambda: app.name),
        "role": safe(lambda: src.getRoleName()),
        "name": safe(lambda: src.name),
        "description": safe(lambda: src.description),
    }


def emit(kind, src, **extra):
    record = {"t": round(time.time(), 3), "event": kind}
    record.update(accessible_info(src))
    record.update(extra)
    write_event(record)


def object_key(src):
    if src is None:
        return ""
    try:
        app = src.getApplication()
        app_name = app.name if app else ""
    except Exception:
        app_name = ""
    return "|".join(
        [
            safe(lambda: app_name),
            safe(lambda: src.getRoleName()),
            safe(lambda: src.name),
            safe(lambda: src.description),
        ]
    )


def object_text(src):
    if src is None:
        return ""
    try:
        text_iface = src.queryText()
        count = int(safe(lambda: text_iface.characterCount, 0) or 0)
        if count <= 0:
            return ""
        return safe_text(text_iface.getText(0, min(count, MAX_TEXT_LENGTH)))
    except Exception:
        return ""


def remember_focus(src):
    if src is None:
        return
    LAST_FOCUS["obj"] = src
    LAST_FOCUS["key"] = object_key(src)
    LAST_FOCUS["text"] = object_text(src)


def has_focused_state(src):
    try:
        states = src.getState()
        return bool(states.contains(pyatspi.STATE_FOCUSED))
    except Exception:
        return False


def find_focused_object():
    try:
        desktop = pyatspi.Registry.getDesktop(0)
    except Exception:
        return None
    queue = []
    try:
        for i in range(safe(lambda: desktop.childCount, 0)):
            child = safe(lambda i=i: desktop.getChildAtIndex(i), None)
            if child is not None:
                queue.append(child)
    except Exception:
        return None
    seen = 0
    while queue and seen < FOCUS_SEARCH_MAX_NODES:
        obj = queue.pop(0)
        seen += 1
        if has_focused_state(obj):
            return obj
        try:
            count = int(safe(lambda obj=obj: obj.childCount, 0) or 0)
        except Exception:
            count = 0
        for index in range(min(count, 80)):
            child = safe(lambda obj=obj, index=index: obj.getChildAtIndex(index), None)
            if child is not None:
                queue.append(child)
    return None


def text_poll_loop():
    while True:
        time.sleep(max(0.1, TEXT_POLL_INTERVAL_SEC))
        src = LAST_FOCUS.get("obj")
        if src is None or object_key(src) != LAST_FOCUS.get("key"):
            src = find_focused_object()
            if src is not None:
                remember_focus(src)
        if src is None:
            continue
        text = object_text(src)
        if text and text != LAST_FOCUS.get("text"):
            LAST_FOCUS["obj"] = src
            LAST_FOCUS["key"] = object_key(src)
            LAST_FOCUS["text"] = text
            emit("text_snapshot", src, text=text)


def on_event(event):
    event_type = safe(lambda: event.type)
    src = safe(lambda: event.source, None)
    if src is None:
        write_event({"t": round(time.time(), 3), "event": event_type or "unknown", "source": ""})
        return

    if event_type == "object:state-changed:focused":
        if safe(lambda: event.detail1, 0):
            remember_focus(src)
            emit("focus", src)
    elif event_type == "object:text-changed:insert":
        remember_focus(src)
        emit("typed", src, text=safe_text(safe(lambda: event.any_data)), pos=safe(lambda: event.detail1, 0))
    elif event_type == "object:text-changed:delete":
        remember_focus(src)
        emit("deleted", src, length=safe(lambda: event.detail2, 0), pos=safe(lambda: event.detail1, 0))
    elif event_type == "object:text-caret-moved":
        remember_focus(src)
        emit("caret", src, pos=safe(lambda: event.detail1, 0))
    elif event_type == "object:active-descendant-changed":
        cell = ""
        child_role = ""
        try:
            child = src[event.detail1]
            cell = safe(lambda: child.name)
            child_role = safe(lambda: child.getRoleName())
        except Exception:
            pass
        emit("active_descendant", src, child=cell, child_role=child_role)
    elif event_type == "object:selection-changed":
        emit("selection", src)
    elif event_type.startswith("window:"):
        emit(event_type.replace(":", "_"), src)
    else:
        emit(event_type.replace(":", "_"), src)


def emit_existing_accessibles():
    desktop = pyatspi.Registry.getDesktop(0)
    for i in range(safe(lambda: desktop.childCount, 0)):
        app = safe(lambda i=i: desktop.getChildAtIndex(i), None)
        if app is None:
            continue
        write_event(
            {
                "t": round(time.time(), 3),
                "event": "existing_app",
                "app": safe(lambda app=app: app.name),
                "role": safe(lambda app=app: app.getRoleName()),
                "name": safe(lambda app=app: app.name),
                "child_count": safe(lambda app=app: app.childCount, 0),
            }
        )


def main():
    os.environ.setdefault("NO_AT_BRIDGE", "0")
    write_event(
        {
            "t": round(time.time(), 3),
            "event": "start",
            "display": os.environ.get("DISPLAY", "?"),
            "session": os.environ.get("XDG_SESSION_TYPE", "?"),
            "log_path": str(LOG_PATH),
        }
    )
    emit_existing_accessibles()
    threading.Thread(target=text_poll_loop, daemon=True).start()
    events = (
        "object:state-changed:focused",
        "object:text-changed:insert",
        "object:text-changed:delete",
        "object:text-caret-moved",
        "object:active-descendant-changed",
        "object:selection-changed",
        "window:activate",
        "window:deactivate",
    )
    for event_name in events:
        pyatspi.Registry.registerEventListener(on_event, event_name)
    pyatspi.Registry.start()


if __name__ == "__main__":
    main()
