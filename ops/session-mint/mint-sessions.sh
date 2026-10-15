#!/usr/bin/env bash
# mint-sessions.sh — Wrapper script for scheduled session minting (FR-37, §6.8, §9.4)
set -euo pipefail

TARGET_AREA="${1:-all}"
SESSION_DIR="${SESSION_BASE_DIR:-/etc/testgen/sessions}"

echo "Starting session minting for target area: ${TARGET_AREA}"
mkdir -p "${SESSION_DIR}"
chmod 0700 "${SESSION_DIR}"

python3 ops/session-mint/mint_sessions.py --area="${TARGET_AREA}" --output-dir="${SESSION_DIR}"

# Ensure permissions on all minted session files are 0600
find "${SESSION_DIR}" -name "*.json" -exec chmod 0600 {} +

echo "Session minting completed for ${TARGET_AREA} at $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
