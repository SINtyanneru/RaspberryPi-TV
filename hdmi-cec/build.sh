#!/bin/sh
#hdmi-cecコンポーネントのビルド。libcecが無い環境ではスキップする。
set -e
cd "$(dirname "$0")"

if [ "$1" = "clean" ]; then
	rm -f rtv-cec
	exit 0
fi

if ! pkg-config --exists libcec 2>/dev/null; then
	echo "hdmi-cec: libcec not found; skipping rtv-cec (install libcec-dev to build it)"
	exit 0
fi

CEC_CFLAGS=$(pkg-config --cflags libcec)
CEC_LIBS=$(pkg-config --libs libcec)
OBJC_INCLUDE=$(gcc -print-file-name=include)

#Objective-C++としてコンパイルする。ObjCランタイムはGCCランタイムを使う。
clang++ -std=c++17 -Wall -Wextra -Wno-unused-parameter -O2 -g \
	-I"$OBJC_INCLUDE" $CEC_CFLAGS \
	-o rtv-cec src/cec_client.mm \
	$CEC_LIBS -lobjc -lpthread

echo "built: rtv-cec"
