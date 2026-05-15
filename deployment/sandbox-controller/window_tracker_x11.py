#!/usr/bin/env python3
"""X11 window tracker for Ubuntu desktops.

Writes one JSON line per window event to stdout and /logs/windows.jsonl.
"""
import json
import os
import pathlib
import time

from Xlib import X, Xatom, display
from Xlib.error import BadDrawable, BadWindow

LOG_PATH = pathlib.Path(os.environ.get("WINDOW_TRACKER_LOG", "/logs/windows.jsonl"))
RETENTION_SECONDS = float(os.environ.get("COMPUTER_AGENT_EVENT_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "300")))
PRUNE_INTERVAL_SEC = float(os.environ.get("WINDOW_LOG_PRUNE_INTERVAL_SEC", "5"))
LAST_PRUNE_TS = 0.0


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
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, ensure_ascii=False, sort_keys=True)
    print(line, flush=True)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


d = display.Display()
root = d.screen().root


def atom(name):
    return d.intern_atom(name)


NET_WM_NAME = atom("_NET_WM_NAME")
NET_WM_PID = atom("_NET_WM_PID")
UTF8_STRING = atom("UTF8_STRING")
NET_ACTIVE_WINDOW = atom("_NET_ACTIVE_WINDOW")
NET_CLIENT_LIST = atom("_NET_CLIENT_LIST")

root.change_attributes(event_mask=X.SubstructureNotifyMask | X.PropertyChangeMask)


def safe(fn, *args, **kwargs):
    try:
        return fn(*args, **kwargs)
    except (BadWindow, BadDrawable, AttributeError, ValueError):
        return None


def get_prop(win, atom_value, type_=Xatom.STRING):
    result = safe(win.get_full_property, atom_value, type_)
    return result.value if result else None


def title_of(win):
    value = get_prop(win, NET_WM_NAME, UTF8_STRING) or get_prop(win, Xatom.WM_NAME, Xatom.STRING)
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return value or ""


def app_of(win):
    cls = safe(win.get_wm_class)
    return cls[1] if cls else ""


def pid_of(win):
    value = get_prop(win, NET_WM_PID, Xatom.CARDINAL)
    if value is None:
        return 0
    try:
        return int(value[0])
    except Exception:
        return 0


def proc_name(pid):
    try:
        return pathlib.Path(f"/proc/{pid}/comm").read_text(encoding="utf-8").strip()
    except Exception:
        return ""


def abs_pos(win):
    x = y = 0
    cur = win
    try:
        while cur and cur.id != root.id:
            geom = cur.get_geometry()
            x += geom.x
            y += geom.y
            tree = cur.query_tree()
            cur = tree.parent if tree else None
    except Exception:
        pass
    return x, y


def is_override_redirect(win):
    attrs = safe(win.get_attributes)
    return bool(attrs.override_redirect) if attrs else False


def info(win):
    geom = safe(win.get_geometry)
    ax, ay = abs_pos(win)
    pid = pid_of(win)
    return {
        "id": hex(win.id),
        "app": app_of(win),
        "title": title_of(win),
        "pid": pid,
        "proc": proc_name(pid) if pid else "",
        "x": ax,
        "y": ay,
        "w": geom.width if geom else 0,
        "h": geom.height if geom else 0,
        "override_redirect": is_override_redirect(win),
    }


def watch(win):
    safe(win.change_attributes, event_mask=X.PropertyChangeMask | X.StructureNotifyMask)


def emit(kind, win, extra=None):
    record = {"t": round(time.time(), 3), "event": kind}
    record.update(info(win))
    if extra:
        record.update(extra)
    write_event(record)


tree = safe(root.query_tree)
if tree:
    for child in tree.children:
        watch(child)

write_event(
    {
        "t": round(time.time(), 3),
        "event": "start",
        "session": os.environ.get("XDG_SESSION_TYPE", "?"),
        "display": os.environ.get("DISPLAY", "?"),
        "log_path": str(LOG_PATH),
    }
)

client_list = get_prop(root, NET_CLIENT_LIST, Xatom.WINDOW)
if client_list is not None:
    for wid in client_list:
        win = d.create_resource_object("window", int(wid))
        watch(win)
        emit("existing", win)

while True:
    event = d.next_event()
    etype = event.type
    if etype == X.CreateNotify:
        watch(event.window)
        emit("create", event.window)
    elif etype == X.MapNotify:
        watch(event.window)
        emit("open", event.window)
    elif etype == X.UnmapNotify:
        emit("hide", event.window)
    elif etype == X.DestroyNotify:
        emit("close", event.window)
    elif etype == X.ConfigureNotify:
        emit("move", event.window, {"x": event.x, "y": event.y, "w": event.width, "h": event.height})
    elif etype == X.PropertyNotify:
        if event.atom in (NET_WM_NAME, Xatom.WM_NAME):
            emit("title_change", event.window)
        elif event.atom == NET_ACTIVE_WINDOW and event.window.id == root.id:
            active = get_prop(root, NET_ACTIVE_WINDOW, Xatom.WINDOW)
            if active:
                emit("focus", d.create_resource_object("window", int(active[0])))
