//DRM/KMSバックエンド: dumb bufferへソフトウェア描画し、
//ページフリップで表示する。実機（Pi）用。
//libdrmが無い環境ではスタブになる（MakefileがHAVE_DRMを定義したときだけ有効）。
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backend.h"

#ifdef HAVE_DRM

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <sys/mman.h>

#include <xf86drm.h>
#include <xf86drmMode.h>
#include <drm.h>

struct drm_buffer {
	uint32_t handle;
	uint32_t fb_id;
	uint32_t pitch;
	uint64_t size;
	void *map;
};

struct drm_data {
	int fd;
	uint32_t connector_id;
	uint32_t crtc_id;
	drmModeModeInfo mode;
	drmModeCrtc *saved_crtc;
	struct drm_buffer buffers[2];
	int draw_index;
	int first_present;
	int flip_pending;
	uint32_t *shadow;
};

static void flip_handler(int fd, unsigned int sequence, unsigned int tv_sec, unsigned int tv_usec, void *user_data) {
	int *pending = user_data;
	*pending = 0;
}

static int drm_buffer_create(struct drm_data *data, struct drm_buffer *buffer) {
	struct drm_mode_create_dumb create = {0};
	create.width = data->mode.hdisplay;
	create.height = data->mode.vdisplay;
	create.bpp = 32;
	if (drmIoctl(data->fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) != 0) {
		perror("rtv-display: DRM_IOCTL_MODE_CREATE_DUMB");
		return -1;
	}
	buffer->handle = create.handle;
	buffer->pitch = create.pitch;
	buffer->size = create.size;
	if (drmModeAddFB(data->fd, create.width, create.height, 24, 32, buffer->pitch, buffer->handle, &buffer->fb_id) != 0) {
		fprintf(stderr, "rtv-display: drmModeAddFB failed\n");
		return -1;
	}
	struct drm_mode_map_dumb map = {0};
	map.handle = buffer->handle;
	if (drmIoctl(data->fd, DRM_IOCTL_MODE_MAP_DUMB, &map) != 0) {
		perror("rtv-display: DRM_IOCTL_MODE_MAP_DUMB");
		return -1;
	}
	buffer->map = mmap(NULL, buffer->size, PROT_READ | PROT_WRITE, MAP_SHARED, data->fd, map.offset);
	if (buffer->map == MAP_FAILED) {
		perror("rtv-display: mmap dumb buffer");
		return -1;
	}
	memset(buffer->map, 0, buffer->size);
	return 0;
}

static void drm_buffer_destroy(struct drm_data *data, struct drm_buffer *buffer) {
	if (buffer->map != NULL) {
		munmap(buffer->map, buffer->size);
	}
	if (buffer->fb_id != 0) {
		drmModeRmFB(data->fd, buffer->fb_id);
	}
	if (buffer->handle != 0) {
		struct drm_mode_destroy_dumb destroy = {0};
		destroy.handle = buffer->handle;
		drmIoctl(data->fd, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy);
	}
}

static void drm_present(struct backend *backend) {
	struct drm_data *data = backend->data;
	struct drm_buffer *buffer = &data->buffers[data->draw_index];
	//シャドウ（密なストライド）からdumb buffer（pitch付き）へコピー
	for (int y = 0; y < backend->height; y++) {
		memcpy((uint8_t *)buffer->map + (size_t)y * buffer->pitch, data->shadow + (size_t)y * backend->width, (size_t)backend->width * 4);
	}
	if (data->first_present) {
		if (drmModeSetCrtc(data->fd, data->crtc_id, buffer->fb_id, 0, 0, &data->connector_id, 1, &data->mode) != 0) {
			perror("rtv-display: drmModeSetCrtc");
			return;
		}
		data->first_present = 0;
	} else {
		data->flip_pending = 1;
		if (drmModePageFlip(data->fd, data->crtc_id, buffer->fb_id, DRM_MODE_PAGE_FLIP_EVENT, &data->flip_pending) != 0) {
			//フリップできない場合はSetCrtcで代替
			data->flip_pending = 0;
			drmModeSetCrtc(data->fd, data->crtc_id, buffer->fb_id, 0, 0, &data->connector_id, 1, &data->mode);
		} else {
			drmEventContext context = {0};
			context.version = DRM_EVENT_CONTEXT_VERSION;
			context.page_flip_handler = flip_handler;
			struct pollfd pfd = {.fd = data->fd, .events = POLLIN};
			while (data->flip_pending) {
				if (poll(&pfd, 1, 1000) <= 0) {
					fprintf(stderr, "rtv-display: page flip timeout\n");
					data->flip_pending = 0;
					break;
				}
				drmHandleEvent(data->fd, &context);
			}
		}
	}
	data->draw_index ^= 1;
}

