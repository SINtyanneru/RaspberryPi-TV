#!/bin/sh
#プロジェクト全体のビルド入口。今はdisplayだけをビルドする。
set -e
cd "$(dirname "$0")"

./display/build.sh "$@"
