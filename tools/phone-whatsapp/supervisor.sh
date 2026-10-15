#!/data/data/com.termux/files/usr/bin/sh
# One local supervisor, no message is sent during startup or reconnection.
set -u
cd "$HOME/.raad-whatsapp" || exit 1
termux-wake-lock >/dev/null 2>&1 || true
while :; do
  node index.mjs
  code=$?
  # An intentional stop exits cleanly and must not restart.
  [ "$code" -eq 0 ] && exit 0
  sleep 15
done
