# libjpeg-turbo 2.1.5.1 library sources (JPEG_SOURCES from its CMakeLists.txt, plus arithmetic coding
# and the no-SIMD stubs). Sourced by build-host.sh; jni/Android.mk keeps the same list.
JPEG_DIR=vendor/libjpeg-turbo-2.1.5.1
LIBJPEG_SOURCES="jcapimin jcapistd jccoefct jccolor jcdctmgr jchuff jcicc jcinit jcmainct jcmarker
  jcmaster jcomapi jcparam jcphuff jcprepct jcsample jctrans jdapimin jdapistd jdatadst jdatasrc
  jdcoefct jdcolor jddctmgr jdhuff jdicc jdinput jdmainct jdmarker jdmaster jdmerge jdphuff jdpostct
  jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt jidctfst jidctint jidctred jquant1
  jquant2 jutils jmemmgr jmemnobs jaricom jcarith jdarith jsimd_none"
