//ウィンドウバックエンド（デバッグ用）: 親コンポジタのクライアントになり、
//自分のフレームバッファをウィンドウに縮小表示する。
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <sys/mman.h>
#include <unistd.h>

#include <wayland-client.h>
#include <wayland-server.h>

#include "backend.h"
#include "xdg-shell-client-protocol.h"

#define MAX_WINDOW_WIDTH 1280
#define MAX_WINDOW_HEIGHT 720

struct window_buffer {
	struct wl_buffer *buffer;
	uint32_t *pixels;
	int width;
	int height;
	int busy;
};

struct window_data {
	struct wl_display *parent;
	struct wl_compositor *compositor;
	struct wl_shm *shm;
	struct xdg_wm_base *wm_base;
	struct wl_surface *surface;
	struct xdg_surface *xdg_surface;
	struct wl_event_source *source;
	struct window_buffer buffers[2];
	int window_width;
	int window_height;
	int configured;
};

static void buffer_release(void *data, struct wl_buffer *buffer) {
	struct window_buffer *window_buffer = data;
	window_buffer->busy = 0;
}

static const struct wl_buffer_listener buffer_listener = {
	.release = buffer_release,
};

static void registry_global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
	struct window_data *window = data;
	if (strcmp(interface, wl_compositor_interface.name) == 0) {
		window->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
	} else if (strcmp(interface, wl_shm_interface.name) == 0) {
		window->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
	} else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
		window->wm_base = wl_registry_bind(registry, name, &xdg_wm_base_interface, 2);
	}
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
}

static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_global_remove,
};

static void xdg_surface_configure(void *data, struct xdg_surface *xdg_surface, uint32_t serial) {
	struct window_data *window = data;
	xdg_surface_ack_configure(xdg_surface, serial);
	window->configured = 1;
}

static const struct xdg_surface_listener xdg_surface_listener = {
	.configure = xdg_surface_configure,
};

static void toplevel_configure(void *data, struct xdg_toplevel *toplevel, int32_t width, int32_t height, struct wl_array *states) {
	struct window_data *window = data;
	if (width > 0) {
		window->window_width = width;
	}
	if (height > 0) {
		window->window_height = height;
	}
}

static void toplevel_close(void *data, struct xdg_toplevel *toplevel) {
	fprintf(stderr, "rtv-display: debug window closed by parent (continuing headless)\n");
}

static const struct xdg_toplevel_listener toplevel_listener = {
	.configure = toplevel_configure,
	.close = toplevel_close,
};

//親ディスプレイのfdが読めるようになったらイベントを処理し、次の読みに備える
static int parent_fd_callback(int fd, uint32_t mask, void *data) {
	struct window_data *window = data;
	if (wl_display_read_events(window->parent) != 0) {
		fprintf(stderr, "rtv-display: parent display read failed\n");
		return 0;
	}
	wl_display_dispatch_pending(window->parent);
	while (wl_display_prepare_read(window->parent) != 0) {
		wl_display_dispatch_pending(window->parent);
	}
	wl_display_flush(window->parent);
	return 0;
}

static int window_buffer_resize(struct window_data *window, struct window_buffer *buffer, int width, int height) {
	if (buffer->buffer != NULL && buffer->width == width && buffer->height == height) {
		return 0;
	}
	if (buffer->buffer != NULL) {
		wl_buffer_destroy(buffer->buffer);
		munmap(buffer->pixels, (size_t)buffer->width * buffer->height * 4);
		buffer->buffer = NULL;
	}
	int size = width * height * 4;
	int fd = memfd_create("rtv-window-buffer", 0);
	if (fd < 0 || ftruncate(fd, size) != 0) {
		perror("rtv-display: memfd_create");
		if (fd >= 0) {
			close(fd);
		}
		return -1;
	}
	buffer->pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	struct wl_shm_pool *pool = wl_shm_create_pool(window->shm, fd, size);
	buffer->buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * 4, WL_SHM_FORMAT_XRGB8888);
	wl_buffer_add_listener(buffer->buffer, &buffer_listener, buffer);
	wl_shm_pool_destroy(pool);
	close(fd);
	buffer->width = width;
	buffer->height = height;
	buffer->busy = 0;
	return 0;
}

//親のイベントを1回分処理する（バッファのrelease回収用）。
//イベントループへ戻るときは「読みの用意済み」状態を保つこと。
static void window_pump_events(struct window_data *window, int timeout_ms) {
	wl_display_cancel_read(window->parent);
	while (wl_display_prepare_read(window->parent) != 0) {
		wl_display_dispatch_pending(window->parent);
	}
	wl_display_flush(window->parent);
	struct pollfd pfd = {.fd = wl_display_get_fd(window->parent), .events = POLLIN};
	if (poll(&pfd, 1, timeout_ms) > 0) {
		wl_display_read_events(window->parent);
		wl_display_dispatch_pending(window->parent);
	} else {
		wl_display_cancel_read(window->parent);
	}
	while (wl_display_prepare_read(window->parent) != 0) {
		wl_display_dispatch_pending(window->parent);
	}
	wl_display_flush(window->parent);
}

