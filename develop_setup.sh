#!/bin/sh
#開発環境のセットアップ。ビルドとデバッグに必要なパッケージを入れる。
#Debian（trixie想定）用。Raspberry Piでもそのまま使える。
set -e

sudo apt-get update
sudo apt-get install -y \
	clang gcc pkg-config \
	libwayland-dev libwayland-bin \
	libdrm-dev \
	libfreetype-dev libfontconfig-dev \
	libcec-dev libp8-platform-dev libudev-dev \
	openjdk-25-jdk-headless maven \
	weston \
	curl unzip fontconfig

#libp8-platform-devとlibudev-devは、libcec.pcのRequiresを
#pkg-configで解決するために必要（無くてもbuild.sh側で回避はできる）。

#UDEV Gothic（テキスト描画用フォント。aptには無いのでGitHubから入れる）
FONT_DIR="$HOME/.local/share/fonts"
if [ ! -f "$FONT_DIR/UDEVGothic-Regular.ttf" ]; then
	TMP_DIR=$(mktemp -d)
	curl -L -o "$TMP_DIR/udev.zip" https://github.com/yuru7/udev-gothic/releases/download/v2.2.0/UDEVGothic_v2.2.0.zip
	unzip -o -j "$TMP_DIR/udev.zip" \
		"UDEVGothic_v2.2.0/UDEVGothic-Regular.ttf" \
		"UDEVGothic_v2.2.0/UDEVGothic-Bold.ttf" \
		-d "$TMP_DIR"
	mkdir -p "$FONT_DIR"
	cp "$TMP_DIR/UDEVGothic-Regular.ttf" "$TMP_DIR/UDEVGothic-Bold.ttf" "$FONT_DIR/"
	fc-cache -f "$FONT_DIR"
	rm -rf "$TMP_DIR"
	echo "installed: UDEV Gothic"
fi

echo "setup done"
