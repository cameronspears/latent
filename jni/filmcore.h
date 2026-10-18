/*
 * Film Lab image core: tone curve + 3D LUT + film grain, streamed through libjpeg one strip at a
 * time so a 24 MP frame never has to fit in memory. Plain C89-ish, integer maths in the hot loop
 * (the camera's armeabi build has no hardware float ABI). Shared by the camera (via jni_bridge.c)
 * and the desktop test tool (tools/fltool.c).
 */
#ifndef FILMCORE_H
#define FILMCORE_H

#define FL_PARAM_HIGHLIGHT 0   /* -4 .. +4, Fuji-style: + brighter highlights        */
#define FL_PARAM_SHADOW    1   /* -4 .. +4, Fuji-style: + deeper shadows             */
#define FL_PARAM_GRAIN     2   /*  0 .. 10, strength                                 */
#define FL_PARAM_GRAINSIZE 3   /*  1 .. 3                                            */
#define FL_PARAM_QUALITY   4   /* 50 .. 100, output JPEG quality                     */
#define FL_PARAM_SEED      5   /* any int: varies the grain pattern between frames   */
#define FL_PARAM_THREADS   6   /* 1 = single-threaded; 2+ = decode/look/encode pipeline */
#define FL_PARAM_COUNT     7

#define FL_TIME_READ   0   /* ms decoding                                   */
#define FL_TIME_LOOK   1   /* ms applying tone + LUT + grain + thumbnail    */
#define FL_TIME_WRITE  2   /* ms encoding and writing                       */
#define FL_TIME_FINISH 3   /* ms thumbnail patch, flush, close, rename      */
#define FL_TIME_TOTAL  4   /* wall time                                     */
#define FL_TIME_CPU    5   /* CPU time summed over every thread             */
#define FL_TIME_THREADS 6  /* threads the look ran on (block develop)       */
#define FL_TIME_WAIT   7   /* block develop: ms this thread waited for the helpers after its share */
#define FL_TIME_ROWS   8   /* block develop: rows this thread looked itself (the rest: helpers)    */
#define FL_TIME_HCPU   9   /* block develop: CPU ms of the helpers together                        */
#define FL_TIME_SETUP  10  /* block develop: ms before the first block (look, grain, tables, threads) */
#define FL_TIME_WARM   11  /* block develop: 1 if its look's tables were already made (fl_prewarm, or the last develop) */
#define FL_TIME_COUNT  12

typedef void (*fl_progress_fn)(void *ctx, int percent);

/* Process a baseline JPEG file into a new JPEG file. lut_path may be NULL or "" for no LUT;
 * preview_path, if given, also gets a screen-size JPEG of the result.
 * Writes out_path + ".TMP" first, then renames, so a crash never leaves a half file under the
 * real name. Returns 0 on success; on failure fills err. */
int fl_process_file(const char *in_path, const char *out_path, const char *lut_path,
                    const char *preview_path, const int *params, fl_progress_fn progress, void *ctx,
                    char *err, int errlen);

/* A screen-size (fits 640x480) JPEG preview of an existing JPEG. preview_path in fl_process_file
 * makes the same thing for free during developing. 0 on success. */
int fl_make_preview(const char *in_path, const char *out_path, char *err, int errlen);

/* Phase timings (FL_TIME_*) of the last successful fl_process_file call, in milliseconds. */
void fl_last_timing(int *out);

/* Preview into 0xAARRGGBB pixels (Android ARGB_8888 order).
 * mode 0: whole frame (from the embedded preview when the file has one), no grain, fitted
 *         inside max_w x max_h.
 * mode 1: 100% crop of max_w x max_h around the frame centre, with grain.
 * On success writes the produced size to out_wh[0], out_wh[1] and returns 0. */
int fl_preview(const char *in_path, const char *lut_path, const int *params, int mode,
               unsigned int *argb, int max_w, int max_h, int *out_wh, char *err, int errlen);

/* The viewfinder: one live frame (a small JPEG from the camera) decoded in memory, fitted inside
 * max_w x max_h, with the look (no grain) into argb. info[FL_LIVE_INFO]: 0, 1 the size produced;
 * 2..4 the level (fl_level, on the picture before the look); 5..68 a 64-bin brightness histogram. */
#define FL_LIVE_INFO 69
int fl_live(const unsigned char *jpeg, unsigned long len, const char *lut_path, const int *params,
            unsigned int *argb, int max_w, int max_h, int *info, char *err, int errlen);

/* How far from level the picture is, from its straight edges (the camera has no tilt sensor).
 * out[0]: the angle of the dominant horizontal / vertical edges in 1/100 degree, positive when they
 * rise to the right (the camera is turned clockwise); out[1]: confidence 0..100 (the share of edge
 * weight agreeing within a degree); out[2]: how many 8x8 blocks agree. 0 on success. */
