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

#include <wayland-server.h>

#include "backend.h"
#include "xdg-shell-protocol.h"

#define BACKGROUND_COLOR 0xFF20242A
#define WINDOW_WIDTH_FRACTION 2
#define WINDOW_HEIGHT_FRACTION 2

struct server {
	struct wl_display *display;
	struct backend *backend;
	struct wl_list surfaces;
	uint32_t configure_serial;
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
	wl_output_send_mode(resource, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, server->backend->width, server->backend->height, 60000);
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
	int win_w = server->backend->width * WINDOW_WIDTH_FRACTION / 3;
	int win_h = server->backend->height * WINDOW_HEIGHT_FRACTION / 3;
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
	server.display = wl_display_create();
	if (server.display == NULL) {
		fprintf(stderr, "rtv-display: wl_display_create failed\n");
		return 1;
	}
	if (strcmp(backend_name, "headless") == 0) {
		server.backend = backend_headless_create(width, height, dump_path);
	} else if (strcmp(backend_name, "drm") == 0) {
		server.backend = backend_drm_create(drm_device, width, height);
	} else if (strcmp(backend_name, "window") == 0) {
		server.backend = backend_window_create(server.display, width, height);
	} else {
		fprintf(stderr, "rtv-display: unknown backend '%s' (headless|drm|window)\n", backend_name);
		return 1;
	}
	if (server.backend == NULL) {
		fprintf(stderr, "rtv-display: backend '%s' failed to start\n", backend_name);
		return 1;
	}
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
	render(&server);
	fprintf(stderr, "rtv-display: listening on %s (%dx%d, backend %s)\n", name, server.backend->width, server.backend->height, backend_name);
	wl_display_run(server.display);
	wl_display_destroy(server.display);
	server.backend->destroy(server.backend);
	return 0;
}
