# scripts/sync_token.sh
#!/usr/bin/env bash
# Synchronise the bearer token from shared/secret.env into each service's config.toml
# Usage: ./scripts/sync_token.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SHARED="$ROOT/shared/secret.env"
TOKEN=$(grep '^BEARER_TOKEN=' "$SHARED" | cut -d'=' -f2-)
if [[ -z "$TOKEN" ]]; then
  echo "Error: BEARER_TOKEN not set in $SHARED"
  exit 1
fi

# Update Raphael gateway
RAP_PATH="$ROOT/raphael_project/src/raphael_gateway/config.toml"
awk -v t="$TOKEN" '/^bearer_token/{$3="\""t"\""}1' "$RAP_PATH" > "$RAP_PATH.tmp" && mv "$RAP_PATH.tmp" "$RAP_PATH"

# Update Alexandria
ALEX_PATH="$ROOT/alexandria_project/config.toml"
awk -v t="$TOKEN" '/^bearer_token/{$3="\""t"\""}1' "$ALEX_PATH" > "$ALEX_PATH.tmp" && mv "$ALEX_PATH.tmp" "$ALEX_PATH"

# Update F.R.I.D.A.Y._v2
FRIDAY_PATH="$ROOT/friday_v2/config.toml"
awk -v t="$TOKEN" '/^bearer_token/{$3="\""t"\""}1' "$FRIDAY_PATH" > "$FRIDAY_PATH.tmp" && mv "$FRIDAY_PATH.tmp" "$FRIDAY_PATH"

echo "Token synced to all services."
