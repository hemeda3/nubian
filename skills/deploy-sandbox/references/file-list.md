# Files installed into the guest VM

Installer puts these under `/home/user/.local/share/nubian-universal-computer-agent/`:

| File | Role |
|------|------|
| `computer_agent.py` | Flask server on :6090, exposes `/eyes/*` `/hands/*` `/memory/*` |
| `window_tracker_x11.py` | X11 window enumeration for evidence reports |
| `xinput_tracker.py` | Input device events |
| `atspi_tracker.py` | Accessibility-tree snapshots (used when X11 evidence is sparse) |
| `event_indexer.py` | duckdb-backed event log for the Web UI replay |
| `screen_recorder.py` | Background ffmpeg recording for replay |
| `recent_event_text.py` | Recent text-input buffer used by the planner |
| `container_port_forwarder.py` | Optional helper for nested container/guest port exposure |
| `ubuntu_desktop_tweaks.sh` | Cursor size, accessibility toolkit, GTK theme tweaks |

Systemd user units under `/home/user/.config/systemd/user/`:

- `nubian-universal-computer-agent.service` — the main Flask server
- `nubian-x11-window-tracker.service`
- `nubian-xinput-tracker.service`
- `nubian-atspi-tracker.service`
- `nubian-event-indexer.service`
- `nubian-screen-recorder.service`

All run as the `user` account (OSWorld default). Inspect with:
```bash
systemctl --user status nubian-universal-computer-agent.service
```
