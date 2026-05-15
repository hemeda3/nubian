# GCP Spot VM Sandbox

Run `happysixd/osworld-docker` on a GCP spot VM with nested KVM.

## Prereqs

- `gcloud` installed + `gcloud auth login`
- A GCP project with billing on, Compute Engine API enabled

## Up

```bash
PROJECT=<your-gcp-project> SOURCE_IP=0.0.0.0/0 \
  ./deployment/gcloud-spot-sandbox.sh up
```

Returns:
```
External IP: <IP>
  noVNC:  http://<IP>:8006
  API:    http://<IP>:5000
```

Then wait ~10–15 min for `Ubuntu.qcow2` (~10 GB) download + container boot.

## Watch boot

```bash
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh logs
```

When you see `[startup] done`, open `http://<IP>:8006`.

## Other commands

```bash
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh ip
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh ssh
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh stop
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh start
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh down
```

`stop` keeps the disk; `down` destroys VM + firewall.

## Wire the agent

```properties
# config/application-dev.properties
NUBIAN_SANDBOX_BASE_URL=http://<IP>:5000
```
