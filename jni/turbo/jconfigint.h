/* jconfigint.h for libjpeg-turbo 2.1.5.1, hand-written for Film Lab (camera armeabi and Mac host). */
#define BUILD  "latent"
#undef inline
#define INLINE  __inline__ __attribute__((always_inline))
/* Android 2.3's dynamic linker has no thread-local storage; only TurboJPEG (not built) uses it. */
#define THREAD_LOCAL
#define PACKAGE_NAME  "libjpeg-turbo"
#define VERSION  "2.1.5.1"
#if defined(__LP64__) || defined(_WIN64)
#define SIZEOF_SIZE_T  8
#else
#define SIZEOF_SIZE_T  4
#endif
#define HAVE_BUILTIN_CTZL
#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif
