//メモリ上フレームバッファ＋PPMダンプ（開発・検証用）
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backend.h"

struct headless_data {
	char *dump_path;
};

static void headless_present(struct backend *backend) {
	struct headless_data *data = backend->data;
	FILE *fp = fopen(data->dump_path, "wb");
	if (fp == NULL) {
		return;
	}
	fprintf(fp, "P6\n%d %d\n255\n", backend->width, backend->height);
	for (int i = 0; i < backend->width * backend->height; i++) {
		uint32_t px = backend->pixels[i];
		fputc((px >> 16) & 0xFF, fp);
		fputc((px >> 8) & 0xFF, fp);
		fputc(px & 0xFF, fp);
	}
	fclose(fp);
}

static void headless_destroy(struct backend *backend) {
	struct headless_data *data = backend->data;
	free(data->dump_path);
	free(data);
	free(backend->pixels);
	free(backend);
}

struct backend *backend_headless_create(int width, int height, const char *dump_path) {
	struct backend *backend = calloc(1, sizeof(*backend));
	struct headless_data *data = calloc(1, sizeof(*data));
	backend->width = width;
	backend->height = height;
	backend->pixels = calloc((size_t)width * height, sizeof(uint32_t));
	data->dump_path = strdup(dump_path);
	backend->data = data;
	backend->present = headless_present;
	backend->destroy = headless_destroy;
	return backend;
}
