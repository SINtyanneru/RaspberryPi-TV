#ifndef RTV_BACKEND_H
#define RTV_BACKEND_H

#include <stdint.h>

struct wl_display;

enum backend_kind {
	BACKEND_HEADLESS,
	BACKEND_DRM,
	BACKEND_WINDOW,
};

//描画バックエンドの抽象。pixelsはXRGB8888のフレームバッファで、
//コンポジタがここへ描いてからpresent()を呼ぶ。
struct backend {
	enum backend_kind kind;
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

//--- ハードウェアプレーン ---------------------------------------------------
//映像などを表示コントローラ側で合成・リサイズするための仕組み。
//対応するバックエンド（DRM）以外ではcreateがNULLを返し、
//呼び出し側はソフトウェア描画へフォールバックする。
//バッファはXBGR8888（メモリ上はB,G,R,Xの並び）でダブルバッファ。
struct backend_plane;

//幅・高さは映像（ソース）のサイズ。pixels_outに2面の先頭アドレス、
//stride_outに1行のバイト数が入る。
struct backend_plane *backend_create_plane(struct backend *backend, uint32_t width, uint32_t height, uint8_t *pixels_out[2], uint32_t *stride_out);
//表示位置とサイズを設定する。
int backend_plane_set_geometry(struct backend *backend, struct backend_plane *plane, int x, int y, int width, int height);
//プレーンの重なり順を設定する。layer 0がプライマリの直下、
//数字が大きいほど下になる。範囲内に入らなければ-1を返す。
int backend_plane_set_layer(struct backend *backend, struct backend_plane *plane, int layer);
//pixels_outのどちらかを表示する（フリップ）。
int backend_plane_flip(struct backend *backend, struct backend_plane *plane, const uint8_t *pixels);
void backend_destroy_plane(struct backend *backend, struct backend_plane *plane);

#endif
