//DRM/KMSバックエンド: dumb bufferへソフトウェア描画し、
//ページフリップで表示する。実機（Pi）用。
//libdrmが無い環境ではスタブになる（MakefileがHAVE_DRMを定義したときだけ有効）。
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backend.h"

#ifdef HAVE_DRM

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <sys/mman.h>

#include <xf86drm.h>
#include <xf86drmMode.h>
#include <drm.h>
#include <drm_fourcc.h>

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
	uint32_t crtc_index;
	drmModeModeInfo mode;
	drmModeCrtc *saved_crtc;
	struct drm_buffer buffers[2];
	int draw_index;
	int first_present;
	int flip_pending;
	uint32_t *shadow;
	//ハードウェアプレーン探索の結果（初回create_plane時に調べる）
	int planes_checked;
	int planes_supported;
	uint32_t primary_zpos;
	uint32_t overlay_ids[8];
	int overlay_used[8];
	int overlay_count;
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
	//プライマリはARGB8888にする（ハードウェアプレーンの映像を透かすため）
	uint32_t handles[4] = {create.handle, 0, 0, 0};
	uint32_t pitches[4] = {create.pitch, 0, 0, 0};
	uint32_t offsets[4] = {0, 0, 0, 0};
	if (drmModeAddFB2(data->fd, create.width, create.height, DRM_FORMAT_ARGB8888, handles, pitches, offsets, &buffer->fb_id, 0) != 0) {
		fprintf(stderr, "rtv-display: drmModeAddFB2 failed\n");
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
	backend->kind = BACKEND_DRM;
	backend->present = drm_present;
	backend->destroy = drm_destroy;
	if (drm_buffer_create(data, &data->buffers[0]) != 0 || drm_buffer_create(data, &data->buffers[1]) != 0) {
		backend->destroy(backend);
		return NULL;
	}
	fprintf(stderr, "rtv-display: DRM %s mode %dx%d@%d\n", device, backend->width, backend->height, data->mode.vrefresh);
	return backend;
}

//--- ハードウェアプレーン ---------------------------------------------------
//オーバーレイプレーンをアトミックコミットで操作し、映像の合成と
//リサイズを表示コントローラ（HVS）にやらせる。
//プライマリ（UI）より下に置くため、zposをプライマリ未満に設定できる
//場合だけ使う。できなければNULLを返し、ソフトウェア描画に落ちる。

struct backend_plane {
	struct drm_data *data;
	int overlay_slot;
	uint32_t plane_id;
	struct drm_buffer buffers[2];
	uint32_t src_width;
	uint32_t src_height;
	int x;
	int y;
	int width;
	int height;
	uint32_t current_fb;
	uint64_t zpos_value;
	uint64_t zpos_min;
	uint64_t zpos_max;
	uint32_t prop_fb_id;
	uint32_t prop_crtc_id;
	uint32_t prop_src_x;
	uint32_t prop_src_y;
	uint32_t prop_src_w;
	uint32_t prop_src_h;
	uint32_t prop_crtc_x;
	uint32_t prop_crtc_y;
	uint32_t prop_crtc_w;
	uint32_t prop_crtc_h;
	uint32_t prop_zpos;
};

//オブジェクトのプロパティを探し、IDを返す。現在値や範囲も必要なら受け取る。
static uint32_t find_property(int fd, uint32_t object_id, uint32_t object_type, const char *name, uint64_t *value_out, uint64_t *min_out, uint64_t *max_out, int *immutable_out) {
	drmModeObjectProperties *props = drmModeObjectGetProperties(fd, object_id, object_type);
	if (props == NULL) {
		return 0;
	}
	uint32_t found = 0;
	for (uint32_t i = 0; i < props->count_props && found == 0; i++) {
		drmModePropertyRes *prop = drmModeGetProperty(fd, props->props[i]);
		if (prop == NULL) {
			continue;
		}
		if (strcmp(prop->name, name) == 0) {
			found = prop->prop_id;
			if (value_out != NULL) {
				*value_out = props->prop_values[i];
			}
			if (min_out != NULL) {
				*min_out = prop->values[0];
			}
			if (max_out != NULL) {
				*max_out = prop->values[1];
			}
			if (immutable_out != NULL) {
				*immutable_out = (prop->flags & DRM_MODE_PROP_IMMUTABLE) != 0;
			}
		}
		drmModeFreeProperty(prop);
	}
	drmModeFreeObjectProperties(props);
	return found;
}

