#!/bin/sh
#コンポジタをヘッドレスで起動し、テストクライアントを接続して
#フレームダンプ（frame.ppm）が生成されるところまで確認する。
set -e
cd "$(dirname "$0")/.."

RUN_DIR=$(mktemp -d)
export XDG_RUNTIME_DIR="$RUN_DIR"
trap 'kill $DISPLAY_PID 2>/dev/null; rm -rf "$RUN_DIR"' EXIT

./rtv-display --socket wayland-rtv --dump frame.ppm &
DISPLAY_PID=$!
sleep 1

WAYLAND_DISPLAY=wayland-rtv ./rtv-test-client
sleep 1

ls -l frame.ppm
echo "OK: frame.ppm generated"
