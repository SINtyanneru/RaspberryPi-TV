# display（rtv-display）

ラズパイTVの画面描画コンポーネント。C で書いた最小の Wayland コンポジタ。

- 実装しているプロトコル: wl_compositor / wl_shm / wl_output / xdg-shell
- クライアントのサーフェスを画面中央に合成するキオスク型
- 標準出力は Java 本体とのバイナリプロトコル用に予約。ログは標準エラーへ出す

## バックエンド（`--backend` で選択）

- `headless`（既定）: メモリ上フレームバッファ＋PPM ダンプ。開発・検証用
- `drm`: DRM/KMS。dumb buffer へ描画してページフリップで表示する実機用。
  libdrm がある環境でのみ有効（無ければ起動時にエラー）。
  `--device` でデバイス指定（既定 `/dev/dri/card0`）、`--width`/`--height`
  に合うモードがあればそれを使う。DRM マスターが必要なので、他の
  コンポジタが動いていないコンソールから起動すること
- `window`: デバッグ用。親コンポジタのクライアントになり、自分の画面を
  ウィンドウ（最大 1280×720 へ縮小）で表示する。`WAYLAND_DISPLAY` の
  親へ接続する

## ビルドと動作確認

プロジェクトルートの `./build.sh`（中身は `display/build.sh` を呼ぶだけ）で
ビルドする。make は使わない。

```
./build.sh                        # ルートから
./display/test/run-headless.sh    # ヘッドレスで合成確認→frame.ppm 生成
./display/test/run-nested.sh      # 親コンポジタ上にウィンドウ表示でネスト起動する確認
./display/build.sh clean          # 生成物を消す
```

## 構成

- `src/main.c` — コンポジタ本体
- `src/backend.h` — バックエンド抽象
- `src/backend_headless.c` / `src/backend_drm.c` / `src/backend_window.c`
- `protocol/xdg-shell.xml` — wayland-protocols 1.32 の xdg-shell
- `test/test-client.c` — 動作確認用クライアント
