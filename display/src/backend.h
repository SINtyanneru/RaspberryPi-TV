#ifndef RTV_BACKEND_H
#define RTV_BACKEND_H

#include <stdint.h>

struct wl_display;

//描画バックエンドの抽象。pixelsはXRGB8888のフレームバッファで、
//コンポジタがここへ描いてからpresent()を呼ぶ。
struct backend {
	int width;
	int height;
	uint32_t *pixels;
	void (*present)(struct backend *backend);
	void (*destroy)(struct backend *backend);
	void *data;
};

//メモリ上フレームバッファ＋PPMダンプ（開発・検証用）
struct backend *backend_headless_create(int width, int height, const char *dump_path);

//DRM/KMS（実機用。要求サイズに合うモードがあればそれを使う）
struct backend *backend_drm_create(const char *device, int request_width, int request_height);

//親コンポジタ上のウィンドウ表示（デバッグ用）
struct backend *backend_window_create(struct wl_display *server_display, int width, int height);

#endif
