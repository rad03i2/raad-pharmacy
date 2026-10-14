#!/data/data/com.termux/files/usr/bin/sh
set -eu
umask 077
cd "$(dirname "$0")"
raad_bridge_token=${1:-}
case "$raad_bridge_token" in *[!a-f0-9]*|'') exit 1 ;; esac
[ "${#raad_bridge_token}" -eq 64 ] || exit 1
node -e 'if (Number(process.versions.node.split(".")[0]) < 20) process.exit(1)'
node stop-previous.mjs
printf '{"token":"%s"}\n' "$raad_bridge_token" > config.json
npm ci --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund
node verify-runtime.mjs
termux-wake-lock
node index.mjs
