#!/bin/sh
#デバッグ実行用。Westonを親コンポジタとして起動し、その中で
#windowバックエンドでアプリを表示する。
cd "$(dirname "$0")/build"

if ! command -v weston >/dev/null 2>&1; then
	echo "debug.sh: westonが見つかりません（sudo apt install weston）" >&2
	cd ../
	exit 1
fi

if [ -z "$DISPLAY" ]; then
	echo "debug.sh: DISPLAYが設定されていません。X11のデスクトップ上で実行してね" >&2
	cd ../
	exit 1
fi

export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}

#レンダラはpixman（ソフトウェア描画）にする。GLレンダラだと
#X11バックエンドの初期化に失敗することがあるため。
weston --backend=x11-backend.so --socket=wayland-rtv --idle-time=0 --use-pixman &
WESTON_PID=$!
trap 'kill $WESTON_PID 2>/dev/null' EXIT

#Westonのソケットができるまで待つ（最大5秒）
i=0
while [ ! -S "$XDG_RUNTIME_DIR/wayland-rtv" ] && [ $i -lt 50 ]; do
	sleep 0.1
	i=$((i + 1))
done

if [ ! -S "$XDG_RUNTIME_DIR/wayland-rtv" ]; then
	echo "debug.sh: westonが起動しませんでした（上のwestonのログを確認してね）" >&2
	cd ../
	exit 1
fi

WAYLAND_DISPLAY=wayland-rtv java -jar raspberrypi_tv-1.0-full.jar window
cd ../
