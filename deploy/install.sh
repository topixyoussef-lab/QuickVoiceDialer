#!/usr/bin/env bash
# Quick Voice — non-Docker install for a plain Linux VPS (Ubuntu/Debian tested).
# Run as root:  sudo bash install.sh
set -euo pipefail

APP_DIR=/opt/quickvoice
USER=quickvoice

apt-get update
apt-get install -y nodejs npm ca-certificates

id -u $USER &>/dev/null || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin $USER

mkdir -p "$APP_DIR"
cp -r ../server "$APP_DIR/server"
mkdir -p "$APP_DIR/server/releases"
cp quickvoice-signaling.service /etc/systemd/system/quickvoice-signaling.service

cd "$APP_DIR/server"
npm install --omit=dev || npm install

chown -R $USER:$USER "$APP_DIR"

systemctl daemon-reload
systemctl enable --now quickvoice-signaling
systemctl status quickvoice-signaling --no-pager

echo
echo "Signaling server is running on ws://<server-ip>:8080/signaling"
echo "Publish an APK:  cp QuickVoiceDialer.apk $APP_DIR/server/releases/"
echo "Then update      $APP_DIR/server/releases/version.json  (versionName/versionCode/apkUrl)"
echo "For HTTPS/wss use nginx or caddy (see Caddyfile)."
