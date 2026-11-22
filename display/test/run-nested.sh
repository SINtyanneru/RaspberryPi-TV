#!/bin/sh
#ネスト構成の確認: 親コンポジタ（ヘッドレス）の上に子コンポジタを
#ウィンドウバックエンドで起動し、さらにその子へテストクライアントを
#接続する。親のframe.ppmに子の画面が合成されれば成功。
set -e
cd "$(dirname "$0")/.."

RUN_DIR=$(mktemp -d)
export XDG_RUNTIME_DIR="$RUN_DIR"
trap 'kill $CHILD_PID $PARENT_PID 2>/dev/null; rm -rf "$RUN_DIR"' EXIT

./rtv-display --socket wayland-parent --dump frame.ppm &
PARENT_PID=$!
sleep 1

WAYLAND_DISPLAY=wayland-parent ./rtv-display --backend window --socket wayland-child &
CHILD_PID=$!
sleep 1

WAYLAND_DISPLAY=wayland-child ./rtv-test-client
sleep 1

ls -l frame.ppm
echo "OK: nested frame dumped"
