LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := latent

JPEG_DIR := ../vendor/libjpeg-turbo-2.1.5.1
# Same list as tools/libjpeg-sources.sh
JPEG_SRC := jcapimin jcapistd jccoefct jccolor jcdctmgr jchuff jcicc jcinit jcmainct jcmarker \
  jcmaster jcomapi jcparam jcphuff jcprepct jcsample jctrans jdapimin jdapistd jdatadst jdatasrc \
  jdcoefct jdcolor jddctmgr jdhuff jdicc jdinput jdmainct jdmarker jdmaster jdmerge jdphuff jdpostct \
  jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt jidctfst jidctint jidctred jquant1 \
  jquant2 jutils jmemmgr jmemnobs jaricom jcarith jdarith jsimd_none

LOCAL_SRC_FILES := filmcore.c jni_bridge.c sony_bridge.c settings_bridge.c $(addprefix $(JPEG_DIR)/,$(addsuffix .c,$(JPEG_SRC)))
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/turbo $(LOCAL_PATH)/$(JPEG_DIR)
# full 32-bit ARM instructions: faster than Thumb for this pixel-heavy code
LOCAL_ARM_MODE := arm
LOCAL_CFLAGS := -O3 -std=gnu99 -fvisibility=hidden -Wno-main -Wno-shift-negative-value
# The camera runs Android 2.3.7: its linker only reads SysV hash tables.
LOCAL_LDFLAGS := -Wl,--hash-style=sysv -Wl,--gc-sections -Wl,--no-undefined
LOCAL_LDLIBS := -lm -ldl
include $(BUILD_SHARED_LIBRARY)
