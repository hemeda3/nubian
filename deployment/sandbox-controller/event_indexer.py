#!/usr/bin/env python3
"""Tail desktop event JSONL logs into a DuckDB query index.

Raw JSONL files remain the source of truth. This process builds a derived
index with session_id and monotonic seq so neighbors/time/app queries are fast.
"""
import json
import os
import pathlib
import time
import uuid

LOG_DIR = pathlib.Path(os.environ.get("COMPUTER_AGENT_LOG_DIR", "/logs"))
DB_PATH = pathlib.Path(os.environ.get("EVENT_INDEX_DB", "/logs/events.duckdb"))
STATE_PATH = pathlib.Path(os.environ.get("EVENT_INDEX_STATE", "/logs/event-indexer-state.json"))
SESSION_ID_PATH = pathlib.Path(os.environ.get("EVENT_SESSION_ID_FILE", "/logs/session-id"))
POLL_INTERVAL = float(os.environ.get("EVENT_INDEX_POLL_INTERVAL_SEC", "0.5"))
BATCH_LIMIT = int(os.environ.get("EVENT_INDEX_BATCH_LIMIT", "2000"))
RETENTION_SECONDS = float(os.environ.get("EVENT_INDEX_RETENTION_SEC", os.environ.get("EVENT_RETENTION_SEC", "3600")))
PRUNE_INTERVAL_SEC = float(os.environ.get("EVENT_INDEX_PRUNE_INTERVAL_SEC", "5"))

SOURCES = {
    "windows": pathlib.Path(os.environ.get("WINDOW_TRACKER_LOG", "/logs/windows.jsonl")),
    "input": pathlib.Path(os.environ.get("XINPUT_TRACKER_LOG", "/logs/input-events.jsonl")),
    "accessibility": pathlib.Path(os.environ.get("ATSPI_TRACKER_LOG", "/logs/atspi-events.jsonl")),
    "actions": LOG_DIR / "computer-agent-actions.jsonl",
}


def connect():
    import duckdb

    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(str(DB_PATH))
    con.execute("PRAGMA threads=1")
    return con


def ensure_schema():
    con = connect()
    try:
        con.execute(
            """
            CREATE TABLE IF NOT EXISTS events (
              seq BIGINT,
              session_id VARCHAR,
              stream VARCHAR,
              t DOUBLE,
              event VARCHAR,
              app VARCHAR,
              id VARCHAR,
              title VARCHAR,
              role VARCHAR,
              name VARCHAR,
              description VARCHAR,
              text VARCHAR,
              cell VARCHAR,
              child VARCHAR,
              x DOUBLE,
              y DOUBLE,
              w DOUBLE,
              h DOUBLE,
              window_id VARCHAR,
              pid BIGINT,
              proc VARCHAR,
              detail BIGINT,
              screenshot VARCHAR,
              source_path VARCHAR,
              source_line BIGINT,
              payload VARCHAR
            )
            """
        )
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq)")
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_t ON events(t)")
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_stream_t ON events(stream, t)")
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_app_t ON events(app, t)")
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_event_t ON events(event, t)")
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_id_t ON events(id, t)")
        for ddl in (
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS text VARCHAR",
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS cell VARCHAR",
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS child VARCHAR",
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS description VARCHAR",
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS window_id VARCHAR",
            "ALTER TABLE events ADD COLUMN IF NOT EXISTS screenshot VARCHAR",
        ):
            con.execute(ddl)
        con.execute("CREATE INDEX IF NOT EXISTS idx_events_window_t ON events(window_id, t)")
    finally:
        con.close()


def table_count():
    con = connect()
    try:
        return int(con.execute("SELECT count(*) FROM events").fetchone()[0])
    finally:
        con.close()


def next_seq():
    con = connect()
    try:
        value = con.execute("SELECT coalesce(max(seq), 0) + 1 FROM events").fetchone()[0]
        return int(value)
    finally:
        con.close()


def session_id():
    SESSION_ID_PATH.parent.mkdir(parents=True, exist_ok=True)
    if SESSION_ID_PATH.exists():
        value = SESSION_ID_PATH.read_text(encoding="utf-8").strip()
        if value:
            return value
    value = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:12]
    SESSION_ID_PATH.write_text(value + "\n", encoding="utf-8")
    return value


def load_state():
    if not STATE_PATH.exists():
        return {"sources": {}}
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {"sources": {}}


def save_state(state):
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(STATE_PATH)


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
    return time.time()


def as_float(value):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except Exception:
        return None


def as_int(value):
    if value is None or value == "":
        return None
    try:
        return int(value)
    except Exception:
        return None


