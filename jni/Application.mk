# armeabi-v7a is what the A5100 runs (hardware FPU, ARMv7: much faster); armeabi is the fallback
# for older bodies. Same toolchain as Recipe Lab, which is confirmed on the A5100 (Android 2.3.7).
APP_ABI := armeabi-v7a armeabi
APP_PLATFORM := android-14
NDK_TOOLCHAIN_VERSION := 4.9
