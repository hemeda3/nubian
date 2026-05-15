#!/usr/bin/env bash
set -euo pipefail

# Live Ubuntu desktop tweaks for agent-visible UI.
# Intended to be safe to run repeatedly inside the OSWorld Ubuntu guest.

export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/1000}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=/run/user/1000/bus}"

CURSOR_SIZE="${NUBIAN_CURSOR_SIZE:-32}"
CURSOR_THEME="${NUBIAN_CURSOR_THEME:-}"
XRESOURCES="${HOME:-/home/user}/.Xresources"

if command -v gsettings >/dev/null 2>&1; then
  gsettings set org.gnome.desktop.interface cursor-size "$CURSOR_SIZE" || true
  if [ -n "$CURSOR_THEME" ]; then
    gsettings set org.gnome.desktop.interface cursor-theme "$CURSOR_THEME" || true
  fi
fi

if command -v xrdb >/dev/null 2>&1; then
  mkdir -p "$(dirname "$XRESOURCES")"
  touch "$XRESOURCES"
  tmp="$(mktemp)"
  grep -v '^Xcursor\.size:' "$XRESOURCES" > "$tmp" || true
  printf 'Xcursor.size: %s\n' "$CURSOR_SIZE" >> "$tmp"
  mv "$tmp" "$XRESOURCES"
  xrdb -merge "$XRESOURCES" || true
fi

if command -v xsetroot >/dev/null 2>&1; then
  xsetroot -cursor_name left_ptr || true
fi

echo "ubuntu_desktop_tweaks: cursor_size=$CURSOR_SIZE cursor_theme=${CURSOR_THEME:-default}"
