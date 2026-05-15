#!/usr/bin/env bash
# Inject a /agent/ → guest:6090 reverse-proxy block into the OSWorld
# container's nginx, so the Nubian controller in the guest VM is reachable
# from outside.
#
# Usage:
#   bash patch-nginx.sh <gcp-vm-name> [container-name]
#   bash patch-nginx.sh nubian-sandbox-spot nubian-sandbox
#
# For non-GCP hosts, run the inner sh block directly with `docker exec`.

set -euo pipefail

VM="${1:?need GCP VM name (or run docker exec directly for other hosts)}"
CONTAINER="${2:-nubian-sandbox}"

gcloud compute ssh "$VM" --command="sudo docker exec -i $CONTAINER sh -s" <<'EOS'
set -e
cp /etc/nginx/sites-enabled/web.conf /tmp/web.before-nubian
awk '
  /location = \/agent/ {skip=1; next}
  /location \/agent\// {skip=1; next}
  skip && /^    }/ {skip=0; next}
  !skip {print}
' /etc/nginx/sites-enabled/web.conf > /tmp/web.clean
head -n -1 /tmp/web.clean > /tmp/web.new
cat >> /tmp/web.new << 'NEOF'

    location = /agent { return 301 /agent/; }

    location /agent/ {
      proxy_http_version 1.1;
      proxy_buffering off;
      proxy_read_timeout 3600s;
      proxy_send_timeout 3600s;
      proxy_pass http://20.20.20.21:6090/;
    }
}
NEOF
mv /tmp/web.new /etc/nginx/sites-enabled/web.conf
nginx -t && nginx -s reload && echo "nginx reloaded"
EOS