static void discover_planes(struct drm_data *data) {
	if (data->planes_checked) {
		return;
	}
	data->planes_checked = 1;
	if (drmSetClientCap(data->fd, DRM_CLIENT_CAP_UNIVERSAL_PLANES, 1) != 0) {
		fprintf(stderr, "rtv-display: UNIVERSAL_PLANES not supported\n");
		return;
	}
	if (drmSetClientCap(data->fd, DRM_CLIENT_CAP_ATOMIC, 1) != 0) {
		fprintf(stderr, "rtv-display: ATOMIC not supported; hardware planes unavailable\n");
		return;
	}
	drmModeRes *resources = drmModeGetResources(data->fd);
	if (resources == NULL) {
		return;
	}
	for (int i = 0; i < resources->count_crtcs; i++) {
		if (resources->crtcs[i] == data->crtc_id) {
			data->crtc_index = (uint32_t)i;
			break;
		}
	}
	drmModeFreeResources(resources);
	drmModePlaneRes *plane_resources = drmModeGetPlaneResources(data->fd);
	if (plane_resources == NULL) {
		return;
	}
	int have_primary = 0;
	for (uint32_t i = 0; i < plane_resources->count_planes; i++) {
		uint32_t plane_id = plane_resources->planes[i];
		drmModePlane *plane = drmModeGetPlane(data->fd, plane_id);
		if (plane == NULL) {
			continue;
		}
		int usable = (plane->possible_crtcs & (1u << data->crtc_index)) != 0;
		drmModeFreePlane(plane);
		if (!usable) {
			continue;
		}
		uint64_t type = 0;
		if (find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "type", &type, NULL, NULL, NULL) == 0) {
			continue;
		}
		if (type == DRM_PLANE_TYPE_PRIMARY) {
			uint64_t zpos = 0;
			find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "zpos", &zpos, NULL, NULL, NULL);
			data->primary_zpos = (uint32_t)zpos;
			have_primary = 1;
		} else if (type == DRM_PLANE_TYPE_OVERLAY && data->overlay_count < 8) {
			data->overlay_ids[data->overlay_count] = plane_id;
			data->overlay_count++;
		}
	}
	drmModeFreePlaneResources(plane_resources);
	if (have_primary && data->overlay_count > 0) {
		data->planes_supported = 1;
		fprintf(stderr, "rtv-display: hardware planes available: %d overlay(s), primary zpos %u\n", data->overlay_count, data->primary_zpos);
	} else {
		fprintf(stderr, "rtv-display: no usable overlay plane found\n");
	}
}

static int drm_plane_buffer_create(struct drm_data *data, struct drm_buffer *buffer, uint32_t width, uint32_t height) {
	struct drm_mode_create_dumb create = {0};
	create.width = width;
	create.height = height;
	create.bpp = 32;
	if (drmIoctl(data->fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) != 0) {
		perror("rtv-display: DRM_IOCTL_MODE_CREATE_DUMB (plane)");
		return -1;
	}
	buffer->handle = create.handle;
	buffer->pitch = create.pitch;
	buffer->size = create.size;
	uint32_t handles[4] = {create.handle, 0, 0, 0};
	uint32_t pitches[4] = {create.pitch, 0, 0, 0};
	uint32_t offsets[4] = {0, 0, 0, 0};
	if (drmModeAddFB2(data->fd, width, height, DRM_FORMAT_XBGR8888, handles, pitches, offsets, &buffer->fb_id, 0) != 0) {
		fprintf(stderr, "rtv-display: drmModeAddFB2 (plane) failed\n");
		return -1;
	}
	struct drm_mode_map_dumb map = {0};
	map.handle = buffer->handle;
	if (drmIoctl(data->fd, DRM_IOCTL_MODE_MAP_DUMB, &map) != 0) {
		perror("rtv-display: DRM_IOCTL_MODE_MAP_DUMB (plane)");
		return -1;
	}
	buffer->map = mmap(NULL, buffer->size, PROT_READ | PROT_WRITE, MAP_SHARED, data->fd, map.offset);
	if (buffer->map == MAP_FAILED) {
		perror("rtv-display: mmap plane buffer");
		return -1;
	}
	memset(buffer->map, 0, buffer->size);
	return 0;
}