static void window_present(struct backend *backend) {
	struct window_data *window = backend->data;
	//デバッグ用: 環境変数RTV_DEBUG_FB_DUMPにパスがあれば自フレームバッファもPPMで出す
	const char *debug_dump = getenv("RTV_DEBUG_FB_DUMP");
	if (debug_dump != NULL) {
		FILE *fp = fopen(debug_dump, "wb");
		if (fp != NULL) {
			fprintf(fp, "P6\n%d %d\n255\n", backend->width, backend->height);
			for (int i = 0; i < backend->width * backend->height; i++) {
				uint32_t px = backend->pixels[i];
				fputc((px >> 16) & 0xFF, fp);
				fputc((px >> 8) & 0xFF, fp);
				fputc(px & 0xFF, fp);
			}
			fclose(fp);
		}
	}
	struct window_buffer *buffer = NULL;
	for (int i = 0; i < 2; i++) {
		if (!window->buffers[i].busy) {
			buffer = &window->buffers[i];
			break;
		}
	}
	if (buffer == NULL) {
		//両方使用中なら、親が返したreleaseを回収してから再試行する
		window_pump_events(window, 100);
		for (int i = 0; i < 2; i++) {
			if (!window->buffers[i].busy) {
				buffer = &window->buffers[i];
				break;
			}
		}
	}
	if (buffer == NULL) {
		//それでも空かなければこのフレームは捨てる
		return;
	}
	if (window_buffer_resize(window, buffer, window->window_width, window->window_height) != 0) {
		return;
	}
	//フレームバッファをウィンドウサイズへ最近傍で縮小
	for (int y = 0; y < window->window_height; y++) {
		int src_y = y * backend->height / window->window_height;
		for (int x = 0; x < window->window_width; x++) {
			int src_x = x * backend->width / window->window_width;
			buffer->pixels[y * window->window_width + x] = backend->pixels[src_y * backend->width + src_x];
		}
	}
	buffer->busy = 1;
	wl_surface_attach(window->surface, buffer->buffer, 0, 0);
	wl_surface_damage_buffer(window->surface, 0, 0, window->window_width, window->window_height);
	wl_surface_commit(window->surface);
	wl_display_flush(window->parent);
}

static void window_destroy(struct backend *backend) {
	struct window_data *window = backend->data;
	if (window->source != NULL) {
		wl_event_source_remove(window->source);
	}
	wl_display_cancel_read(window->parent);
	for (int i = 0; i < 2; i++) {
		if (window->buffers[i].buffer != NULL) {
			wl_buffer_destroy(window->buffers[i].buffer);
			munmap(window->buffers[i].pixels, (size_t)window->buffers[i].width * window->buffers[i].height * 4);
		}
	}
	wl_surface_destroy(window->surface);
	wl_display_disconnect(window->parent);
	free(window);
	free(backend->pixels);
	free(backend);
}

struct backend *backend_window_create(struct wl_display *server_display, int width, int height) {
	struct window_data *window = calloc(1, sizeof(*window));
	window->parent = wl_display_connect(NULL);
	if (window->parent == NULL) {
		fprintf(stderr, "rtv-display: failed to connect to parent compositor (WAYLAND_DISPLAY)\n");
		free(window);
		return NULL;
	}
	struct wl_registry *registry = wl_display_get_registry(window->parent);
	wl_registry_add_listener(registry, &registry_listener, window);
	wl_display_roundtrip(window->parent);
	wl_registry_destroy(registry);
	if (window->compositor == NULL || window->shm == NULL || window->wm_base == NULL) {
		fprintf(stderr, "rtv-display: parent compositor lacks required globals\n");
		wl_display_disconnect(window->parent);
		free(window);
		return NULL;
	}
	window->window_width = width < MAX_WINDOW_WIDTH ? width : MAX_WINDOW_WIDTH;
	window->window_height = height < MAX_WINDOW_HEIGHT ? height : MAX_WINDOW_HEIGHT;
	window->surface = wl_compositor_create_surface(window->compositor);
	window->xdg_surface = xdg_wm_base_get_xdg_surface(window->wm_base, window->surface);
	xdg_surface_add_listener(window->xdg_surface, &xdg_surface_listener, window);
	struct xdg_toplevel *toplevel = xdg_surface_get_toplevel(window->xdg_surface);
	xdg_toplevel_add_listener(toplevel, &toplevel_listener, window);
	xdg_toplevel_set_title(toplevel, "rtv-display");
	wl_surface_commit(window->surface);
	while (!window->configured) {
		wl_display_dispatch(window->parent);
	}
	//サーバーのイベントループへ親ディスプレイを組み込む
	struct wl_event_loop *loop = wl_display_get_event_loop(server_display);
	while (wl_display_prepare_read(window->parent) != 0) {
		wl_display_dispatch_pending(window->parent);
	}
	window->source = wl_event_loop_add_fd(loop, wl_display_get_fd(window->parent), WL_EVENT_READABLE, parent_fd_callback, window);
	wl_display_flush(window->parent);

	struct backend *backend = calloc(1, sizeof(*backend));
	backend->width = width;
	backend->height = height;
	backend->pixels = calloc((size_t)width * height, sizeof(uint32_t));
	backend->data = window;
	backend->present = window_present;
	backend->destroy = window_destroy;
	backend->kind = BACKEND_WINDOW;
	fprintf(stderr, "rtv-display: debug window %dx%d on parent compositor\n", window->window_width, window->window_height);
	return backend;
}
