#!/bin/sh
#プロジェクト全体のビルド入口。成果物はbuild/へ集める。
set -e
cd "$(dirname "$0")"

if [ "$1" = "clean" ]; then
	./display/build.sh clean
	./hdmi-cec/build.sh clean
	if command -v mvn >/dev/null 2>&1; then
		(cd core && mvn -q clean)
	fi
	rm -rf build
	exit 0
fi

mkdir -p build
./display/build.sh
./hdmi-cec/build.sh

#core（Java）はMavenでパッケージし、実行可能jarをbuild/へコピーする
if command -v mvn >/dev/null 2>&1; then
	(cd core && mvn -q package)
	cp core/target/raspberrypi_tv-1.0-full.jar build/
	echo "built: raspberrypi_tv-1.0-full.jar"
else
	echo "core: mvn not found; skipping raspberrypi_tv jar"
fi

#アプリをビルド
./app/build.sh