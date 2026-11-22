#!/bin/sh
#プロジェクト全体のビルド入口。displayとhdmi-cecをビルドする。
set -e
cd "$(dirname "$0")"

./display/build.sh "$@"
./hdmi-cec/build.sh "$@"
