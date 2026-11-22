//rtv-display: ラズパイTV用の最小Waylandコンポジタ。
//wl_compositor / wl_shm / wl_output / xdg-shell を実装し、
//クライアントのサーフェスを画面中央に1枚だけ合成するキオスク型。
//標準出力は将来のJava本体とのバイナリプロトコル用に空けるため、
//ログはすべて標準エラーへ出すこと。
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <wayland-server.h>

#include "backend.h"
#include "xdg-shell-protocol.h"

#define BACKGROUND_COLOR 0xFF20242A
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
#define OBJECT_TYPE_RECT 0x01
#define MAX_ARGS 255
#define IN_BUFFER_SIZE 65536

struct proto_arg {
	const uint8_t *data;
	uint8_t size;
};

//coreから作られたオブジェクト。IDとの対応表はこのプロセスが持つ。
struct display_object {
	uint64_t id;
	uint8_t type;
	uint64_t x;
	uint64_t y;
	uint64_t width;
	uint64_t height;
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
	//オブジェクト（UI）はサーフェスの上に描く
	struct display_object *object;
	wl_list_for_each(object, &server->objects, link) {
		if (object->type != OBJECT_TYPE_RECT || object->a == 0) {
			continue;
		}
		if (object->x >= (uint64_t)width || object->y >= (uint64_t)height) {
			continue;
		}
		uint64_t x1 = object->x + object->width;
		if (x1 > (uint64_t)width || x1 < object->x) {
			x1 = (uint64_t)width;
		}
		uint64_t y1 = object->y + object->height;
		if (y1 > (uint64_t)height || y1 < object->y) {
			y1 = (uint64_t)height;
		}
		for (uint64_t y = object->y; y < y1; y++) {
			for (uint64_t x = object->x; x < x1; x++) {
				uint32_t *dst = &backend->pixels[y * (uint64_t)width + x];
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
	backend->present(backend);
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
	render(surface->server);
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

static struct display_object *find_object(struct server *server, uint64_t id) {
	struct display_object *object;
	wl_list_for_each(object, &server->objects, link) {
		if (object->id == id) {
			return object;
		}
	}
	return NULL;
}

//位置・サイズ（32バイト）と色（4バイト）をオブジェクトへ反映する
static void apply_geometry_color(struct display_object *object, const uint8_t *geometry, const uint8_t *color) {
	object->x = read_u64be(geometry);
	object->y = read_u64be(geometry + 8);
	object->width = read_u64be(geometry + 16);
	object->height = read_u64be(geometry + 24);
	object->r = color[0];
	object->g = color[1];
	object->b = color[2];
	object->a = color[3];
}

static uint8_t handle_command(struct server *server, uint8_t command, struct proto_arg *args, int argc) {
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
		render(server);
		return STATUS_OK;
	case COMMAND_CREATE_OBJECT: {
		//引数1: ID(u64 BE)、引数2: 種類(u8)、
		//引数3: X・Y・横幅・縦幅(各u64 BE)、引数4: 色(R・G・B・A)
		if (argc != 4 || args[0].size != 8 || args[1].size != 1 || args[2].size != 32 || args[3].size != 4) {
			fprintf(stderr, "rtv-display: create object: bad args\n");
			return STATUS_ERROR;
		}
		uint8_t type = args[1].data[0];
		if (type != OBJECT_TYPE_RECT) {
			fprintf(stderr, "rtv-display: create object: unknown type 0x%02X\n", type);
			return STATUS_ERROR;
		}
		uint64_t id = read_u64be(args[0].data);
		struct display_object *object = find_object(server, id);
		if (object == NULL) {
			object = calloc(1, sizeof(*object));
			object->id = id;
			//作った順に手前へ描くので末尾へ足す
			wl_list_insert(server->objects.prev, &object->link);
		}
		object->type = type;
		apply_geometry_color(object, args[2].data, args[3].data);
		render(server);
		return STATUS_OK;
	}
	case COMMAND_UPDATE_OBJECT: {
		//引数1: ID(u64 BE)、引数2: X・Y・横幅・縦幅、引数3: 色
		if (argc != 3 || args[0].size != 8 || args[1].size != 32 || args[2].size != 4) {
			fprintf(stderr, "rtv-display: update object: bad args\n");
			return STATUS_ERROR;
		}
		struct display_object *object = find_object(server, read_u64be(args[0].data));
		if (object == NULL) {
			fprintf(stderr, "rtv-display: update object: no such id\n");
			return STATUS_ERROR;
		}
		apply_geometry_color(object, args[1].data, args[2].data);
		render(server);
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
		wl_list_remove(&object->link);
		free(object);
		render(server);
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
			free(object);
		}
		render(server);
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
		uint8_t status = handle_command(server, command, args, argc);
		uint8_t response = status;
		if (write(STDOUT_FILENO, &response, 1) < 0) {
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
