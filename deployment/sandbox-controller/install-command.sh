set -e
printf 'password\n' | sudo -S mkdir -p /workspace /workspace/agent-demo /downloads /uploads /logs /tmp/agent
printf 'password\n' | sudo -S chown -R user:user /workspace /downloads /uploads /logs /tmp/agent
if ! command -v xdotool >/dev/null 2>&1 || ! command -v wmctrl >/dev/null 2>&1 || ! command -v xclip >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y xdotool wmctrl xclip
fi
if ! command -v xrdb >/dev/null 2>&1 || ! command -v xsetroot >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y x11-xserver-utils
fi
if ! command -v xinput >/dev/null 2>&1 || ! python3 -c 'import pyatspi' >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y xinput at-spi2-core python3-pyatspi
fi
if ! python3 -c 'import Xlib' >/dev/null 2>&1; then
  python3 -m pip install --user python-xlib
fi
if ! python3 -c 'import duckdb' >/dev/null 2>&1; then
  python3 -m pip install --user duckdb
fi
if ! python3 -c 'import pyautogui, requests, websocket' >/dev/null 2>&1; then
  python3 -m pip install --user pyautogui requests websocket-client pillow
fi
if ! python3 -c 'import numpy, paddle, paddleocr; assert numpy.__version__.startswith("1.26."); assert paddle.__version__.startswith("3.2.")' >/dev/null 2>&1; then
  python3 -m pip install --user --disable-pip-version-check --force-reinstall 'numpy==1.26.4' 'paddlepaddle==3.2.2' paddleocr
fi
if ! python3 -m ruff version >/dev/null 2>&1; then
  python3 -m pip install --user --disable-pip-version-check ruff
fi
mkdir -p /home/user/.local/share/nubian-universal-computer-agent /home/user/.config/systemd/user
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/computer_agent.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/window_tracker_x11.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/xinput_tracker.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/atspi_tracker.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/event_indexer.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/screen_recorder.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/ubuntu_desktop_tweaks.sh
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/computer_agent.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/window_tracker_x11.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/xinput_tracker.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/atspi_tracker.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/event_indexer.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/screen_recorder.py
export XDG_RUNTIME_DIR=/run/user/1000
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus
export NUBIAN_CURSOR_SIZE="32"
gsettings set org.gnome.desktop.interface toolkit-accessibility true || true
NUBIAN_CURSOR_SIZE="${NUBIAN_CURSOR_SIZE:-32}" /home/user/.local/share/nubian-universal-computer-agent/ubuntu_desktop_tweaks.sh || true
systemctl --user daemon-reload
systemctl --user enable nubian-universal-computer-agent.service
systemctl --user enable nubian-x11-window-tracker.service
systemctl --user enable nubian-xinput-tracker.service
systemctl --user enable nubian-atspi-tracker.service
systemctl --user enable nubian-event-indexer.service
systemctl --user enable nubian-screen-recorder.service
systemctl --user restart nubian-universal-computer-agent.service
systemctl --user restart nubian-x11-window-tracker.service
systemctl --user restart nubian-xinput-tracker.service
systemctl --user restart nubian-atspi-tracker.service
systemctl --user restart nubian-event-indexer.service
systemctl --user restart nubian-screen-recorder.service
sleep 2
systemctl --user --no-pager --full status nubian-universal-computer-agent.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-x11-window-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-xinput-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-atspi-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-event-indexer.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-screen-recorder.service | sed -n '1,80p'
curl -fsS http://127.0.0.1:6090/health
