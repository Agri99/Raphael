#!/usr/bin/env bash
# Tailscale Serve launch script for K.A.R.E.N. Gateway
# Revision 3.4 (Lean Production Readiness)

set -euo pipefail

echo "Starting K.A.R.E.N. Gateway in background..."
python -m uvicorn friday_mobile_gateway.app:app --host 127.0.0.1 --port 8765 &
gateway_pid=$!

sleep 2

echo "Registering background loopback proxy with Tailscale Serve..."
tailscale serve --bg http://127.0.0.1:8765

echo "Gateway started with PID $gateway_pid and registered with Tailscale Serve"
