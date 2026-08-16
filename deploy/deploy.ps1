#!/usr/bin/env pwsh
<# ─────────────────────────────────────────────────────────────────────────────
 Quick Voice — Deploy to a remote VPS via SSH.

 Usage:
   .\deploy.ps1 -VpsIp 123.45.67.89 -Subdomain mysignal `
                -DuckdnsToken abc123 -TurnPass "s3cret"
#>
param(
    [Parameter(Mandatory=$true)]  [string]$VpsIp,
    [Parameter(Mandatory=$true)]  [string]$Subdomain,
    [Parameter(Mandatory=$true)]  [string]$DuckdnsToken,
    [Parameter(Mandatory=$true)]  [string]$TurnPass,
    [string]$VpsUser = "ubuntu",
    [string]$SshKey  = "",
    [string]$Remote  = "/opt/quickvoice"
)

$ErrorActionPreference = "Stop"
$Here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root  = Split-Path -Parent $Here
$Host_ = "${VpsUser}@${VpsIp}"

function Info($m) { Write-Host "▸ $m" -ForegroundColor Green }
function Fail($m) { Write-Host "✖ $m" -ForegroundColor Red; exit 1 }

$ssh = @("-o","StrictHostKeyChecking=no","-o","ServerAliveInterval=30","-o","ConnectTimeout=20")
$scp = @("-o","StrictHostKeyChecking=no")
if ($SshKey) { $ssh += @("-i",$SshKey); $scp += @("-i",$SshKey) }

# ── Test SSH ────────────────────────────────────────────────────────────────
Info "Testing SSH to $Host_ ..."
& ssh @ssh $Host_ echo ok 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Cannot reach $Host_ via SSH." }
Info "SSH OK."

# ── Upload ──────────────────────────────────────────────────────────────────
Info "Uploading deploy/ + server/ ..."
& scp @scp -r "$Here" "$Root\server" "${Host_}:${Remote}/" 2>$null
Info "Upload complete."

# ── Create remote deploy script ─────────────────────────────────────────────
Info "Running setup on remote server..."
$remoteScript = @"
#!/usr/bin/env bash
set -euo pipefail
export QV_SUBDOMAIN='${Subdomain}'
export QV_DUCKDNS_TOKEN='${DuckdnsToken}'
export QV_TURN_PASSWORD='${TurnPass}'
cd ${Remote}/deploy
sudo -E bash setup.sh
"@
$remoteScript | & ssh @ssh $Host_ "cat > /tmp/qv-deploy.sh && chmod +x /tmp/qv-deploy.sh && bash /tmp/qv-deploy.sh"

# ── Verify ──────────────────────────────────────────────────────────────────
Info "Verifying..."
Start-Sleep -Seconds 5
$resp = & ssh @ssh $Host_ "curl -fsS http://localhost:8080/api/version" 2>$null
if ($resp -match "versionName") {
    Info "Signaling server OK: $resp"
} else {
    Write-Host "⚠  May still be building. Check: ssh $Host_ 'cd ${Remote}/deploy && docker compose logs -f'" -ForegroundColor Yellow
    exit 0
}

# ── Summary ─────────────────────────────────────────────────────────────────
$Domain = "$Subdomain.duckdns.org"
Write-Host ""
Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host " Quick Voice Server — Deployment Complete" -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Web:      https://${Domain}"
Write-Host "  Signaling: wss://${Domain}/signaling"
Write-Host "  TURN:     turn://${Domain}:3478  |  quickvoice / ${TurnPass}"
Write-Host "  Update:   https://${Domain}/api/version"
Write-Host ""
Write-Host "  Phone config:"
Write-Host "    Settings → Wi-Fi calls → Connect"
Write-Host "    Signaling: wss://${Domain}/signaling"
Write-Host "    TURN: turn://${Domain}:3478 | quickvoice | ${TurnPass}"
Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
