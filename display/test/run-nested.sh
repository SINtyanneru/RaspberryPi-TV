#!/bin/sh
#ネスト構成の確認: 親コンポジタ（ヘッドレス）の上に子コンポジタを
#ウィンドウバックエンドで起動し、さらにその子へテストクライアントを
#接続する。どちらも標準入力の初期化コマンド(0x00)で画面を出す。
#親のframe.ppmに子の画面が合成されれば成功。
set -e
cd "$(dirname "$0")/.."

RUN_DIR=$(mktemp -d)
export XDG_RUNTIME_DIR="$RUN_DIR"
PARENT_FIFO="$RUN_DIR/parent.stdin"
CHILD_FIFO="$RUN_DIR/child.stdin"
mkfifo "$PARENT_FIFO" "$CHILD_FIFO"
trap 'kill $CHILD_PID $PARENT_PID 2>/dev/null; rm -rf "$RUN_DIR"' EXIT
rm -f frame.ppm parent-response.bin child-response.bin

../build/rtv-display --socket wayland-parent --dump frame.ppm < "$PARENT_FIFO" > parent-response.bin &
PARENT_PID=$!
exec 3>"$PARENT_FIFO"
printf '\000\000' >&3
sleep 1

WAYLAND_DISPLAY=wayland-parent ../build/rtv-display --backend window --socket wayland-child < "$CHILD_FIFO" > child-response.bin &
CHILD_PID=$!
exec 4>"$CHILD_FIFO"
printf '\000\000' >&4
sleep 1

for f in parent-response.bin child-response.bin; do
	STATUS=$(od -An -tu1 "$f" | awk '{print $1}')
	if [ "$STATUS" != "32" ]; then
		echo "FAIL: $f status=$STATUS (expected 32 = 0x20)"
		exit 1
	fi
done
echo "init: both status 0x20 OK"

WAYLAND_DISPLAY=wayland-child ../build/rtv-test-client
sleep 1

ls -l frame.ppm
echo "OK: nested frame dumped"
