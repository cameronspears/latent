/* jconfig.h for libjpeg-turbo 2.1.5.1, hand-written for Film Lab (no CMake): plain C, no SIMD.
 * The camera's Cortex-A5 has no NEON, so libjpeg-turbo's SIMD code would never run there anyway. */
#define JPEG_LIB_VERSION  62
#define LIBJPEG_TURBO_VERSION  2.1.5.1
#define LIBJPEG_TURBO_VERSION_NUMBER  2001005
#define C_ARITH_CODING_SUPPORTED 1
#define D_ARITH_CODING_SUPPORTED 1
#define MEM_SRCDST_SUPPORTED 1
/* #undef WITH_SIMD */
#define BITS_IN_JSAMPLE  8
/* #undef RIGHT_SHIFT_IS_UNSIGNED */
