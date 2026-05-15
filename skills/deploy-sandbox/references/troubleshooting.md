# Common install failures

## `RemoteDisconnected: Remote end closed connection`
OSWorld `/execute` has a default response timeout. `apt-get` + `pip install` on first run can exceed it. The install usually finishes anyway — re-check with:

```bash
curl -X POST http://<host>:5000/execute \
  -H 'Content-Type: application/json' \
  -d '{"shell":true,"command":"systemctl --user is-active nubian-universal-computer-agent.service"}'
```

If it says `active`, you're done.

## Health endpoint says `inactive` after install
Two trackers (`window_tracker_x11.py`, `xinput_tracker.py`) sometimes get truncated by the loop's argument handling. Re-upload only the missing files:

```bash
for f in window_tracker_x11.py xinput_tracker.py; do
  curl -fsS -X POST http://127.0.0.1:5000/setup/upload \
    -F "file_path=/home/user/.local/share/nubian-universal-computer-agent/$f" \
    -F "file_data=@$f"
done
```

Then restart:
```bash
systemctl --user daemon-reload
systemctl --user restart nubian-universal-computer-agent.service
```

## `paddleocr` install fails
The installer pins `numpy==1.26.4` + `paddlepaddle==3.2.2`. If your guest python is older than 3.10 or paddle's wheel is missing for your arch, the import will fail but the controller still starts (OCR is optional). Health returns `paddleocr: false` + `paddleocr_error`.

## `/eyes/screenshot` returns black frame
The X11 session isn't yet rendered. After install run:
```bash
ubuntu_desktop_tweaks.sh
```
inside the guest to apply cursor size + accessibility-toolkit settings.