static void drm_destroy(struct backend *backend) {
	struct drm_data *data = backend->data;
	if (data->saved_crtc != NULL) {
		drmModeSetCrtc(data->fd, data->saved_crtc->crtc_id, data->saved_crtc->buffer_id, data->saved_crtc->x, data->saved_crtc->y, &data->connector_id, 1, &data->saved_crtc->mode);
		drmModeFreeCrtc(data->saved_crtc);
	}
	drm_buffer_destroy(data, &data->buffers[0]);
	drm_buffer_destroy(data, &data->buffers[1]);
	close(data->fd);
	free(data->shadow);
	free(data);
	free(backend);
}

struct backend *backend_drm_create(const char *device, int request_width, int request_height) {
	struct drm_data *data = calloc(1, sizeof(*data));
	data->fd = open(device, O_RDWR | O_CLOEXEC);
	if (data->fd < 0) {
		perror("rtv-display: open DRM device");
		free(data);
		return NULL;
	}
	drmModeRes *resources = drmModeGetResources(data->fd);
	if (resources == NULL) {
		fprintf(stderr, "rtv-display: drmModeGetResources failed\n");
		close(data->fd);
		free(data);
		return NULL;
	}
	//接続中のコネクタとモードを選ぶ
	drmModeConnector *connector = NULL;
	for (int i = 0; i < resources->count_connectors; i++) {
		drmModeConnector *candidate = drmModeGetConnector(data->fd, resources->connectors[i]);
		if (candidate != NULL && candidate->connection == DRM_MODE_CONNECTED && candidate->count_modes > 0) {
			connector = candidate;
			break;
		}
		if (candidate != NULL) {
			drmModeFreeConnector(candidate);
		}
	}
	if (connector == NULL) {
		fprintf(stderr, "rtv-display: no connected connector\n");
		drmModeFreeResources(resources);
		close(data->fd);
		free(data);
		return NULL;
	}
	data->connector_id = connector->connector_id;
	data->mode = connector->modes[0];
	for (int i = 0; i < connector->count_modes; i++) {
		if (connector->modes[i].hdisplay == request_width && connector->modes[i].vdisplay == request_height) {
			data->mode = connector->modes[i];
			break;
		}
	}
	//CRTCを探す
	drmModeEncoder *encoder = NULL;
	if (connector->encoder_id != 0) {
		encoder = drmModeGetEncoder(data->fd, connector->encoder_id);
	}
	if (encoder != NULL && encoder->crtc_id != 0) {
		data->crtc_id = encoder->crtc_id;
	} else {
		for (int i = 0; i < connector->count_encoders && data->crtc_id == 0; i++) {
			drmModeEncoder *candidate = drmModeGetEncoder(data->fd, connector->encoders[i]);
			if (candidate == NULL) {
				continue;
			}
			for (int j = 0; j < resources->count_crtcs; j++) {
				if (candidate->possible_crtcs & (1 << j)) {
					data->crtc_id = resources->crtcs[j];
					break;
				}
			}
			drmModeFreeEncoder(candidate);
		}
	}
	if (encoder != NULL) {
		drmModeFreeEncoder(encoder);
	}
	drmModeFreeConnector(connector);
	drmModeFreeResources(resources);
	if (data->crtc_id == 0) {
		fprintf(stderr, "rtv-display: no CRTC found\n");
		close(data->fd);
		free(data);
		return NULL;
	}
	data->saved_crtc = drmModeGetCrtc(data->fd, data->crtc_id);
	data->first_present = 1;

	struct backend *backend = calloc(1, sizeof(*backend));
	backend->width = data->mode.hdisplay;
	backend->height = data->mode.vdisplay;
	data->shadow = calloc((size_t)backend->width * backend->height, sizeof(uint32_t));
	backend->pixels = data->shadow;
	backend->data = data;
	backend->present = drm_present;
	backend->destroy = drm_destroy;
	if (drm_buffer_create(data, &data->buffers[0]) != 0 || drm_buffer_create(data, &data->buffers[1]) != 0) {
		backend->destroy(backend);
		return NULL;
	}
	fprintf(stderr, "rtv-display: DRM %s mode %dx%d@%d\n", device, backend->width, backend->height, data->mode.vrefresh);
	return backend;
}

#else

struct backend *backend_drm_create(const char *device, int request_width, int request_height) {
	(void)device;
	(void)request_width;
	(void)request_height;
	fprintf(stderr, "rtv-display: built without libdrm; DRM backend unavailable\n");
	return NULL;
}

#endif