def field(record, *names):
    for name in names:
        if name in record and record[name] not in (None, ""):
            return record[name]
    return None


def row_for(seq, sid, stream, record, source_path, source_line):
    return (
        seq,
        sid,
        stream,
        event_time(record),
        str(field(record, "event", "type") or ""),
        str(field(record, "app") or ""),
        str(field(record, "id") or ""),
        str(field(record, "title") or ""),
        str(field(record, "role") or ""),
        str(field(record, "name") or ""),
        str(field(record, "description") or ""),
        str(field(record, "text") or ""),
        str(field(record, "cell") or ""),
        str(field(record, "child") or ""),
        as_float(field(record, "x")),
        as_float(field(record, "y")),
        as_float(field(record, "w")),
        as_float(field(record, "h")),
        str(field(record, "window_id", "id") or ""),
        as_int(field(record, "pid")),
        str(field(record, "proc") or ""),
        as_int(field(record, "detail", "btn")),
        str(field(record, "screenshot") or ""),
        str(source_path),
        int(source_line),
        json.dumps(record, ensure_ascii=False, sort_keys=True),
    )


def insert_records(records):
    if not records:
        return 0
    sid = session_id()
    records.sort(key=lambda item: (event_time(item["record"]), item["stream"], item["source_line"]))
    seq = next_seq()
    rows = []
    for item in records:
        rows.append(row_for(seq, sid, item["stream"], item["record"], item["source_path"], item["source_line"]))
        seq += 1
    con = connect()
    try:
        con.executemany(
            """
            INSERT INTO events (
              seq, session_id, stream, t, event, app, id, title, role, name,
              description, text, cell, child, x, y, w, h, window_id, pid, proc, detail,
              screenshot, source_path, source_line, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            rows,
        )
    finally:
        con.close()
    return len(rows)


def prune_old_rows():
    if RETENTION_SECONDS <= 0:
        return 0
    cutoff = time.time() - RETENTION_SECONDS
    con = connect()
    try:
        deleted = con.execute("DELETE FROM events WHERE t < ?", [cutoff]).rowcount
        return int(deleted or 0)
    finally:
        con.close()


def read_from_source(stream, path, state, max_lines):
    path = pathlib.Path(path)
    if not path.exists():
        return []
    key = str(path)
    source_state = state.setdefault("sources", {}).setdefault(key, {"offset": 0, "line": 0})
    size = path.stat().st_size
    if int(source_state.get("offset", 0)) > size:
        source_state["offset"] = 0
        source_state["line"] = 0
    records = []
    with path.open("r", encoding="utf-8", errors="replace") as f:
        f.seek(int(source_state.get("offset", 0)))
        while len(records) < max_lines:
            line = f.readline()
            if not line:
                break
            source_state["line"] = int(source_state.get("line", 0)) + 1
            source_state["offset"] = f.tell()
            try:
                record = json.loads(line)
            except Exception:
                record = {"raw": line.rstrip("\n"), "event": "malformed_json"}
            records.append(
                {
                    "stream": stream,
                    "record": record,
                    "source_path": str(path),
                    "source_line": int(source_state["line"]),
                }
            )
    return records


def bootstrap_if_needed(state):
    if table_count() > 0:
        return state
    state = {"sources": {}}
    records = []
    for stream, path in SOURCES.items():
        records.extend(read_from_source(stream, path, state, max_lines=10_000_000))
    inserted = insert_records(records)
    save_state(state)
    print(json.dumps({"event": "bootstrap", "inserted": inserted, "db": str(DB_PATH)}), flush=True)
    return state


def main():
    ensure_schema()
    state = bootstrap_if_needed(load_state())
    print(
        json.dumps(
            {
                "event": "start",
                "db": str(DB_PATH),
                "state": str(STATE_PATH),
                "session_id": session_id(),
                "sources": {name: str(path) for name, path in SOURCES.items()},
            },
            sort_keys=True,
        ),
        flush=True,
    )
    while True:
        batch = []
        per_source = max(1, BATCH_LIMIT // max(1, len(SOURCES)))
        for stream, path in SOURCES.items():
            batch.extend(read_from_source(stream, path, state, per_source))
        if batch:
            inserted = insert_records(batch)
            save_state(state)
            print(json.dumps({"event": "indexed", "inserted": inserted}), flush=True)
        now = time.time()
        if RETENTION_SECONDS > 0 and now - state.get("last_prune_ts", 0) >= PRUNE_INTERVAL_SEC:
            deleted = prune_old_rows()
            state["last_prune_ts"] = now
            save_state(state)
            if deleted:
                print(json.dumps({"event": "pruned", "deleted": deleted, "retention_sec": RETENTION_SECONDS}), flush=True)
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