int fl_level(const unsigned char *rgb, int w, int h, int *out);

/* A live frame (argb, w x h) for a 16-bit screen: cropped from the centre to aspect1000/1000 (width over
 * height; 0 = ow:oh), stretched to ow x oh (bilinear: the screen's pixels needn't be square), and
 * ordered-dithered (4x4 Bayer) down to 4 bits a channel, as 16-bit pixels with each channel's nibble at
 * shifts[0..3] (red, green, blue, alpha; fully opaque). Returns 0, or -1 on bad sizes. */
int fl_to4444(const unsigned int *argb, int w, int h, unsigned short *out, int ow, int oh, int aspect1000, const int *shifts);

/* The viewfinder's frame composer (v0.38.0; one pass a screen row since v0.45.0): one live JPEG to the screen's
 * 16-bit picture in one go. A context per thread (its buffers and film). fl_frame decodes (fitted inside
 * max_w x max_h) as Y'CbCr, or as RGB when the level is asked (FL_F_LEVEL reads RGB) or there is no film, writes
 * the frame's signature (FL_FI_SIG) and stops there when FL_F_STILL finds the scene still (returns 1, nothing
 * written), applies the look (a table indexed by Y'CbCr, or the RGB one), reads the histogram of the looked
 * picture (FL_F_HIST: 64 bins, every other pixel of every other row), then for each of ow x oh screen pixels
 * (rows stride pixels apart): bilinear stretch of the centre aspect1000 crop, the overlay (fl_set_overlay)
 * where it shows (opaque pixels at the nearest 4-bit colour, solid), blue-noise dither to 4 bits a channel
 * (moving with seq when FL_F_TEMPORAL), packed as format: FL_FMT_565 (opaque RGB565 holding the 4-bit values
 * exactly) or FL_FMT_4444 (nibbles at shifts[0..3]). jpeg NULL: the last frame again (the overlay changed;
 * FL_F_STILL ignored). info[FL_FRAME_INFO]: 0, 1 frame size; 2..4 level (FL_NO_READING when not read); 5..68
 * histogram; FL_FI_OVERLAY the overlay generation laid in (0: none); FL_FI_SIG.. the signature.
 * tm[FL_FRAME_TIMES]: ms per stage. 0, 1 (still), or -1. */
typedef struct fl_frame_ctx fl_frame_ctx;
#define FL_F_LEVEL 1
#define FL_F_HIST 2
#define FL_F_TEMPORAL 4
#define FL_F_STILL 8   /* after decoding, stop (return 1) when the signature is within the still scene's (fl_set_still_ref) */
#define FL_F_REFERENCE (1 << 30)   /* host test builds only (FL_FRAME_REFERENCE): v0.44.1's composer, to compare with */
#define FL_FMT_565 0
#define FL_FMT_4444 1
#define FL_NO_READING (-999999)
#define FL_FI_OVERLAY 69
#define FL_FI_SIG 70      /* the decoded frame's signature: mean brightness (0..255) of FL_FI_SIG_W x FL_FI_SIG_H cells, row by row */
#define FL_FI_SIG_W 8
#define FL_FI_SIG_H 6
#define FL_FI_SIG_N 48
#define FL_FRAME_INFO 118
#define FL_TM_DECODE 0
#define FL_TM_READ 1
#define FL_TM_LOOK 2
#define FL_TM_COMPOSE 3
#define FL_TM_BLEND 4     /* of compose: the rows blended, faded, dithered and packed, one pass (from every eighth row) */
#define FL_TM_DITHER 5    /* of compose: the overlay's own pixels, the type (likewise); the rest of compose is stretching */
#define FL_FRAME_TIMES 6
fl_frame_ctx *fl_frame_new(void);
void fl_frame_free(fl_frame_ctx *c);
int fl_frame(fl_frame_ctx *c, const unsigned char *jpeg, unsigned long len, const char *lut_path, const int *params,
             int flags, int seq, unsigned short *out, int ow, int oh, int stride, int aspect1000, int format,
             const int *shifts, int max_w, int max_h, int *info, int *tm, char *err, int errlen);
/* The still scene FL_F_STILL compares with: sig (FL_FI_SIG_N cells, as fl_frame writes them), and how far a cell may
 * move (0..255) and still count as still; sig NULL forgets it. Any thread (locked). */
void fl_set_still_ref(const int *sig, int threshold);
/* This core's and its memory's speed, as a line of text for the log (ending with a load chased through 8..256 KB:
 * the caches); px: a screen bitmap's pixels to fill too, or NULL. */
