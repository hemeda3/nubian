#!/usr/bin/env python3
"""Print recent desktop event journey ordered by time."""

import argparse
import json
import time
import urllib.parse
import urllib.request


def fetch_json(url, timeout=12):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def event_time(event):
    try:
        return float(event.get("t") or 0)
    except Exception:
        return 0.0


def short_text(value, limit=80):
    value = "" if value is None else str(value)
    value = value.replace("\n", "\\n").replace("\t", "\\t").replace("\b", "\\b")
    if len(value) > limit:
        return value[:limit] + f"...<{len(value) - limit} more>"
    return value


def label_event(event):
    stream = event.get("stream") or event.get("source") or ""
    name = event.get("event") or ""
    parts = [stream, name]
    app = event.get("app") or ""
    role = event.get("role") or ""
    widget = event.get("name") or ""
    if app:
        parts.append(f"app={app}")
    if role or widget:
        parts.append(f"ui={role}/{widget}".rstrip("/"))
    if event.get("x") is not None and event.get("y") is not None:
        parts.append(f"xy=({event.get('x')},{event.get('y')})")
    if "Key" in str(name):
        parts.append(f"key={event.get('key') or event.get('detail')}")
    text = event.get("text")
    if text not in (None, ""):
        parts.append(f"text={short_text(text)!r}")
    return " ".join(str(part) for part in parts if part)


def printable_join(events):
    chars = []
    for event in events:
        if event.get("stream") == "input" and event.get("event") == "KeyPress" and event.get("is_printable") is True:
            chars.append(event.get("text") or "")
    return "".join(chars)


def printable_keypresses(events):
    return [
        event
        for event in events
        if event.get("stream") == "input"
        and event.get("event") == "KeyPress"
        and event.get("is_printable") is True
        and event.get("text") is not None
    ]


def char_label(text):
    if text == "\n":
        return "ENTER"
    if text == "\t":
        return "TAB"
    if text == "\b":
        return "BACKSPACE"
    if text == " ":
        return "SPACE"
    return text


def atspi_join(events):
    chars = []
    for event in events:
        if event.get("stream") == "accessibility" and event.get("event") == "typed":
            text = event.get("text") or ""
            if text:
                chars.append(text)
    return "".join(chars)


def event_ts(event):
    return event.get("timestamp") or f"{event_time(event):.3f}"


def event_xy(event):
    x = event.get("x")
    y = event.get("y")
    return f"({x},{y})" if x is not None and y is not None else "(?,?)"


def print_chars(events):
    print("os_xinput_chars:")
    for event in printable_keypresses(events):
        print(
            f"  {event_ts(event)} char={char_label(event.get('text') or '')!r} "
            f"key={event.get('key')} xy={event_xy(event)}"
        )


def print_clicks(events):
    print("os_xinput_clicks:")
    for event in events:
        if event.get("stream") == "input" and event.get("event") in ("ButtonPress", "ButtonRelease"):
            print(f"  {event_ts(event)} {event.get('event')} button={event.get('detail')} xy={event_xy(event)}")


def print_key_events(events):
    print("os_xinput_keys:")
    for event in events:
        if event.get("stream") == "input" and "Key" in str(event.get("event") or ""):
            text = event.get("text")
            text_part = f" text={char_label(text)!r}" if text not in (None, "") else ""
            print(
                f"  {event_ts(event)} {event.get('event')} key={event.get('key') or event.get('detail')}"
                f"{text_part} xy={event_xy(event)}"
            )


def print_windows(events):
    print("windows:")
    for event in events:
        if event.get("stream") != "windows":
            continue
        app = event.get("app") or ""
        title = event.get("title") or ""
        print(f"  {event_ts(event)} {event.get('event')} app={app!r} title={short_text(title, 120)!r} xy={event_xy(event)}")


def print_a11y(events):
    print("a11y:")
    for event in events:
        if event.get("stream") != "accessibility":
            continue
        app = event.get("app") or ""
        role = event.get("role") or ""
        name = event.get("name") or ""
        text = event.get("text")
        text_part = f" text={short_text(text, 120)!r}" if text not in (None, "") else ""
        print(f"  {event_ts(event)} {event.get('event')} app={app!r} ui={role!r}/{name!r}{text_part}")


def print_timeline(events):
    print("timeline:")
    for event in events:
        print(f"  {event_ts(event)} {label_event(event)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://YOUR_HOST:28006/agent")
    parser.add_argument("--seconds", type=float, default=60)
    parser.add_argument("--limit", type=int, default=2000)
    parser.add_argument("--with-a11y", action="store_true", help="also include AT-SPI accessibility events")
    parser.add_argument("--with-windows", action="store_true", help="also include X11 window events")
    parser.add_argument("--journey", action="store_true", help="include windows and A11y, grouped into sections")
    parser.add_argument("--all", action="store_true", help="print all events, not only text/key/pointer/focus events")
    args = parser.parse_args()

    base = args.base.rstrip("/")
    streams = ["input"]
    if args.with_windows or args.journey:
        streams.append("windows")
    if args.with_a11y or args.journey:
        streams.append("accessibility")
    query = urllib.parse.urlencode({"limit": args.limit, "streams": ",".join(streams)})
    payload = fetch_json(f"{base}/eyes/events?{query}")
    cutoff = time.time() - max(0, args.seconds)
    events = []
    for event in payload.get("events", []):
        if event_time(event) < cutoff:
            continue
        if not args.all:
            name = str(event.get("event") or "")
            text = event.get("text")
            if not (
                text not in (None, "")
                or "Key" in name
                or name in ("focus", "window_activate", "window_deactivate", "ButtonPress", "ButtonRelease")
            ):
                continue
        events.append(event)
    events.sort(key=event_time)

    print(f"window_seconds={args.seconds:g} events={len(events)}")
    print(f"os_xinput_printable={short_text(printable_join(events), 300)!r}")
    if args.with_a11y or args.journey:
        print(f"atspi_typed={short_text(atspi_join(events), 300)!r}")
    print_chars(events)
    print_clicks(events)
    if args.with_windows or args.journey:
        print_windows(events)
    if args.with_a11y or args.journey:
        print_a11y(events)
    if not args.journey:
        print_timeline(events)
    elif args.all:
        print_key_events(events)
        print_timeline(events)


if __name__ == "__main__":
    main()
