#!/bin/sh
#hdmi-cecコンポーネントのビルド。libcecが無い環境ではスキップする。
set -e
cd "$(dirname "$0")"
OUT=../build

if [ "$1" = "clean" ]; then
	rm -f rtv-cec "$OUT/rtv-cec"
	exit 0
fi

if pkg-config --exists libcec 2>/dev/null; then
	CEC_CFLAGS=$(pkg-config --cflags libcec)
	CEC_LIBS=$(pkg-config --libs libcec)
elif printf '#include <libcec/cec.h>\n' | clang++ -E -x c++ - >/dev/null 2>&1; then
	#pkg-configのRequires（p8-platform、libudev）が揃っていないと、
	#libcec.pcがあっても--existsが失敗する（Debianのlibcec-devは
	#それらに依存していない）。ヘッダが標準位置にあるなら直接リンクで足りる。
	CEC_CFLAGS=""
	CEC_LIBS="-lcec"
else
	echo "hdmi-cec: libcec not found; skipping rtv-cec (install libcec-dev to build it)"
	exit 0
fi

mkdir -p "$OUT"
OBJC_INCLUDE=$(gcc -print-file-name=include)

#Objective-C++としてコンパイルする。ObjCランタイムはGCCランタイムを使う。
clang++ -std=c++17 -Wall -Wextra -Wno-unused-parameter -O2 -g \
	-I"$OBJC_INCLUDE" $CEC_CFLAGS \
	-o "$OUT/rtv-cec" src/cec_client.mm \
	$CEC_LIBS -lobjc -lpthread

echo "built: rtv-cec"