static int plane_commit(struct backend_plane *plane, uint32_t fb_id) {
	struct drm_data *data = plane->data;
	drmModeAtomicReq *req = drmModeAtomicAlloc();
	if (req == NULL) {
		return -1;
	}
	uint32_t id = plane->plane_id;
	drmModeAtomicAddProperty(req, id, plane->prop_fb_id, fb_id);
	drmModeAtomicAddProperty(req, id, plane->prop_crtc_id, fb_id != 0 ? data->crtc_id : 0);
	drmModeAtomicAddProperty(req, id, plane->prop_src_x, 0);
	drmModeAtomicAddProperty(req, id, plane->prop_src_y, 0);
	drmModeAtomicAddProperty(req, id, plane->prop_src_w, (uint64_t)plane->src_width << 16);
	drmModeAtomicAddProperty(req, id, plane->prop_src_h, (uint64_t)plane->src_height << 16);
	drmModeAtomicAddProperty(req, id, plane->prop_crtc_x, (uint64_t)(int64_t)plane->x);
	drmModeAtomicAddProperty(req, id, plane->prop_crtc_y, (uint64_t)(int64_t)plane->y);
	drmModeAtomicAddProperty(req, id, plane->prop_crtc_w, (uint32_t)plane->width);
	drmModeAtomicAddProperty(req, id, plane->prop_crtc_h, (uint32_t)plane->height);
	if (plane->prop_zpos != 0) {
		drmModeAtomicAddProperty(req, id, plane->prop_zpos, plane->zpos_value);
	}
	int result = drmModeAtomicCommit(data->fd, req, DRM_MODE_ATOMIC_NONBLOCK, NULL);
	if (result != 0) {
		fprintf(stderr, "rtv-display: plane atomic commit failed: %s\n", strerror(errno));
	} else {
		plane->current_fb = fb_id;
	}
	drmModeAtomicFree(req);
	return result;
}

struct backend_plane *backend_create_plane(struct backend *backend, uint32_t width, uint32_t height, uint8_t *pixels_out[2], uint32_t *stride_out) {
	if (backend->kind != BACKEND_DRM) {
		return NULL;
	}
	struct drm_data *data = backend->data;
	discover_planes(data);
	if (!data->planes_supported) {
		return NULL;
	}
	int slot = -1;
	for (int i = 0; i < data->overlay_count; i++) {
		if (!data->overlay_used[i]) {
			slot = i;
			break;
		}
	}
	if (slot < 0) {
		fprintf(stderr, "rtv-display: no free overlay plane\n");
		return NULL;
	}
	uint32_t plane_id = data->overlay_ids[slot];
	//zposでプライマリより下に置ける場合だけ使う（UIが映像に隠れないように）
	uint64_t zmin = 0;
	uint64_t zmax = 0;
	int immutable = 0;
	uint32_t zpos_id = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "zpos", NULL, &zmin, &zmax, &immutable);
	if (zpos_id == 0 || immutable || data->primary_zpos == 0 ||
		(uint64_t)(data->primary_zpos - 1) < zmin || (uint64_t)(data->primary_zpos - 1) > zmax) {
		fprintf(stderr, "rtv-display: overlay plane cannot go below the primary (zpos)\n");
		return NULL;
	}
	struct backend_plane *plane = calloc(1, sizeof(*plane));
	plane->data = data;
	plane->overlay_slot = slot;
	plane->plane_id = plane_id;
	plane->src_width = width;
	plane->src_height = height;
	plane->zpos_value = data->primary_zpos - 1;
	plane->zpos_min = zmin;
	plane->zpos_max = zmax;
	plane->prop_zpos = zpos_id;
	plane->prop_fb_id = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "FB_ID", NULL, NULL, NULL, NULL);
	plane->prop_crtc_id = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "CRTC_ID", NULL, NULL, NULL, NULL);
	plane->prop_src_x = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "SRC_X", NULL, NULL, NULL, NULL);
	plane->prop_src_y = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "SRC_Y", NULL, NULL, NULL, NULL);
	plane->prop_src_w = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "SRC_W", NULL, NULL, NULL, NULL);
	plane->prop_src_h = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "SRC_H", NULL, NULL, NULL, NULL);
	plane->prop_crtc_x = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "CRTC_X", NULL, NULL, NULL, NULL);
	plane->prop_crtc_y = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "CRTC_Y", NULL, NULL, NULL, NULL);
	plane->prop_crtc_w = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "CRTC_W", NULL, NULL, NULL, NULL);
	plane->prop_crtc_h = find_property(data->fd, plane_id, DRM_MODE_OBJECT_PLANE, "CRTC_H", NULL, NULL, NULL, NULL);
	if (plane->prop_fb_id == 0 || plane->prop_crtc_id == 0 || plane->prop_src_x == 0 || plane->prop_src_y == 0 ||
		plane->prop_src_w == 0 || plane->prop_src_h == 0 || plane->prop_crtc_x == 0 || plane->prop_crtc_y == 0 ||
		plane->prop_crtc_w == 0 || plane->prop_crtc_h == 0) {
		fprintf(stderr, "rtv-display: plane properties missing\n");
		free(plane);
		return NULL;
	}
	if (drm_plane_buffer_create(data, &plane->buffers[0], width, height) != 0 ||
		drm_plane_buffer_create(data, &plane->buffers[1], width, height) != 0) {
		drm_buffer_destroy(data, &plane->buffers[0]);
		drm_buffer_destroy(data, &plane->buffers[1]);
		free(plane);
		return NULL;
	}
	data->overlay_used[slot] = 1;
	pixels_out[0] = plane->buffers[0].map;
	pixels_out[1] = plane->buffers[1].map;
	*stride_out = plane->buffers[0].pitch;
	fprintf(stderr, "rtv-display: hardware plane %u assigned (source %ux%u, zpos %llu)\n",
		plane_id, width, height, (unsigned long long)plane->zpos_value);
	return plane;
}

