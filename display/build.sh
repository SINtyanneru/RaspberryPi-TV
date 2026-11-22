#!/bin/sh
#rtv-displayとテストクライアントをビルドする。
#使い方: ./build.sh [clean]
set -e
cd "$(dirname "$0")"

CC=${CC:-clang}
CFLAGS="-std=c11 -Wall -Wextra -Wno-unused-parameter -O2 -g"
GEN=gen
OUT=../build

if [ "$1" = "clean" ]; then
	rm -rf "$GEN" rtv-display rtv-test-client "$OUT/rtv-display" "$OUT/rtv-test-client" frame.ppm frame.png child-fb.ppm
	echo "cleaned"
	exit 0
fi

mkdir -p "$GEN" "$OUT"
wayland-scanner server-header protocol/xdg-shell.xml "$GEN/xdg-shell-protocol.h"
wayland-scanner private-code protocol/xdg-shell.xml "$GEN/xdg-shell-protocol.c"
wayland-scanner client-header protocol/xdg-shell.xml "$GEN/xdg-shell-client-protocol.h"
wayland-scanner private-code protocol/xdg-shell.xml "$GEN/xdg-shell-client-protocol.c"

#libdrmがあればDRMバックエンドを有効にする
DRM_DEFINE=""
DRM_CFLAGS=""
DRM_LIBS=""
if pkg-config --exists libdrm 2>/dev/null; then
	DRM_DEFINE="-DHAVE_DRM"
	DRM_CFLAGS=$(pkg-config --cflags libdrm)
	DRM_LIBS=$(pkg-config --libs libdrm)
fi

#FreeTypeがあればテキストオブジェクトを有効にする
TEXT_DEFINE=""
TEXT_CFLAGS=""
TEXT_LIBS=""
if pkg-config --exists freetype2 2>/dev/null; then
	TEXT_DEFINE="-DHAVE_TEXT"
	TEXT_CFLAGS=$(pkg-config --cflags freetype2)
	TEXT_LIBS=$(pkg-config --libs freetype2)
	if pkg-config --exists fontconfig 2>/dev/null; then
		TEXT_DEFINE="$TEXT_DEFINE -DHAVE_FONTCONFIG"
		TEXT_CFLAGS="$TEXT_CFLAGS $(pkg-config --cflags fontconfig)"
		TEXT_LIBS="$TEXT_LIBS $(pkg-config --libs fontconfig)"
	fi
else
	echo "note: freetype2 not found; text objects are disabled" >&2
fi

SERVER_CFLAGS=$(pkg-config --cflags wayland-server wayland-client)
SERVER_LIBS=$(pkg-config --libs wayland-server wayland-client)
CLIENT_CFLAGS=$(pkg-config --cflags wayland-client)
CLIENT_LIBS=$(pkg-config --libs wayland-client)

$CC $CFLAGS $DRM_DEFINE $TEXT_DEFINE -I"$GEN" -Isrc $SERVER_CFLAGS $DRM_CFLAGS $TEXT_CFLAGS \
	-o "$OUT/rtv-display" \
	src/main.c src/backend_headless.c src/backend_drm.c src/backend_window.c \
	"$GEN/xdg-shell-protocol.c" \
	$SERVER_LIBS $DRM_LIBS $TEXT_LIBS -lm

$CC $CFLAGS -I"$GEN" $CLIENT_CFLAGS \
	-o "$OUT/rtv-test-client" \
	test/test-client.c "$GEN/xdg-shell-client-protocol.c" \
	$CLIENT_LIBS

echo "built: rtv-display rtv-test-client"
