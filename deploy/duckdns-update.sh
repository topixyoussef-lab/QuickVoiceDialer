#!/usr/bin/env bash
# Keeps your DuckDNS subdomain pointed at this server's public IP.
#
# Setup:
#   1) Create a free subdomain at https://duckdns.org and copy its token.
#   2) Set DOMAIN and TOKEN below.
#   3) Run once:            sudo bash duckdns-update.sh
#   4) Keep it in sync automatically (runs every 5 min):
#        (crontab -l 2>/dev/null; echo "*/5 * * * * /opt/quickvoice/deploy/duckdns-update.sh >/dev/null 2>&1") | crontab -
#
# The empty `ip=` parameter tells DuckDNS to auto-detect the request IP.
set -euo pipefail

DOMAIN="REPLACE_YOUR_SUBDOMAIN.duckdns.org"   # e.g. quickvoice123.duckdns.org
TOKEN="REPLACE_YOUR_TOKEN"                     # your DuckDNS token

SUBDOMAIN="${DOMAIN%.duckdns.org}"

RESP="$(curl -fsS "https://www.duckdns.org/update?domains=${SUBDOMAIN}&token=${TOKEN}&ip=")"
[ "$RESP" = "OK" ] || { echo "DuckDNS update failed: $RESP" >&2; exit 1; }
echo "DuckDNS updated: $DOMAIN"
