//rtv-test-client: コンポジタの動作確認用クライアント。
//xdg_toplevelを作り、縦グラデーション＋中央の赤い矩形を描いてcommitする。
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <wayland-client.h>

#include "xdg-shell-client-protocol.h"

static struct wl_compositor *compositor;
static struct wl_shm *shm;
static struct xdg_wm_base *wm_base;
static int configured_width = 1280;
static int configured_height = 720;
static int configured = 0;

static void registry_global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
	if (strcmp(interface, wl_compositor_interface.name) == 0) {
		compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
	} else if (strcmp(interface, wl_shm_interface.name) == 0) {
		shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
	} else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
		wm_base = wl_registry_bind(registry, name, &xdg_wm_base_interface, 2);
	}
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
}

static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_global_remove,
};

static void xdg_surface_configure(void *data, struct xdg_surface *xdg_surface, uint32_t serial) {
	xdg_surface_ack_configure(xdg_surface, serial);
	configured = 1;
}

static const struct xdg_surface_listener xdg_surface_listener = {
	.configure = xdg_surface_configure,
};

static void toplevel_configure(void *data, struct xdg_toplevel *toplevel, int32_t width, int32_t height, struct wl_array *states) {
	if (width > 0) {
		configured_width = width;
	}
	if (height > 0) {
		configured_height = height;
	}
}

static void toplevel_close(void *data, struct xdg_toplevel *toplevel) {
}

static const struct xdg_toplevel_listener toplevel_listener = {
	.configure = toplevel_configure,
	.close = toplevel_close,
};

static void paint(uint32_t *pixels, int width, int height) {
	for (int y = 0; y < height; y++) {
		uint32_t g = (uint32_t)(y * 255 / height);
		for (int x = 0; x < width; x++) {
			pixels[y * width + x] = 0xFF000000 | (40 << 16) | (g << 8) | 160;
		}
	}
	//中央の矩形（検証用に目立つ色）
	for (int y = height / 4; y < height * 3 / 4; y++) {
		for (int x = width / 4; x < width * 3 / 4; x++) {
			pixels[y * width + x] = 0xFFFF3B30;
		}
	}
}

int main(void) {
	struct wl_display *display = wl_display_connect(NULL);
	if (display == NULL) {
		fprintf(stderr, "rtv-test-client: failed to connect\n");
		return 1;
	}
	struct wl_registry *registry = wl_display_get_registry(display);
	wl_registry_add_listener(registry, &registry_listener, NULL);
	wl_display_roundtrip(display);
	if (compositor == NULL || shm == NULL || wm_base == NULL) {
		fprintf(stderr, "rtv-test-client: missing globals (compositor=%p shm=%p wm_base=%p)\n", (void *)compositor, (void *)shm, (void *)wm_base);
		return 1;
	}
	struct wl_surface *surface = wl_compositor_create_surface(compositor);
	struct xdg_surface *xdg_surface = xdg_wm_base_get_xdg_surface(wm_base, surface);
	xdg_surface_add_listener(xdg_surface, &xdg_surface_listener, NULL);
	struct xdg_toplevel *toplevel = xdg_surface_get_toplevel(xdg_surface);
	xdg_toplevel_add_listener(toplevel, &toplevel_listener, NULL);
	xdg_toplevel_set_title(toplevel, "rtv-test-client");
	wl_surface_commit(surface);
	while (!configured) {
		wl_display_dispatch(display);
	}

	int width = configured_width;
	int height = configured_height;
	int stride = width * 4;
	int size = stride * height;
	int fd = memfd_create("rtv-test-buffer", 0);
	if (fd < 0 || ftruncate(fd, size) != 0) {
		fprintf(stderr, "rtv-test-client: failed to create shm buffer\n");
		return 1;
	}
	uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	paint(pixels, width, height);
	struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, size);
	struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, stride, WL_SHM_FORMAT_XRGB8888);
	wl_surface_attach(surface, buffer, 0, 0);
	wl_surface_damage_buffer(surface, 0, 0, width, height);
	wl_surface_commit(surface);
	wl_display_roundtrip(display);
	fprintf(stderr, "rtv-test-client: painted %dx%d\n", width, height);
	return 0;
}
