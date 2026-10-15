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
# Termux:Boot starts the same supervisor after reboot when its official add-on is enabled.
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/raad-whatsapp" <<'RAAD_BOOT'
#!/data/data/com.termux/files/usr/bin/sh
cd "$HOME/.raad-whatsapp" || exit 1
sh supervisor.sh >> "$HOME/.raad-whatsapp/boot.log" 2>&1
RAAD_BOOT
chmod 700 "$HOME/.termux/boot/raad-whatsapp"
exec sh supervisor.sh