int fl_probe(unsigned char *px, int bytes, char *out, int len);

/* The overlay for every frame from now on: argb (as Android's getPixels gives it, not premultiplied),
 * w x h, the size of the screen picture; NULL clears it. Returns its generation (> 0), or -1. */
int fl_set_overlay(const unsigned int *argb, int w, int h);

/* The calling thread onto one core (Latent is started on core 3 only, which the screen shares).
 * Returns 0, or -1 (errno in *err_no). */
int fl_pin_core(int core, int *err_no);

/* Row access for fl_develop_uyvy: fill (write == 0) or store (write == 1) row y as packed UYVY,
 * w * 2 bytes. Returns 0 on success. */
typedef int (*fl_row_io)(void *ctx, int y, unsigned char *uyvy, int write);

/* Applies the same look and grain as fl_process_file to an image held as UYVY rows (the camera
 * hardware's format), read and written through io. Timings as for fl_process_file. 0 on success. */
int fl_develop_uyvy(const char *lut_path, const int *params, int w, int h, fl_row_io io, void *ctx,
                    fl_progress_fn progress, void *pctx, char *err, int errlen);

/* UYVY (Cb Y0 Cr Y1, full-range BT.601) <-> RGB for one row of w pixels (w even). */
/* Speed test: the look's interchangeable inner loops, timed on in_path decoded at 1/scale; one line each. */
int fl_bench(const char *in_path, const char *lut_path, const int *params, int scale, char *report, int len);
/* Read only: the cores present / online, those Latent may use, its CPU group, and each core's load
 * over one second. One "key: value" line each. */
int fl_cores(char *report, int len);
/* The look on in_path (at 1/scale) on one core, then on two threads with the helper allowed onto every
 * core; reports what that request did, where each ran, both times, and that the pictures match. */
int fl_bench_cores(const char *in_path, const char *lut_path, const int *params, int scale, char *report, int len);
/* Which inner loop fl_develop_uyvy uses (0 = per pair, the default). */
void fl_set_variant(int v);

/* Block access with two buffers (buf 0 or 1): get reads rows y .. y + *n - 1 (packed UYVY, *stride bytes
 * apart) into that buffer to change in place, NULL on failure; put sends those rows on from it (0 = ok).
 * Blocks are read and sent in order, top to bottom; a buffer is only read into after its block was sent. */
typedef unsigned char *(*fl_block_get)(void *ctx, int y, int buf, int *n, int *stride);
typedef int (*fl_block_put)(void *ctx, int y, int n, int buf);
/* The look a block at a time; params[FL_PARAM_THREADS] 1..3 = this core plus helpers on cores 1 and 2 (never 0). */
int fl_develop_blocks(const char *lut_path, const int *params, int w, int h, fl_block_get get, fl_block_put put, void *ctx,
                      fl_progress_fn progress, void *pctx, char *err, int errlen);
/* .cube rows read by Latent's own exact decimal reader (1, the default) or sscanf (0). */
void fl_set_fast_cube(int on);
/* Every cached film forgotten (and the block develop's table made from one, and the decoded previews): the next
 * look reads its file again. A look still using a film keeps it until it is done. */
void fl_forget_lut(void);
/* A film file read into the shared film cache (8 films, the least recently used dropped) unless it is there
 * already, so the next look on it, on any thread, needs no card read. 0, or -1 (err). Any thread. */
int fl_cache_lut(const char *lut_path, char *err, int errlen);
/* Reads path both ways: ms[0] ms with sscanf, ms[1] ms with the fast reader, ms[2] rows the fast reader took,
 * ms[3] rows it left to sscanf. 0 = identical tables and identical floats in every row it took, 1 = not, -1 = error. */
int fl_cube_check(const char *path, int *ms, char *err, int errlen);
/* The next develop's film made ready on a thread of its own (file read, grain tile, Y'CbCr table), while
 * the caller waits on something else (Sony's encoder). Returns at once (once an earlier one is done); the
 * next block develop waits for it. */
void fl_prewarm(const char *lut_path, const int *params);
/* Block develops (per-pair loops) remember recent lookups in 2^bits entries per thread (bits 8..16), or don't
 * (0, the default). The picture is the same either way. */
void fl_set_pair_memo(int bits);
/* Threads the last fl_develop_blocks used; note gets why a helper didn't join, if one didn't. */
int fl_last_threads(char *note, int len);

void fl_uyvy_to_rgb(const unsigned char *uyvy, unsigned char *rgb, int w);
void fl_rgb_to_uyvy(const unsigned char *rgb, unsigned char *uyvy, int w);

#endif
