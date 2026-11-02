#!/bin/bash
# Runs inside the proot guest. The Android service passes BACKEND_PORT and
# FRONTEND_PORT; everything else about the guest environment is fixed here.
set -u

export HOME=/home/deeptutor
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export PATH=/opt/python/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
export PYTHONPATH=/opt/deeptutor/core
export PYTHONUNBUFFERED=1 PYTHONUTF8=1 PYTHONIOENCODING=utf-8
export DEEPTUTOR_HOME=/home/deeptutor/workspace
export TIKTOKEN_CACHE_DIR=/opt/deeptutor/tiktoken
export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
# Android's seccomp policy rejects io_uring; skip libuv's probe entirely.
export UV_USE_IO_URING=0
export NEXT_TELEMETRY_DISABLED=1
export HF_HUB_OFFLINE=1 TOKENIZERS_PARALLELISM=false

BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
export DEEPTUTOR_API_BASE_URL="$BACKEND_URL"
export NEXT_PUBLIC_API_BASE="$BACKEND_URL" NEXT_PUBLIC_API_BASE_EXTERNAL="$BACKEND_URL"
export AUTH_ENABLED=false NEXT_PUBLIC_AUTH_ENABLED=false DEEPTUTOR_AUTH_ENABLED=false
export BACKEND_PORT FRONTEND_PORT

mkdir -p "$DEEPTUTOR_HOME" /tmp
cd "$DEEPTUTOR_HOME" || exit 1

echo "[deeptutor] initializing workspace"
python3 -m deeptutor_cli.main init --non-interactive --home "$DEEPTUTOR_HOME" || {
  echo "[deeptutor] init failed"; exit 1;
}

echo "[deeptutor] starting backend on $BACKEND_URL"
python3 -m deeptutor_cli.main serve --host 127.0.0.1 --port "$BACKEND_PORT" &
BACKEND=$!

echo "[deeptutor] starting web on http://127.0.0.1:${FRONTEND_PORT}"
(cd /opt/deeptutor/web && PORT="$FRONTEND_PORT" HOSTNAME=127.0.0.1 exec node server.js) &
FRONTEND=$!

stop() { kill "$BACKEND" "$FRONTEND" 2>/dev/null; }
trap 'stop; exit 0' TERM INT HUP

# Either service exiting ends the session; the Android side reports it.
wait -n "$BACKEND" "$FRONTEND"
STATUS=$?
echo "[deeptutor] a service exited with status $STATUS"
stop
wait
exit "$STATUS"
