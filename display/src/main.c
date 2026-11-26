//rtv-display: ラズパイTV用の最小Waylandコンポジタ。
//wl_compositor / wl_shm / wl_output / xdg-shell を実装し、
//クライアントのサーフェスを画面中央に1枚だけ合成するキオスク型。
//標準出力は将来のJava本体とのバイナリプロトコル用に空けるため、
//ログはすべて標準エラーへ出すこと。
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <wayland-server.h>

#ifdef HAVE_TEXT
#include <ft2build.h>
#include FT_FREETYPE_H
#ifdef HAVE_FONTCONFIG
#include <fontconfig/fontconfig.h>
#endif
#endif

#include "backend.h"
#include "xdg-shell-protocol.h"

#define BACKGROUND_COLOR 0xFFDCDCDC
#define WINDOW_WIDTH_FRACTION 2
#define WINDOW_HEIGHT_FRACTION 2

//coreとのバイナリプロトコル（標準入出力）
//要求: [u8 コマンド][u8 引数の数]([u8 サイズ][データ])の繰り返し
//応答: [u8 ステータス]
#define STATUS_OK 0x20
#define STATUS_ERROR 0xFF
#define COMMAND_INIT 0x00
#define COMMAND_CREATE_OBJECT 0x01
#define COMMAND_UPDATE_OBJECT 0x02
#define COMMAND_DELETE_OBJECT 0x03
#define COMMAND_DELETE_ALL_OBJECTS 0x04
#define COMMAND_GET_SIZE 0x05
#define OBJECT_TYPE_RECT 0x01
#define OBJECT_TYPE_TRIANGLE 0x02
#define OBJECT_TYPE_LINE 0x03
#define OBJECT_TYPE_CIRCLE 0x04
#define OBJECT_TYPE_TEXT 0x05
#define OBJECT_TYPE_FRAMEBUFFER 0x06
#define MAX_ARGS 255
#define IN_BUFFER_SIZE 65536

struct proto_arg {
	const uint8_t *data;
	uint8_t size;
};

//coreから作られたオブジェクト。IDとの対応表はこのプロセスが持つ。
//座標フィールドの使い方は種類による:
// 四角形: x,y=左上、width,height
// 三角形: (x,y),(x2,y2),(x3,y3)=3頂点
// 直線: (x,y)=始点、(x2,y2)=終点
// 円: x,y=中心、radius=半径
// テキスト: x,y=左上、size=ピクセルサイズ、text=文字列(UTF-8)
// フレームバッファー: x,y=表示位置、width,height=表示サイズ、
//   src_width,src_height=映像のサイズ(BGRAをソケットから受ける)
struct display_object {
	uint64_t id;
	uint8_t type;
	//重なり順。大きいほど手前に描く。同点なら作成順（seqの小さい順）。
	int64_t z;
	uint64_t seq;
	//座標は符号付き。画面外へはみ出す位置（負の座標）も扱うため
	int64_t x;
	int64_t y;
	uint64_t width;
	uint64_t height;
	int64_t x2;
	int64_t y2;
	int64_t x3;
	int64_t y3;
	uint64_t radius;
	uint64_t size;
	char *text;
	uint64_t src_width;
	uint64_t src_height;
	//フレームバッファー用: 映像受け入れソケットとダブルバッファ
	struct server *server;
	int listen_fd;
	int client_fd;
	struct wl_event_source *listen_source;
	struct wl_event_source *client_source;
	uint8_t *frame_front;
	uint8_t *frame_back;
	size_t frame_size;
	size_t frame_received;
	int has_frame;
	//フレームバッファー用: 1なら画素ごとのアルファで合成する
	//（ハードウェアプレーンは使わず、ソフトウェア描画になる）
	int fb_alpha;
	//フレームバッファー用: ハードウェアプレーン（使える場合だけ）
	struct backend_plane *plane;
	int plane_layer;
	uint8_t *plane_pixels[2];
	uint32_t plane_stride;
	int plane_next;
	uint64_t plane_src_width;
	uint64_t plane_src_height;
	uint8_t r;
	uint8_t g;
	uint8_t b;
	uint8_t a;
	struct wl_list link;
};

struct server {
	struct wl_display *display;
	struct backend *backend;
	struct wl_list surfaces;
	struct wl_list objects;
	uint64_t next_seq;
	uint32_t configure_serial;
	//バックエンド設定（生成は初期化コマンドまで遅延する）
	const char *backend_name;
	const char *dump_path;
	const char *drm_device;
	int width;
	int height;
	//プロトコル受信バッファ
	uint8_t in_buffer[IN_BUFFER_SIZE];
	size_t in_length;
	//再描画の予約（タイマー）。NULLでなければ予約済み
	struct wl_event_source *render_timer_source;
#ifdef HAVE_TEXT
	//テキスト描画用フォント（UDEV Gothic）。初回利用時に読み込む。
	FT_Library ft_library;
	FT_Face ft_face;
	int ft_failed;
#endif
};

struct xdg_surface_data;

struct surface {
	struct wl_resource *resource;
	struct wl_resource *buffer;
	struct wl_resource *pending_buffer;
	int has_pending_buffer;
	struct wl_list frame_callbacks;
	struct xdg_surface_data *xdg;
	struct wl_list link;
	struct server *server;
};

struct xdg_surface_data {
	struct wl_resource *resource;
	struct wl_resource *toplevel;
	struct surface *surface;
};

