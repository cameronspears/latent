/*
 * Host test for fl_develop_uyvy: a JPEG is decoded into a UYVY buffer laid out like Sony device
 * memory (padded canvas, data offset), developed through the row callback, and written back out,
 * so it can be compared with fl_process_file's output for the same settings.
 *   uyvytest IN.JPG OUT.JPG LUT
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "filmcore.h"
#include "jpeglib.h"

typedef struct { unsigned char *mem; int canvas_w, offset, w; long reads, writes; } fake;

static int io(void *ctx, int y, unsigned char *row, int write) {
    fake *f = (fake *)ctx;
    unsigned char *at = f->mem + f->offset + (size_t)y * f->canvas_w * 2;
    if (write) { memcpy(at, row, (size_t)f->w * 2); f->writes++; }
    else { memcpy(row, at, (size_t)f->w * 2); f->reads++; }
    return 0;
}

int main(int argc, char **argv) {
    struct jpeg_decompress_struct d;
    struct jpeg_compress_struct c;
    struct jpeg_error_mgr e1, e2;
    FILE *in, *out;
    fake f;
    unsigned char *rgb;
    int params[FL_PARAM_COUNT] = { -2, 1, 6, 2, 95, 7, 1 }, y, w, h;
    char err[256] = "";
    if (argc < 4) return 2;
    if (argc > 4) params[FL_PARAM_GRAIN] = atoi(argv[4]);
    in = fopen(argv[1], "rb");
    d.err = jpeg_std_error(&e1);
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, in);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    jpeg_start_decompress(&d);
    w = d.output_width & ~1; h = d.output_height;
    f.w = w; f.canvas_w = w + 16; f.offset = 64; f.reads = f.writes = 0;   /* padded like Sony's canvases */
    f.mem = calloc((size_t)f.offset + (size_t)f.canvas_w * 2 * h, 1);
    rgb = malloc((size_t)d.output_width * 3);
    while (d.output_scanline < d.output_height) {
        JSAMPROW r = rgb;
        y = d.output_scanline;
        jpeg_read_scanlines(&d, &r, 1);
        fl_rgb_to_uyvy(rgb, f.mem + f.offset + (size_t)y * f.canvas_w * 2, w);
    }
    jpeg_finish_decompress(&d);
    fclose(in);
    if (fl_develop_uyvy(argv[3], params, w, h, io, &f, NULL, NULL, err, sizeof err) != 0) { fprintf(stderr, "error: %s\n", err); return 1; }
    out = fopen(argv[2], "wb");
    c.err = jpeg_std_error(&e2);
    jpeg_create_compress(&c);
    jpeg_stdio_dest(&c, out);
    c.image_width = w; c.image_height = h; c.input_components = 3; c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, 95, TRUE);
    jpeg_start_compress(&c, TRUE);
    for (y = 0; y < h; y++) {
        JSAMPROW r = rgb;
        fl_uyvy_to_rgb(f.mem + f.offset + (size_t)y * f.canvas_w * 2, rgb, w);
        jpeg_write_scanlines(&c, &r, 1);
    }
    jpeg_finish_compress(&c);
    fclose(out);
    {
        int t[FL_TIME_COUNT];
        fl_last_timing(t);
        fprintf(stderr, "rows read %ld written %ld; ms: io-read %d look %d io-write %d total %d\n", f.reads, f.writes, t[0], t[1], t[2], t[4]);
    }
    return 0;
}
