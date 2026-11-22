#!/bin/sh
#コンポジタをヘッドレスで起動し、標準入力から初期化コマンド(0x00)を
#送ってからテストクライアントを接続する。
#初期化前は画面が出ず、初期化の応答が0x20で、frame.ppmが生成されることを確認する。
set -e
cd "$(dirname "$0")/.."

RUN_DIR=$(mktemp -d)
export XDG_RUNTIME_DIR="$RUN_DIR"
FIFO="$RUN_DIR/display.stdin"
mkfifo "$FIFO"
trap 'kill $DISPLAY_PID 2>/dev/null; rm -rf "$RUN_DIR"' EXIT
rm -f frame.ppm response.bin

../build/rtv-display --socket wayland-rtv --dump frame.ppm < "$FIFO" > response.bin &
DISPLAY_PID=$!
exec 3>"$FIFO"
sleep 1

if [ -e frame.ppm ]; then
	echo "FAIL: frame.ppm exists before init"
	exit 1
fi
printf '\000\000' >&3
sleep 1
STATUS=$(od -An -tu1 response.bin | awk '{print $1}')
if [ "$STATUS" != "32" ]; then
	echo "FAIL: init status=$STATUS (expected 32 = 0x20)"
	exit 1
fi
echo "init: status 0x20 OK, frame appeared after init"

WAYLAND_DISPLAY=wayland-rtv ../build/rtv-test-client
sleep 1

ls -l frame.ppm
echo "OK: frame.ppm generated"