int backend_plane_set_geometry(struct backend *backend, struct backend_plane *plane, int x, int y, int width, int height) {
	(void)backend;
	plane->x = x;
	plane->y = y;
	plane->width = width;
	plane->height = height;
	if (plane->current_fb != 0) {
		return plane_commit(plane, plane->current_fb);
	}
	return 0;
}

int backend_plane_set_layer(struct backend *backend, struct backend_plane *plane, int layer) {
	(void)backend;
	if (layer < 0) {
		return -1;
	}
	struct drm_data *data = plane->data;
	if (data->primary_zpos == 0) {
		return -1;
	}
	uint64_t value = (uint64_t)data->primary_zpos - 1 - (uint64_t)layer;
	if (value < plane->zpos_min || value > plane->zpos_max) {
		return -1;
	}
	if (value == plane->zpos_value) {
		return 0;
	}
	plane->zpos_value = value;
	if (plane->current_fb != 0) {
		return plane_commit(plane, plane->current_fb);
	}
	return 0;
}

int backend_plane_flip(struct backend *backend, struct backend_plane *plane, const uint8_t *pixels) {
	(void)backend;
	uint32_t fb_id = 0;
	if (pixels == (const uint8_t *)plane->buffers[0].map) {
		fb_id = plane->buffers[0].fb_id;
	} else if (pixels == (const uint8_t *)plane->buffers[1].map) {
		fb_id = plane->buffers[1].fb_id;
	}
	if (fb_id == 0) {
		return -1;
	}
	return plane_commit(plane, fb_id);
}

void backend_destroy_plane(struct backend *backend, struct backend_plane *plane) {
	(void)backend;
	if (plane == NULL) {
		return;
	}
	struct drm_data *data = plane->data;
	if (plane->current_fb != 0) {
		plane_commit(plane, 0);
	}
	drm_buffer_destroy(data, &plane->buffers[0]);
	drm_buffer_destroy(data, &plane->buffers[1]);
	data->overlay_used[plane->overlay_slot] = 0;
	free(plane);
}

#else

struct backend *backend_drm_create(const char *device, int request_width, int request_height) {
	(void)device;
	(void)request_width;
	(void)request_height;
	fprintf(stderr, "rtv-display: built without libdrm; DRM backend unavailable\n");
	return NULL;
}

struct backend_plane *backend_create_plane(struct backend *backend, uint32_t width, uint32_t height, uint8_t *pixels_out[2], uint32_t *stride_out) {
	(void)backend;
	(void)width;
	(void)height;
	(void)pixels_out;
	(void)stride_out;
	return NULL;
}

int backend_plane_set_geometry(struct backend *backend, struct backend_plane *plane, int x, int y, int width, int height) {
	(void)backend;
	(void)plane;
	(void)x;
	(void)y;
	(void)width;
	(void)height;
	return -1;
}

int backend_plane_set_layer(struct backend *backend, struct backend_plane *plane, int layer) {
	(void)backend;
	(void)plane;
	(void)layer;
	return -1;
}

int backend_plane_flip(struct backend *backend, struct backend_plane *plane, const uint8_t *pixels) {
	(void)backend;
	(void)plane;
	(void)pixels;
	return -1;
}

void backend_destroy_plane(struct backend *backend, struct backend_plane *plane) {
	(void)backend;
	(void)plane;
}

#endif