static uint32_t now_msec(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

//--- 合成 -----------------------------------------------------------------

//座標計算用にu64をint64へ飽和させる（画面より遥かに大きい値は丸めてよい）
static int64_t sat_coord(int64_t value) {
	if (value > (1 << 24)) {
		return 1 << 24;
	}
	if (value < -(1 << 24)) {
		return -(1 << 24);
	}
	return value;
}

static uint64_t isqrt_u64(uint64_t value) {
	uint64_t root = (uint64_t)sqrt((double)value);
	while ((root + 1) * (root + 1) <= value) {
		root++;
	}
	while (root * root > value) {
		root--;
	}
	return root;
}

//1画素をアルファ合成で描く（範囲外・完全透明なら何もしない）
static void blend_pixel(struct server *server, int64_t x, int64_t y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
	struct backend *backend = server->backend;
	if (a == 0 || x < 0 || y < 0 || x >= backend->width || y >= backend->height) {
		return;
	}
	uint32_t *dst = &backend->pixels[y * backend->width + x];
	if (a == 0xFF) {
		*dst = 0xFF000000 | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
		return;
	}
	uint32_t nr = ((uint32_t)r * a + ((*dst >> 16) & 0xFF) * (0xFF - a)) / 0xFF;
	uint32_t ng = ((uint32_t)g * a + ((*dst >> 8) & 0xFF) * (0xFF - a)) / 0xFF;
	uint32_t nb = ((uint32_t)b * a + (*dst & 0xFF) * (0xFF - a)) / 0xFF;
	*dst = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
}

static void draw_rect(struct server *server, struct display_object *object) {
	struct backend *backend = server->backend;
	int64_t width = backend->width;
	int64_t height = backend->height;
	int64_t x0 = sat_coord(object->x);
	int64_t y0 = sat_coord(object->y);
	int64_t x1 = x0 + sat_coord((int64_t)object->width);
	int64_t y1 = y0 + sat_coord((int64_t)object->height);
	//画面と重なる部分だけに切り取る（負の座標で左・上にはみ出していても描く）
	if (x1 <= 0 || y1 <= 0 || x0 >= width || y0 >= height) {
		return;
	}
	if (x0 < 0) {
		x0 = 0;
	}
	if (y0 < 0) {
		y0 = 0;
	}
	if (x1 > width) {
		x1 = width;
	}
	if (y1 > height) {
		y1 = height;
	}
	for (int64_t y = y0; y < y1; y++) {
		for (int64_t x = x0; x < x1; x++) {
			uint32_t *dst = &backend->pixels[y * width + x];
			if (object->a == 0xFF) {
				*dst = 0xFF000000 | ((uint32_t)object->r << 16) | ((uint32_t)object->g << 8) | object->b;
			} else {
				uint32_t a = object->a;
				uint32_t r = (object->r * a + ((*dst >> 16) & 0xFF) * (0xFF - a)) / 0xFF;
				uint32_t g = (object->g * a + ((*dst >> 8) & 0xFF) * (0xFF - a)) / 0xFF;
				uint32_t b = (object->b * a + (*dst & 0xFF) * (0xFF - a)) / 0xFF;
				*dst = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}
}

static int64_t edge_function(int64_t ax, int64_t ay, int64_t bx, int64_t by, int64_t px, int64_t py) {
	return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
}

static void draw_triangle(struct server *server, struct display_object *object) {
	struct backend *backend = server->backend;
	int64_t vx[3];
	int64_t vy[3];
	vx[0] = sat_coord(object->x);
	vy[0] = sat_coord(object->y);
	vx[1] = sat_coord(object->x2);
	vy[1] = sat_coord(object->y2);
	vx[2] = sat_coord(object->x3);
	vy[2] = sat_coord(object->y3);
	int64_t min_x = vx[0];
	int64_t max_x = vx[0];
	int64_t min_y = vy[0];
	int64_t max_y = vy[0];
	for (int i = 1; i < 3; i++) {
		if (vx[i] < min_x) min_x = vx[i];
		if (vx[i] > max_x) max_x = vx[i];
		if (vy[i] < min_y) min_y = vy[i];
		if (vy[i] > max_y) max_y = vy[i];
	}
	if (min_x < 0) min_x = 0;
	if (min_y < 0) min_y = 0;
	if (max_x > backend->width - 1) max_x = backend->width - 1;
	if (max_y > backend->height - 1) max_y = backend->height - 1;
	for (int64_t y = min_y; y <= max_y; y++) {
		for (int64_t x = min_x; x <= max_x; x++) {
			int64_t e0 = edge_function(vx[0], vy[0], vx[1], vy[1], x, y);
			int64_t e1 = edge_function(vx[1], vy[1], vx[2], vy[2], x, y);
			int64_t e2 = edge_function(vx[2], vy[2], vx[0], vy[0], x, y);
			int inside = (e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0);
			if (inside) {
				blend_pixel(server, x, y, object->r, object->g, object->b, object->a);
			}
		}
	}
}

static void draw_line(struct server *server, struct display_object *object) {
	struct backend *backend = server->backend;
	int64_t x0 = sat_coord(object->x);
	int64_t y0 = sat_coord(object->y);
	int64_t x1 = sat_coord(object->x2);
	int64_t y1 = sat_coord(object->y2);
	int64_t dx = x1 - x0;
	int64_t dy = y1 - y0;
	int64_t adx = dx < 0 ? -dx : dx;
	int64_t ady = dy < 0 ? -dy : dy;
	int64_t steps = adx > ady ? adx : ady;
	if (steps == 0) {
		blend_pixel(server, x0, y0, object->r, object->g, object->b, object->a);
		return;
	}
	//Liang-Barsky法で、画面内に入る区間[t0,t1]だけを描く
	double t0 = 0.0;
	double t1 = 1.0;
	double p[4] = {-(double)dx, (double)dx, -(double)dy, (double)dy};
	double q[4] = {(double)x0, (double)(backend->width - 1 - x0), (double)y0, (double)(backend->height - 1 - y0)};
	for (int i = 0; i < 4; i++) {
		if (p[i] == 0.0) {
			if (q[i] < 0.0) {
				return;
			}
			continue;
		}
		double t = q[i] / p[i];
		if (p[i] < 0.0) {
			if (t > t1) {
				return;
			}
			if (t > t0) {
				t0 = t;
			}
		} else {
			if (t < t0) {
				return;
			}
			if (t < t1) {
				t1 = t;
			}
		}
	}
	int64_t i_from = (int64_t)ceil(t0 * (double)steps);
	int64_t i_to = (int64_t)floor(t1 * (double)steps);
	for (int64_t i = i_from; i <= i_to; i++) {
		int64_t x = x0 + (int64_t)llround((double)dx * (double)i / (double)steps);
		int64_t y = y0 + (int64_t)llround((double)dy * (double)i / (double)steps);
		blend_pixel(server, x, y, object->r, object->g, object->b, object->a);
	}
}

static void draw_circle(struct server *server, struct display_object *object) {
	struct backend *backend = server->backend;
	int64_t cx = sat_coord(object->x);
	int64_t cy = sat_coord(object->y);
	int64_t radius = sat_coord(object->radius);
	int64_t y_from = cy - radius;
	if (y_from < 0) {
		y_from = 0;
	}
	int64_t y_to = cy + radius;
	if (y_to > backend->height - 1) {
		y_to = backend->height - 1;
	}
	for (int64_t y = y_from; y <= y_to; y++) {
		int64_t dy = y - cy;
		uint64_t dx = isqrt_u64((uint64_t)(radius * radius - dy * dy));
		int64_t x_from = cx - (int64_t)dx;
		if (x_from < 0) {
			x_from = 0;
		}
		int64_t x_to = cx + (int64_t)dx;
		if (x_to > backend->width - 1) {
			x_to = backend->width - 1;
		}
		for (int64_t x = x_from; x <= x_to; x++) {
			blend_pixel(server, x, y, object->r, object->g, object->b, object->a);
		}
	}
}

#ifdef HAVE_TEXT
//フォントファイルの場所を探す。環境変数RTV_FONT、fontconfig(UDEV Gothic)、
//決め打ちパスの順に探し、見つけたら静的バッファへ入れて返す。
static const char *find_font_path(void) {
	static char buffer[1024];
	buffer[0] = '\0';
	const char *env = getenv("RTV_FONT");
	if (env != NULL && env[0] != '\0') {
		snprintf(buffer, sizeof(buffer), "%s", env);
		return buffer;
	}
#ifdef HAVE_FONTCONFIG
	FcPattern *pattern = FcNameParse((const FcChar8 *)"UDEV Gothic");
	if (pattern != NULL) {
		FcConfigSubstitute(NULL, pattern, FcMatchPattern);
		FcDefaultSubstitute(pattern);
		FcResult result;
		FcPattern *matched = FcFontMatch(NULL, pattern, &result);
		if (matched != NULL) {
			FcChar8 *file = NULL;
			if (FcPatternGetString(matched, FC_FILE, 0, &file) == FcResultMatch && file != NULL) {
				snprintf(buffer, sizeof(buffer), "%s", (const char *)file);
			}
			FcPatternDestroy(matched);
		}
		FcPatternDestroy(pattern);
	}
	if (buffer[0] != '\0') {
		return buffer;
	}
#endif
	char home_path[1024];
	home_path[0] = '\0';
	const char *home = getenv("HOME");
	if (home != NULL) {
		snprintf(home_path, sizeof(home_path), "%s/.local/share/fonts/UDEVGothic-Regular.ttf", home);
	}
	const char *candidates[] = {
		"/usr/share/fonts/truetype/udev-gothic/UDEVGothic-Regular.ttf",
		"/usr/share/fonts/opentype/udev-gothic/UDEVGothic-Regular.ttf",
		home_path,
		"UDEVGothic-Regular.ttf",
		NULL,
	};
	for (int i = 0; candidates[i] != NULL; i++) {
		if (candidates[i][0] != '\0' && access(candidates[i], R_OK) == 0) {
			snprintf(buffer, sizeof(buffer), "%s", candidates[i]);
			return buffer;
		}
	}
	return NULL;
}

//フォントを読み込む（初回だけ）。成功したら1。
static int ensure_font(struct server *server) {
	if (server->ft_face != NULL) {
		return 1;
	}
	if (server->ft_failed) {
		return 0;
	}
	server->ft_failed = 1;
	if (FT_Init_FreeType(&server->ft_library) != 0) {
		fprintf(stderr, "rtv-display: FT_Init_FreeType failed\n");
		return 0;
	}
	const char *path = find_font_path();
	if (path == NULL) {
		fprintf(stderr, "rtv-display: UDEV Gothic font not found\n");
		return 0;
	}
	if (FT_New_Face(server->ft_library, path, 0, &server->ft_face) != 0) {
		fprintf(stderr, "rtv-display: failed to load font: %s\n", path);
		server->ft_face = NULL;
		return 0;
	}
	fprintf(stderr, "rtv-display: font loaded: %s\n", path);
	server->ft_failed = 0;
	return 1;
}

//UTF-8を1文字読んでポインタを進める。不正な並びはU+FFFDにする。
static uint32_t utf8_decode(const uint8_t **cursor) {
	const uint8_t *p = *cursor;
	uint32_t cp;
	int extra;
	if (p[0] < 0x80) {
		cp = p[0];
		extra = 0;
	} else if ((p[0] & 0xE0) == 0xC0) {
		cp = p[0] & 0x1F;
		extra = 1;
	} else if ((p[0] & 0xF0) == 0xE0) {
		cp = p[0] & 0x0F;
		extra = 2;
	} else if ((p[0] & 0xF8) == 0xF0) {
		cp = p[0] & 0x07;
		extra = 3;
	} else {
		*cursor = p + 1;
		return 0xFFFD;
	}
	for (int i = 0; i < extra; i++) {
		if ((p[1 + i] & 0xC0) != 0x80) {
			*cursor = p + 1;
			return 0xFFFD;
		}
		cp = (cp << 6) | (p[1 + i] & 0x3F);
	}
	*cursor = p + 1 + extra;
	return cp;
}

static void draw_text(struct server *server, struct display_object *object) {
	if (object->text == NULL || object->size == 0) {
		return;
	}
	if (!ensure_font(server)) {
		return;
	}
	FT_Face face = server->ft_face;
	uint64_t size = object->size;
	if (size > 2048) {
		size = 2048;
	}
	FT_Set_Pixel_Sizes(face, 0, (FT_UInt)size);
	int64_t pen_x = sat_coord(object->x);
	//yはテキストの上端。ベースラインはアセント分だけ下。
	int64_t baseline = sat_coord(object->y) + (face->size->metrics.ascender >> 6);
	const uint8_t *cursor = (const uint8_t *)object->text;
	while (*cursor != '\0') {
		uint32_t cp = utf8_decode(&cursor);
		if (FT_Load_Char(face, cp, FT_LOAD_RENDER) != 0) {
			continue;
		}
		FT_GlyphSlot glyph = face->glyph;
		FT_Bitmap *bitmap = &glyph->bitmap;
		int64_t gx = pen_x + glyph->bitmap_left;
		int64_t gy = baseline - glyph->bitmap_top;
		for (unsigned int row = 0; row < bitmap->rows; row++) {
			for (unsigned int col = 0; col < bitmap->width; col++) {
				uint8_t coverage = bitmap->buffer[row * (unsigned int)bitmap->pitch + col];
				if (coverage == 0) {
					continue;
				}
				uint8_t alpha = (uint8_t)((uint32_t)object->a * coverage / 0xFF);
				blend_pixel(server, gx + (int64_t)col, gy + (int64_t)row, object->r, object->g, object->b, alpha);
			}
		}
		pen_x += glyph->advance.x >> 6;
	}
}
#else
static int ensure_font(struct server *server) {
	(void)server;
	return 0;
}

static void draw_text(struct server *server, struct display_object *object) {
	//FreeType無しでビルドした場合はテキストを描かない（作成時点でエラーにしている）
	(void)server;
	(void)object;
}
#endif

static void draw_framebuffer(struct server *server, struct display_object *object) {
	struct backend *backend = server->backend;
	int64_t width = backend->width;
	int64_t height = backend->height;
	if (object->width == 0 || object->height == 0 || object->src_width == 0 || object->src_height == 0) {
		return;
	}
	//原点は切り取らずに保持する（ソース座標の対応付けに使う）
	int64_t x0 = sat_coord(object->x);
	int64_t y0 = sat_coord(object->y);
	int64_t x1 = x0 + sat_coord((int64_t)object->width);
	int64_t y1 = y0 + sat_coord((int64_t)object->height);
	if (x1 <= 0 || y1 <= 0 || x0 >= width || y0 >= height) {
		return;
	}
	int64_t cx0 = x0 < 0 ? 0 : x0;
	int64_t cy0 = y0 < 0 ? 0 : y0;
	int64_t cx1 = x1 > width ? width : x1;
	int64_t cy1 = y1 > height ? height : y1;
	for (int64_t y = cy0; y < cy1; y++) {
		//表示サイズへリサイズ（最近傍）しながら描く
		uint64_t sy = (uint64_t)(y - y0) * object->src_height / object->height;
		for (int64_t x = cx0; x < cx1; x++) {
			uint32_t *dst = &backend->pixels[y * width + x];
			if (object->plane != NULL) {
				//映像はハードウェアプレーン側にある。ここは透明にして透かす。
				*dst = 0;
				continue;
			}
			if (!object->has_frame || object->frame_front == NULL) {
				if (object->fb_alpha) {
					//アルファ有効時は、映像が来る前は透明（下を透かす）
					continue;
				}
				//まだ映像が来ていない間は黒で埋める
				*dst = 0xFF000000;
				continue;
			}
			uint64_t sx = (uint64_t)(x - x0) * object->src_width / object->width;
			uint32_t src = ((const uint32_t *)object->frame_front)[sy * object->src_width + sx];
			if (object->fb_alpha) {
				//BGRAのアルファで下と合成する
				blend_pixel(server, x, y,
					(uint8_t)(src >> 16), (uint8_t)(src >> 8), (uint8_t)src,
					(uint8_t)(src >> 24));
				continue;
			}
			//BGRAのバイト並びはXRGB8888のワードと一致する
			*dst = src | 0xFF000000;
		}
	}
}

static void draw_object(struct server *server, struct display_object *object) {
	if (object->a == 0) {
		return;
	}
	switch (object->type) {
	case OBJECT_TYPE_RECT:
		draw_rect(server, object);
		break;
	case OBJECT_TYPE_TRIANGLE:
		draw_triangle(server, object);
		break;
	case OBJECT_TYPE_LINE:
		draw_line(server, object);
		break;
	case OBJECT_TYPE_CIRCLE:
		draw_circle(server, object);
		break;
	case OBJECT_TYPE_TEXT:
		draw_text(server, object);
		break;
	case OBJECT_TYPE_FRAMEBUFFER:
		draw_framebuffer(server, object);
		break;
	default:
		break;
	}
}

//描画順の比較: zの小さい順、同点なら作成順（seqの小さい順）。
static int compare_objects(const void *a, const void *b) {
	struct display_object *object_a = *(struct display_object *const *)a;
	struct display_object *object_b = *(struct display_object *const *)b;
	if (object_a->z != object_b->z) {
		return object_a->z < object_b->z ? -1 : 1;
	}
	if (object_a->seq != object_b->seq) {
		return object_a->seq < object_b->seq ? -1 : 1;
	}
	return 0;
}

static void render(struct server *server) {
	if (server->backend == NULL) {
		//初期化コマンド前は画面が無い
		return;
	}
	struct backend *backend = server->backend;
	int width = backend->width;
	int height = backend->height;
	for (int i = 0; i < width * height; i++) {
		backend->pixels[i] = BACKGROUND_COLOR;
	}
	struct surface *surface;
	wl_list_for_each(surface, &server->surfaces, link) {
		if (surface->buffer == NULL) {
			continue;
		}
		struct wl_shm_buffer *shm = wl_shm_buffer_get(surface->buffer);
		if (shm == NULL) {
			continue;
		}
		int bw = wl_shm_buffer_get_width(shm);
		int bh = wl_shm_buffer_get_height(shm);
		int stride = wl_shm_buffer_get_stride(shm);
		uint32_t format = wl_shm_buffer_get_format(shm);
		int dx = (width - bw) / 2;
		int dy = (height - bh) / 2;
		wl_shm_buffer_begin_access(shm);
		uint8_t *data = wl_shm_buffer_get_data(shm);
		for (int y = 0; y < bh; y++) {
			int fy = dy + y;
			if (fy < 0 || fy >= height) {
				continue;
			}
			uint32_t *src = (uint32_t *)(data + (size_t)y * stride);
			for (int x = 0; x < bw; x++) {
				int fx = dx + x;
				if (fx < 0 || fx >= width) {
					continue;
				}
				uint32_t px = src[x];
				if (format == WL_SHM_FORMAT_ARGB8888) {
					uint32_t a = px >> 24;
					if (a == 0) {
						continue;
					}
					if (a != 0xFF) {
						uint32_t bg = backend->pixels[fy * width + fx];
						uint32_t r = (((px >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (0xFF - a)) / 0xFF;
						uint32_t g = (((px >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (0xFF - a)) / 0xFF;
						uint32_t b = ((px & 0xFF) * a + (bg & 0xFF) * (0xFF - a)) / 0xFF;
						px = 0xFF000000 | (r << 16) | (g << 8) | b;
					}
				}
				backend->pixels[fy * width + fx] = px | 0xFF000000;
			}
		}
		wl_shm_buffer_end_access(shm);
	}
	//オブジェクト（UI）はサーフェスの上に、zの小さい順（同点は作成順）に描く
	int object_count = 0;
	struct display_object *object;
	wl_list_for_each(object, &server->objects, link) {
		object_count++;
	}
	if (object_count > 0) {
		struct display_object **order = malloc(sizeof(*order) * (size_t)object_count);
		if (order != NULL) {
			int i = 0;
			wl_list_for_each(object, &server->objects, link) {
				order[i] = object;
				i++;
			}
			qsort(order, (size_t)object_count, sizeof(*order), compare_objects);
			for (i = 0; i < object_count; i++) {
				draw_object(server, order[i]);
			}
			free(order);
		} else {
			wl_list_for_each(object, &server->objects, link) {
				draw_object(server, object);
			}
		}
	}
	backend->present(backend);
}

static int render_timer_callback(void *data) {
	struct server *server = data;
	struct wl_event_source *source = server->render_timer_source;
	server->render_timer_source = NULL;
	if (source != NULL) {
		wl_event_source_remove(source);
	}
	render(server);
	return 0;
}

//再描画を予約する。コマンドが連続して届いても最大で約60fpsに
//間引いて描く（1コマンドごとの全画面再描画を避ける）。
//応答は予約せずに即座に返すので、クライアントは待たされない。
static void schedule_render(struct server *server) {
	if (server->backend == NULL) {
		//初期化前は描く画面が無い
		return;
	}
	if (server->render_timer_source != NULL) {
		return;
	}
	struct wl_event_loop *loop = wl_display_get_event_loop(server->display);
	server->render_timer_source = wl_event_loop_add_timer(loop, render_timer_callback, server);
	if (server->render_timer_source == NULL) {
		render(server);
		return;
	}
	wl_event_source_timer_update(server->render_timer_source, 16);
}

static void send_frame_callbacks(struct surface *surface) {
	uint32_t time = now_msec();
	struct wl_resource *callback;
	struct wl_resource *tmp;
	wl_resource_for_each_safe(callback, tmp, &surface->frame_callbacks) {
		wl_callback_send_done(callback, time);
		wl_resource_destroy(callback);
	}
}

//--- wl_surface -------------------------------------------------------------

static void surface_handle_destroy(struct wl_client *client, struct wl_resource *resource) {
	wl_resource_destroy(resource);
}

static void surface_handle_attach(struct wl_client *client, struct wl_resource *resource, struct wl_resource *buffer, int32_t x, int32_t y) {
	struct surface *surface = wl_resource_get_user_data(resource);
	surface->pending_buffer = buffer;
	surface->has_pending_buffer = 1;
}

static void surface_handle_damage(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
	//全面再描画するのでダメージ領域は使わない
}

static void surface_handle_frame(struct wl_client *client, struct wl_resource *resource, uint32_t callback_id) {
	struct surface *surface = wl_resource_get_user_data(resource);
	struct wl_resource *callback = wl_resource_create(client, &wl_callback_interface, 1, callback_id);
	wl_list_insert(surface->frame_callbacks.prev, wl_resource_get_link(callback));
}

static void surface_handle_set_opaque_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
}

static void surface_handle_set_input_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
}

static void surface_handle_commit(struct wl_client *client, struct wl_resource *resource) {
	struct surface *surface = wl_resource_get_user_data(resource);
	if (surface->has_pending_buffer) {
		if (surface->buffer != NULL) {
			wl_buffer_send_release(surface->buffer);
		}
		surface->buffer = surface->pending_buffer;
		surface->pending_buffer = NULL;
		surface->has_pending_buffer = 0;
	}
	schedule_render(surface->server);
	send_frame_callbacks(surface);
}

static void surface_handle_set_buffer_transform(struct wl_client *client, struct wl_resource *resource, int32_t transform) {
}

static void surface_handle_set_buffer_scale(struct wl_client *client, struct wl_resource *resource, int32_t scale) {
}

static void surface_handle_damage_buffer(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
}

static const struct wl_surface_interface surface_impl = {
	.destroy = surface_handle_destroy,
	.attach = surface_handle_attach,
	.damage = surface_handle_damage,
	.frame = surface_handle_frame,
	.set_opaque_region = surface_handle_set_opaque_region,
	.set_input_region = surface_handle_set_input_region,
	.commit = surface_handle_commit,
	.set_buffer_transform = surface_handle_set_buffer_transform,
	.set_buffer_scale = surface_handle_set_buffer_scale,
	.damage_buffer = surface_handle_damage_buffer,
};

static void surface_destructor(struct wl_resource *resource) {
	struct surface *surface = wl_resource_get_user_data(resource);
	if (surface->buffer != NULL) {
		wl_buffer_send_release(surface->buffer);
	}
	struct wl_resource *callback;
	struct wl_resource *tmp;
	wl_resource_for_each_safe(callback, tmp, &surface->frame_callbacks) {
		wl_resource_destroy(callback);
	}
	wl_list_remove(&surface->link);
	free(surface);
}

//--- wl_compositor ------------------------------------------------------------

static void compositor_handle_create_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
	struct server *server = wl_resource_get_user_data(resource);
	struct surface *surface = calloc(1, sizeof(*surface));
	surface->server = server;
	wl_list_init(&surface->frame_callbacks);
	surface->resource = wl_resource_create(client, &wl_surface_interface, wl_resource_get_version(resource), id);
	wl_resource_set_implementation(surface->resource, &surface_impl, surface, surface_destructor);
	wl_list_insert(&server->surfaces, &surface->link);
}

static void region_handle_add(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
}

static void region_handle_subtract(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
}

static const struct wl_region_interface region_impl = {
	.add = region_handle_add,
	.subtract = region_handle_subtract,
};

static void compositor_handle_create_region(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
	//リージョンの内容自体は使わない
	struct wl_resource *region = wl_resource_create(client, &wl_region_interface, 1, id);
	wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
	.create_surface = compositor_handle_create_surface,
	.create_region = compositor_handle_create_region,
};

static void compositor_bind(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
	struct wl_resource *resource = wl_resource_create(client, &wl_compositor_interface, version, id);
	wl_resource_set_implementation(resource, &compositor_impl, data, NULL);
}

//--- wl_output ------------------------------------------------------------------

static void output_bind(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
	struct server *server = data;
	struct wl_resource *resource = wl_resource_create(client, &wl_output_interface, version, id);
	wl_resource_set_implementation(resource, NULL, NULL, NULL);
	wl_output_send_geometry(resource, 0, 0, 527, 296, WL_OUTPUT_SUBPIXEL_UNKNOWN, "Rumi", "RaspberryPi TV", WL_OUTPUT_TRANSFORM_NORMAL);
	wl_output_send_mode(resource, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, server->width, server->height, 60000);
	if (version >= WL_OUTPUT_SCALE_SINCE_VERSION) {
		wl_output_send_scale(resource, 1);
	}
	if (version >= WL_OUTPUT_DONE_SINCE_VERSION) {
		wl_output_send_done(resource);
	}
}

//--- xdg-shell --------------------------------------------------------------------

static void xdg_toplevel_handle_destroy(struct wl_client *client, struct wl_resource *resource) {
	wl_resource_destroy(resource);
}

static void xdg_toplevel_handle_set_title(struct wl_client *client, struct wl_resource *resource, const char *title) {
}

static void xdg_toplevel_handle_set_app_id(struct wl_client *client, struct wl_resource *resource, const char *app_id) {
}

static const struct xdg_toplevel_interface xdg_toplevel_impl = {
	.destroy = xdg_toplevel_handle_destroy,
	.set_title = xdg_toplevel_handle_set_title,
	.set_app_id = xdg_toplevel_handle_set_app_id,
};

static void xdg_surface_handle_destroy(struct wl_client *client, struct wl_resource *resource) {
	wl_resource_destroy(resource);
}

static void xdg_surface_handle_get_toplevel(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
	struct xdg_surface_data *xdg = wl_resource_get_user_data(resource);
	struct server *server = xdg->surface->server;
	struct wl_resource *toplevel = wl_resource_create(client, &xdg_toplevel_interface, wl_resource_get_version(resource), id);
	xdg->toplevel = toplevel;
	wl_resource_set_implementation(toplevel, &xdg_toplevel_impl, xdg, NULL);
	//初期configure: ウィンドウは画面の中央に分数サイズで出す
	int win_w = server->width * WINDOW_WIDTH_FRACTION / 3;
	int win_h = server->height * WINDOW_HEIGHT_FRACTION / 3;
	struct wl_array states;
	wl_array_init(&states);
	enum xdg_toplevel_state *state = wl_array_add(&states, sizeof(*state));
	*state = XDG_TOPLEVEL_STATE_ACTIVATED;
	xdg_toplevel_send_configure(toplevel, win_w, win_h, &states);
	wl_array_release(&states);
	xdg_surface_send_configure(resource, ++server->configure_serial);
}

static void xdg_surface_handle_set_window_geometry(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y, int32_t width, int32_t height) {
}

static void xdg_surface_handle_ack_configure(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
}

static const struct xdg_surface_interface xdg_surface_impl = {
	.destroy = xdg_surface_handle_destroy,
	.get_toplevel = xdg_surface_handle_get_toplevel,
	.set_window_geometry = xdg_surface_handle_set_window_geometry,
	.ack_configure = xdg_surface_handle_ack_configure,
};

static void xdg_surface_destructor(struct wl_resource *resource) {
	struct xdg_surface_data *xdg = wl_resource_get_user_data(resource);
	if (xdg->surface != NULL) {
		xdg->surface->xdg = NULL;
	}
	free(xdg);
}

static void wm_base_handle_destroy(struct wl_client *client, struct wl_resource *resource) {
	wl_resource_destroy(resource);
}

static void wm_base_handle_get_xdg_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id, struct wl_resource *surface_resource) {
	struct surface *surface = wl_resource_get_user_data(surface_resource);
	struct xdg_surface_data *xdg = calloc(1, sizeof(*xdg));
	xdg->surface = surface;
	xdg->resource = wl_resource_create(client, &xdg_surface_interface, wl_resource_get_version(resource), id);
	wl_resource_set_implementation(xdg->resource, &xdg_surface_impl, xdg, xdg_surface_destructor);
	surface->xdg = xdg;
}

static void wm_base_handle_pong(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
}

static const struct xdg_wm_base_interface wm_base_impl = {
	.destroy = wm_base_handle_destroy,
	.get_xdg_surface = wm_base_handle_get_xdg_surface,
	.pong = wm_base_handle_pong,
};

static void wm_base_bind(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
	struct wl_resource *resource = wl_resource_create(client, &xdg_wm_base_interface, version, id);
	wl_resource_set_implementation(resource, &wm_base_impl, data, NULL);
}

//--- coreとのプロトコル -----------------------------------------------------------

static struct backend *create_backend(struct server *server) {
	if (strcmp(server->backend_name, "headless") == 0) {
		return backend_headless_create(server->width, server->height, server->dump_path);
	}
	if (strcmp(server->backend_name, "drm") == 0) {
		return backend_drm_create(server->drm_device, server->width, server->height);
	}
	if (strcmp(server->backend_name, "window") == 0) {
		return backend_window_create(server->display, server->width, server->height);
	}
	fprintf(stderr, "rtv-display: unknown backend '%s'\n", server->backend_name);
	return NULL;
}

static uint64_t read_u64be(const uint8_t *data) {
	uint64_t value = 0;
	for (int i = 0; i < 8; i++) {
		value = (value << 8) | data[i];
	}
	return value;
}

static void write_u32be(uint8_t *data, uint32_t value) {
	for (int i = 3; i >= 0; i--) {
		data[i] = value & 0xFF;
		value >>= 8;
	}
}

static struct display_object *find_object(struct server *server, uint64_t id) {
	struct display_object *object;
	wl_list_for_each(object, &server->objects, link) {
		if (object->id == id) {
			return object;
		}
	}
	return NULL;
}

//--- フレームバッファーの映像受け入れ -------------------------------------
// /tmp/rpitv-buffer-{ID}.sock というUNIXソケットでBGRAの映像を受ける。

static void framebuffer_socket_path(struct display_object *object, char *buffer, size_t size) {
	snprintf(buffer, size, "/tmp/rpitv-buffer-%llu.sock", (unsigned long long)object->id);
}

//ソケットとフレームバッファを片付ける（どの種類のオブジェクトに呼んでも安全）。
static void framebuffer_close(struct display_object *object) {
	if (object->plane != NULL) {
		if (object->server != NULL && object->server->backend != NULL) {
			backend_destroy_plane(object->server->backend, object->plane);
		}
		object->plane = NULL;
	}
	object->plane_layer = -1;
	if (object->client_source != NULL) {
		wl_event_source_remove(object->client_source);
		object->client_source = NULL;
	}
	if (object->client_fd >= 0) {
		close(object->client_fd);
		object->client_fd = -1;
	}
	if (object->listen_source != NULL) {
		wl_event_source_remove(object->listen_source);
		object->listen_source = NULL;
	}
	if (object->listen_fd >= 0) {
		close(object->listen_fd);
		object->listen_fd = -1;
		char path[64];
		framebuffer_socket_path(object, path, sizeof(path));
		unlink(path);
	}
	free(object->frame_front);
	free(object->frame_back);
	object->frame_front = NULL;
	object->frame_back = NULL;
	object->frame_size = 0;
	object->frame_received = 0;
	object->has_frame = 0;
}

static void framebuffer_disconnect_client(struct display_object *object) {
	if (object->client_source != NULL) {
		wl_event_source_remove(object->client_source);
		object->client_source = NULL;
	}
	if (object->client_fd >= 0) {
		close(object->client_fd);
		object->client_fd = -1;
	}
	object->frame_received = 0;
}

static int framebuffer_client_callback(int fd, uint32_t mask, void *data) {
	struct display_object *object = data;
	if (object->frame_back == NULL || object->frame_size == 0) {
		return 0;
	}
	for (;;) {
		ssize_t n = read(fd, object->frame_back + object->frame_received, object->frame_size - object->frame_received);
		if (n > 0) {
			object->frame_received += (size_t)n;
			if (object->frame_received == object->frame_size) {
				//1フレーム揃ったので表と入れ替えて表示する
				uint8_t *tmp = object->frame_front;
				object->frame_front = object->frame_back;
				object->frame_back = tmp;
				object->frame_received = 0;
				object->has_frame = 1;
				if (object->plane != NULL && object->server->backend != NULL) {
					//プレーンの裏バッファへ行ごとにコピーしてフリップする。
					//合成とリサイズは表示コントローラがやるので、全体の再描画はしない。
					uint8_t *dst = object->plane_pixels[object->plane_next];
					for (uint64_t row = 0; row < object->src_height; row++) {
						memcpy(dst + row * object->plane_stride,
							object->frame_front + row * object->src_width * 4,
							(size_t)object->src_width * 4);
					}
					if (backend_plane_flip(object->server->backend, object->plane, dst) == 0) {
						object->plane_next ^= 1;
					} else {
						fprintf(stderr, "rtv-display: framebuffer %llu: plane flip failed; falling back to software rendering\n", (unsigned long long)object->id);
						backend_destroy_plane(object->server->backend, object->plane);
						object->plane = NULL;
						object->plane_layer = -1;
						schedule_render(object->server);
					}
				} else {
					schedule_render(object->server);
				}
			}
			continue;
		}
		if (n == 0) {
			//切断。最後のフレームは表示したままにする。
			fprintf(stderr, "rtv-display: framebuffer %llu: producer disconnected\n", (unsigned long long)object->id);
			framebuffer_disconnect_client(object);
			return 0;
		}
		if (errno == EINTR) {
			continue;
		}
		if (errno == EAGAIN || errno == EWOULDBLOCK) {
			return 0;
		}
		framebuffer_disconnect_client(object);
		return 0;
	}
}

static int framebuffer_listen_callback(int fd, uint32_t mask, void *data) {
	struct display_object *object = data;
	int client = accept(fd, NULL, NULL);
	if (client < 0) {
		return 0;
	}
	int flags = fcntl(client, F_GETFL, 0);
	fcntl(client, F_SETFL, flags | O_NONBLOCK);
	fcntl(client, F_SETFD, FD_CLOEXEC);
	//接続は1つだけ。前の接続があれば切る。
	if (object->client_fd >= 0) {
		framebuffer_disconnect_client(object);
	}
	object->client_fd = client;
	struct wl_event_loop *loop = wl_display_get_event_loop(object->server->display);
	object->client_source = wl_event_loop_add_fd(loop, client, WL_EVENT_READABLE, framebuffer_client_callback, object);
	fprintf(stderr, "rtv-display: framebuffer %llu: producer connected\n", (unsigned long long)object->id);
	return 0;
}

//ソケットを用意し、映像サイズに合わせてフレームバッファを確保する。
static int framebuffer_setup(struct server *server, struct display_object *object) {
	if (object->listen_fd < 0) {
		char path[64];
		framebuffer_socket_path(object, path, sizeof(path));
		unlink(path);
		int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
		if (fd < 0) {
			return 0;
		}
		int flags = fcntl(fd, F_GETFL, 0);
		fcntl(fd, F_SETFL, flags | O_NONBLOCK);
		struct sockaddr_un addr;
		memset(&addr, 0, sizeof(addr));
		addr.sun_family = AF_UNIX;
		snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
		if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(fd, 4) != 0) {
			fprintf(stderr, "rtv-display: framebuffer: cannot bind %s\n", path);
			close(fd);
			return 0;
		}
		object->listen_fd = fd;
		struct wl_event_loop *loop = wl_display_get_event_loop(server->display);
		object->listen_source = wl_event_loop_add_fd(loop, fd, WL_EVENT_READABLE, framebuffer_listen_callback, object);
		fprintf(stderr, "rtv-display: framebuffer socket ready: %s (source %llux%llu, display %llux%llu)\n",
			path,
			(unsigned long long)object->src_width, (unsigned long long)object->src_height,
			(unsigned long long)object->width, (unsigned long long)object->height);
	}
	size_t frame_size = (size_t)object->src_width * (size_t)object->src_height * 4;
	if (object->frame_size != frame_size) {
		free(object->frame_front);
		free(object->frame_back);
		object->frame_front = malloc(frame_size);
		object->frame_back = malloc(frame_size);
		if (object->frame_front == NULL || object->frame_back == NULL) {
			free(object->frame_front);
			free(object->frame_back);
			object->frame_front = NULL;
			object->frame_back = NULL;
			object->frame_size = 0;
			object->frame_received = 0;
			object->has_frame = 0;
			return 0;
		}
		object->frame_size = frame_size;
		object->frame_received = 0;
		object->has_frame = 0;
	}
	//バックエンドがあるなら、ハードウェアプレーンの割り当ても試みる。
	//失敗したらソフトウェア描画のまま（planeはNULL）。
	//アルファ有効時はプレーンを使わない（プレーンでは下のソフトウェア
	//描画と画素単位で合成できないため）。既に持っていたら手放す。
	if (server->backend != NULL && object->fb_alpha && object->plane != NULL) {
		backend_destroy_plane(server->backend, object->plane);
		object->plane = NULL;
		object->plane_layer = -1;
	}
	if (server->backend != NULL && !object->fb_alpha) {
		if (object->plane != NULL &&
			(object->plane_src_width != object->src_width || object->plane_src_height != object->src_height)) {
			//映像サイズが変わったら、バッファを作り直すために一度手放す
			backend_destroy_plane(server->backend, object->plane);
			object->plane = NULL;
		}
		if (object->plane == NULL) {
			object->plane = backend_create_plane(server->backend,
				(uint32_t)object->src_width, (uint32_t)object->src_height,
				object->plane_pixels, &object->plane_stride);
			if (object->plane != NULL) {
				object->plane_src_width = object->src_width;
				object->plane_src_height = object->src_height;
				object->plane_next = 0;
			}
		}
		if (object->plane != NULL &&
			backend_plane_set_geometry(server->backend, object->plane,
				(int)object->x, (int)object->y, (int)object->width, (int)object->height) != 0) {
			backend_destroy_plane(server->backend, object->plane);
			object->plane = NULL;
		}
	}
	return 1;
}

//フレームバッファーのハードウェアプレーンの重なり順を、オブジェクトの
//z（同点は作成順）に合わせる。プレーンはプライマリ（ソフトウェア描画の
//面）より下にしか置けないため、ソフトウェア描画のフレームバッファーより
//上に来るプレーンは順序が逆転してしまう。その場合はプレーンを手放して
//ソフトウェア描画に落とす。
static void refresh_framebuffer_layers(struct server *server) {
	if (server->backend == NULL) {
		return;
	}
	//ソフトウェア描画になっているフレームバッファーのうち最も下のもの
	int have_software = 0;
	int64_t software_z = 0;
	uint64_t software_seq = 0;
	struct display_object *object;
	wl_list_for_each(object, &server->objects, link) {
		if (object->type != OBJECT_TYPE_FRAMEBUFFER || object->plane != NULL || object->listen_fd < 0) {
			continue;
		}
		if (!have_software || object->z < software_z ||
			(object->z == software_z && object->seq < software_seq)) {
			have_software = 1;
			software_z = object->z;
			software_seq = object->seq;
		}
	}
	if (have_software) {
		wl_list_for_each(object, &server->objects, link) {
			if (object->type != OBJECT_TYPE_FRAMEBUFFER || object->plane == NULL) {
				continue;
			}
			if (object->z > software_z ||
				(object->z == software_z && object->seq > software_seq)) {
				fprintf(stderr, "rtv-display: framebuffer %llu: dropping hardware plane to keep z order (a framebuffer above it renders in software)\n",
					(unsigned long long)object->id);
				backend_destroy_plane(server->backend, object->plane);
				object->plane = NULL;
				object->plane_layer = -1;
				schedule_render(server);
			}
		}
	}
	//残ったプレーンへ、上にある順に層（プライマリ直下が0）を割り当てる
	int count = 0;
	wl_list_for_each(object, &server->objects, link) {
		if (object->type == OBJECT_TYPE_FRAMEBUFFER && object->plane != NULL) {
			count++;
		}
	}
	if (count == 0) {
		return;
	}
	struct display_object **order = malloc(sizeof(*order) * (size_t)count);
	if (order == NULL) {
		return;
	}
	int i = 0;
	wl_list_for_each(object, &server->objects, link) {
		if (object->type == OBJECT_TYPE_FRAMEBUFFER && object->plane != NULL) {
			order[i] = object;
			i++;
		}
	}
	qsort(order, (size_t)count, sizeof(*order), compare_objects);
	for (i = 0; i < count; i++) {
		//昇順の末尾が一番上。プライマリ直下を層0として数える
		int layer = count - 1 - i;
		struct display_object *target = order[i];
		if (target->plane_layer == layer) {
			continue;
		}
		if (backend_plane_set_layer(server->backend, target->plane, layer) != 0) {
			fprintf(stderr, "rtv-display: framebuffer %llu: cannot set plane layer %d; falling back to software rendering\n",
				(unsigned long long)target->id, layer);
			backend_destroy_plane(server->backend, target->plane);
			target->plane = NULL;
			target->plane_layer = -1;
			schedule_render(server);
		} else {
			target->plane_layer = layer;
		}
	}
	free(order);
}

static void apply_color(struct display_object *object, const uint8_t *color) {
	object->r = color[0];
	object->g = color[1];
	object->b = color[2];
	object->a = color[3];
}

//テキストオブジェクトの文字列を差し替える（dataがNULLなら解放だけ）。
static void set_object_text(struct display_object *object, const uint8_t *data, uint8_t size) {
	free(object->text);
	object->text = NULL;
	if (data == NULL) {
		return;
	}
	object->text = malloc((size_t)size + 1);
	memcpy(object->text, data, size);
	object->text[size] = '\0';
}

//種類ごとの内容（IDと種類の後ろに続く引数列）をオブジェクトへ反映する。
//args[first]から末尾までを使う。形が合わなければ0を返す（その場合オブジェクトは変更しない）。
static int apply_object_args(struct server *server, struct display_object *object, struct proto_arg *args, int argc, int first) {
	const uint8_t *geometry = NULL;
	const uint8_t *color = NULL;
	if (argc - first >= 2) {
		geometry = args[first].data;
		color = args[first + 1].data;
	}
	switch (object->type) {
	case OBJECT_TYPE_RECT:
		if (argc - first != 2 || args[first].size != 32 || args[first + 1].size != 4) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->width = read_u64be(geometry + 16);
		object->height = read_u64be(geometry + 24);
		apply_color(object, color);
		return 1;
	case OBJECT_TYPE_TRIANGLE:
		if (argc - first != 2 || args[first].size != 48 || args[first + 1].size != 4) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->x2 = read_u64be(geometry + 16);
		object->y2 = read_u64be(geometry + 24);
		object->x3 = read_u64be(geometry + 32);
		object->y3 = read_u64be(geometry + 40);
		apply_color(object, color);
		return 1;
	case OBJECT_TYPE_LINE:
		if (argc - first != 2 || args[first].size != 32 || args[first + 1].size != 4) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->x2 = read_u64be(geometry + 16);
		object->y2 = read_u64be(geometry + 24);
		apply_color(object, color);
		return 1;
	case OBJECT_TYPE_CIRCLE:
		if (argc - first != 2 || args[first].size != 24 || args[first + 1].size != 4) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->radius = read_u64be(geometry + 16);
		apply_color(object, color);
		return 1;
	case OBJECT_TYPE_TEXT:
		//引数: 位置とサイズ(24バイト)、色(4バイト)、文字列(UTF-8)
		if (argc - first != 3 || args[first].size != 24 || args[first + 1].size != 4) {
			return 0;
		}
		if (!ensure_font(server)) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->size = read_u64be(geometry + 16);
		apply_color(object, color);
		set_object_text(object, args[first + 2].data, args[first + 2].size);
		return 1;
	case OBJECT_TYPE_FRAMEBUFFER: {
		//引数: 表示位置とサイズ(32バイト)、映像のサイズ(16バイト)、
		//任意でフラグ(1バイト、bit0=アルファ有効)
		int rest = argc - first;
		if ((rest != 2 && rest != 3) || args[first].size != 32 || args[first + 1].size != 16) {
			return 0;
		}
		if (rest == 3 && args[first + 2].size != 1) {
			return 0;
		}
		uint64_t src_width = read_u64be(args[first + 1].data);
		uint64_t src_height = read_u64be(args[first + 1].data + 8);
		if (src_width == 0 || src_height == 0 || src_width > 7680 || src_height > 4320 ||
			src_width * src_height > 3840 * 2160) {
			return 0;
		}
		object->x = read_u64be(geometry);
		object->y = read_u64be(geometry + 8);
		object->width = read_u64be(geometry + 16);
		object->height = read_u64be(geometry + 24);
		object->src_width = src_width;
		object->src_height = src_height;
		object->fb_alpha = (rest == 3 && (args[first + 2].data[0] & 0x01) != 0) ? 1 : 0;
		object->a = 0xFF;
		if (!framebuffer_setup(server, object)) {
			return 0;
		}
		return 1;
	}
	default:
		return 0;
	}
}

//コマンドを処理する。データを返すコマンドは payload に続きのバイト列を入れ、
//payload_length にその長さを返す（呼び出し側がステータスの後に送る）。
static uint8_t handle_command(struct server *server, uint8_t command, struct proto_arg *args, int argc, uint8_t *payload, size_t *payload_length) {
	*payload_length = 0;
	switch (command) {
	case COMMAND_INIT:
		//初期化: ここでバックエンドを作り、画面が出る/切り替わる
		if (server->backend != NULL) {
			return STATUS_OK;
		}
		server->backend = create_backend(server);
		if (server->backend == NULL) {
			return STATUS_ERROR;
		}
		//起動前に作られたフレームバッファーにもプレーンを割り当てる
		struct display_object *object;
		wl_list_for_each(object, &server->objects, link) {
			if (object->type == OBJECT_TYPE_FRAMEBUFFER) {
				framebuffer_setup(server, object);
			}
		}
		refresh_framebuffer_layers(server);
		schedule_render(server);
		return STATUS_OK;
	case COMMAND_CREATE_OBJECT: {
		//引数1: ID(u64 BE)、引数2: 種類(u8)、引数3: Z位置(i64 BE)、引数4以降: 種類ごとの内容
		if (argc < 3 || args[0].size != 8 || args[1].size != 1 || args[2].size != 8) {
			fprintf(stderr, "rtv-display: create object: bad args\n");
			return STATUS_ERROR;
		}
		uint8_t type = args[1].data[0];
		if (type != OBJECT_TYPE_RECT && type != OBJECT_TYPE_TRIANGLE && type != OBJECT_TYPE_LINE && type != OBJECT_TYPE_CIRCLE && type != OBJECT_TYPE_TEXT && type != OBJECT_TYPE_FRAMEBUFFER) {
			fprintf(stderr, "rtv-display: create object: unknown type 0x%02X\n", type);
			return STATUS_ERROR;
		}
		uint64_t id = read_u64be(args[0].data);
		int64_t z = (int64_t)read_u64be(args[2].data);
		struct display_object *object = find_object(server, id);
		int is_new = 0;
		if (object == NULL) {
			object = calloc(1, sizeof(*object));
			object->id = id;
			object->server = server;
			object->listen_fd = -1;
			object->client_fd = -1;
			object->plane_layer = -1;
			object->seq = server->next_seq;
			server->next_seq++;
			is_new = 1;
		}
		uint8_t old_type = object->type;
		object->type = type;
		if (!apply_object_args(server, object, args, argc, 3)) {
			fprintf(stderr, "rtv-display: create object: bad args for type 0x%02X\n", type);
			object->type = old_type;
			if (is_new) {
				framebuffer_close(object);
				free(object);
			}
			return STATUS_ERROR;
		}
		object->z = z;
		if (type != OBJECT_TYPE_TEXT) {
			set_object_text(object, NULL, 0);
		}
		if (old_type == OBJECT_TYPE_FRAMEBUFFER && type != OBJECT_TYPE_FRAMEBUFFER) {
			framebuffer_close(object);
		}
		if (is_new) {
			//リストの並びは作成順のまま保つ（描画順はzで決める）
			wl_list_insert(server->objects.prev, &object->link);
		}
		if (type == OBJECT_TYPE_FRAMEBUFFER || old_type == OBJECT_TYPE_FRAMEBUFFER) {
			refresh_framebuffer_layers(server);
		}
		schedule_render(server);
		return STATUS_OK;
	}
	case COMMAND_UPDATE_OBJECT: {
		//引数1: ID(u64 BE)、引数2: Z位置(i64 BE)、引数3以降: 種類ごとの内容（作成と同じ並び）
		if (argc < 2 || args[0].size != 8 || args[1].size != 8) {
			fprintf(stderr, "rtv-display: update object: bad args\n");
			return STATUS_ERROR;
		}
		struct display_object *object = find_object(server, read_u64be(args[0].data));
		if (object == NULL) {
			fprintf(stderr, "rtv-display: update object: no such id\n");
			return STATUS_ERROR;
		}
		int64_t z = (int64_t)read_u64be(args[1].data);
		if (!apply_object_args(server, object, args, argc, 2)) {
			fprintf(stderr, "rtv-display: update object: bad args\n");
			return STATUS_ERROR;
		}
		object->z = z;
		if (object->type == OBJECT_TYPE_FRAMEBUFFER) {
			refresh_framebuffer_layers(server);
		}
		schedule_render(server);
		return STATUS_OK;
	}
	case COMMAND_DELETE_OBJECT: {
		//引数1: ID(u64 BE)
		if (argc != 1 || args[0].size != 8) {
			fprintf(stderr, "rtv-display: delete object: bad args\n");
			return STATUS_ERROR;
		}
		struct display_object *object = find_object(server, read_u64be(args[0].data));
		if (object == NULL) {
			fprintf(stderr, "rtv-display: delete object: no such id\n");
			return STATUS_ERROR;
		}
		int was_framebuffer = object->type == OBJECT_TYPE_FRAMEBUFFER;
		wl_list_remove(&object->link);
		framebuffer_close(object);
		free(object->text);
		free(object);
		if (was_framebuffer) {
			refresh_framebuffer_layers(server);
		}
		schedule_render(server);
		return STATUS_OK;
	}
	case COMMAND_DELETE_ALL_OBJECTS: {
		//引数なし。全オブジェクトを消す。
		if (argc != 0) {
			fprintf(stderr, "rtv-display: delete all objects: bad args\n");
			return STATUS_ERROR;
		}
		struct display_object *object;
		struct display_object *tmp;
		wl_list_for_each_safe(object, tmp, &server->objects, link) {
			wl_list_remove(&object->link);
			framebuffer_close(object);
			free(object->text);
			free(object);
		}
		schedule_render(server);
		return STATUS_OK;
	}
	case COMMAND_GET_SIZE: {
		//引数なし。応答はステータスの後に幅(u32 BE)、高さ(u32 BE)。
		//初期化前（バックエンドがまだ無い）ならエラー。
		if (argc != 0 || server->backend == NULL) {
			fprintf(stderr, "rtv-display: get size: not initialized\n");
			return STATUS_ERROR;
		}
		write_u32be(payload, (uint32_t)server->backend->width);
		write_u32be(payload + 4, (uint32_t)server->backend->height);
		*payload_length = 8;
		return STATUS_OK;
	}
	default:
		fprintf(stderr, "rtv-display: unknown command 0x%02X\n", command);
		return STATUS_ERROR;
	}
}

//バッファ先頭のフレームを1つ解釈する。完結していなければ0、
//解釈できたら消費したバイト数を返す。
static size_t parse_frame(struct server *server, struct proto_arg *args, uint8_t *command_out, int *argc_out) {
	if (server->in_length < 2) {
		return 0;
	}
	uint8_t command = server->in_buffer[0];
	int argc = server->in_buffer[1];
	size_t pos = 2;
	for (int i = 0; i < argc; i++) {
		if (pos >= server->in_length) {
			return 0;
		}
		uint8_t size = server->in_buffer[pos];
		pos += 1;
		if (pos + size > server->in_length) {
			return 0;
		}
		args[i].data = server->in_buffer + pos;
		args[i].size = size;
		pos += size;
	}
	*command_out = command;
	*argc_out = argc;
	return pos;
}

static int stdin_callback(int fd, uint32_t mask, void *data) {
	struct server *server = data;
	if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
		wl_display_terminate(server->display);
		return 0;
	}
	ssize_t n = read(fd, server->in_buffer + server->in_length, sizeof(server->in_buffer) - server->in_length);
	if (n == 0) {
		//coreが標準入力を閉じた（＝終了した）ので自分も終わる
		fprintf(stderr, "rtv-display: stdin closed, exiting\n");
		wl_display_terminate(server->display);
		return 0;
	}
	if (n < 0) {
		return 0;
	}
	server->in_length += (size_t)n;
	for (;;) {
		struct proto_arg args[MAX_ARGS];
		uint8_t command = 0;
		int argc = 0;
		size_t consumed = parse_frame(server, args, &command, &argc);
		if (consumed == 0) {
			break;
		}
		uint8_t payload[8];
		size_t payload_length = 0;
		uint8_t status = handle_command(server, command, args, argc, payload, &payload_length);
		uint8_t response[1 + 8];
		response[0] = status;
		memcpy(response + 1, payload, payload_length);
		if (write(STDOUT_FILENO, response, 1 + payload_length) < 0) {
			fprintf(stderr, "rtv-display: failed to write response\n");
		}
		memmove(server->in_buffer, server->in_buffer + consumed, server->in_length - consumed);
		server->in_length -= consumed;
	}
	if (server->in_length == sizeof(server->in_buffer)) {
		fprintf(stderr, "rtv-display: protocol buffer overflow, exiting\n");
		wl_display_terminate(server->display);
	}
	return 0;
}

//--- main ---------------------------------------------------------------------------

int main(int argc, char **argv) {
	const char *socket_name = NULL;
	const char *dump_path = "frame.ppm";
	const char *backend_name = "headless";
	const char *drm_device = "/dev/dri/card0";
	int width = 1920;
	int height = 1080;
	for (int i = 1; i < argc; i++) {
		if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc) {
			socket_name = argv[++i];
		} else if (strcmp(argv[i], "--dump") == 0 && i + 1 < argc) {
			dump_path = argv[++i];
		} else if (strcmp(argv[i], "--backend") == 0 && i + 1 < argc) {
			backend_name = argv[++i];
		} else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
			drm_device = argv[++i];
		} else if (strcmp(argv[i], "--width") == 0 && i + 1 < argc) {
			width = atoi(argv[++i]);
		} else if (strcmp(argv[i], "--height") == 0 && i + 1 < argc) {
			height = atoi(argv[++i]);
		}
	}

	struct server server = {0};
	wl_list_init(&server.surfaces);
	wl_list_init(&server.objects);
	server.display = wl_display_create();
	if (server.display == NULL) {
		fprintf(stderr, "rtv-display: wl_display_create failed\n");
		return 1;
	}
	//バックエンドは作らない。初期化コマンド(0x00)が来て初めて画面が出る。
	server.backend_name = backend_name;
	server.dump_path = dump_path;
	server.drm_device = drm_device;
	server.width = width;
	server.height = height;
	wl_global_create(server.display, &wl_compositor_interface, 4, &server, compositor_bind);
	wl_display_init_shm(server.display);
	wl_global_create(server.display, &wl_output_interface, 2, &server, output_bind);
	wl_global_create(server.display, &xdg_wm_base_interface, 2, &server, wm_base_bind);

	const char *name;
	if (socket_name != NULL) {
		if (wl_display_add_socket(server.display, socket_name) != 0) {
			fprintf(stderr, "rtv-display: failed to add socket %s\n", socket_name);
			return 1;
		}
		name = socket_name;
	} else {
		name = wl_display_add_socket_auto(server.display);
		if (name == NULL) {
			fprintf(stderr, "rtv-display: failed to add socket\n");
			return 1;
		}
	}
	struct wl_event_loop *loop = wl_display_get_event_loop(server.display);
	wl_event_loop_add_fd(loop, STDIN_FILENO, WL_EVENT_READABLE, stdin_callback, &server);
	fprintf(stderr, "rtv-display: listening on %s (%dx%d, backend %s, waiting for init command)\n", name, width, height, backend_name);
	wl_display_run(server.display);
	wl_display_destroy(server.display);
	if (server.backend != NULL) {
		server.backend->destroy(server.backend);
	}
	return 0;
}
