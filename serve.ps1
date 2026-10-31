# Tailscale Serve PowerShell Launcher for K.A.R.E.N. Gateway
# Revision 3.4 (Lean Production Readiness)

$ErrorActionPreference = "Stop"

Write-Output "Verifying Tailscale Serve status..."
& tailscale serve status

Write-Output "Registering background loopback proxy for K.A.R.E.N. Gateway on port 8765..."
& tailscale serve --bg http://127.0.0.1:8765

Write-Output "Active Tailscale Serve configuration:"
& tailscale serve status

