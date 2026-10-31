# scripts/wifi_mobile_switch_test.sh
#!/usr/bin/env bash
# Record the outcome of a long-running generation while the phone's network
# switches between Wi‑Fi and mobile data.
# This script only records the result for manual verification; it does not
# toggle Android network interfaces (that must be done on the phone).
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8765}"
TOKEN="${2:-REPLACE_WITH_SECURE_TOKEN}"
OUT="${3:-docs/wifi_mobile_switch_result.txt}"

mkdir -p "$(dirname "$OUT")"

echo "Starting long generation at $(date -Iseconds)" > "$OUT"
curl -s -N -X POST "$BASE_URL/v1/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"request_id":"'"$(uuidgen || echo "$RANDOM-$RANDOM")"'","conversation_id":"'"$RANDOM"'","mode":"fast","messages":[{"role":"user","content":"Write a 500-word essay about distributed systems."}]}' \
  | tee -a "$OUT"

echo "Generation ended at $(date -Iseconds)" >> "$OUT"
echo "Result saved to $OUT"
