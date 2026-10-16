#!/usr/bin/env bash
# Downloads the toolchain build.sh needs into $LATENT_TOOLCHAIN (default ~/.latent-toolchain):
#   android-ndk-r16b   the last NDK that builds for armeabi (what the camera's Android 2.3 runs)
#   sdk/               build-tools 30.0.3 + platforms/android-28 (android.jar, api-versions.xml)
# About 2.5 GB unpacked. Needs a JDK (11 is used by build.sh) and, on Apple Silicon, Rosetta.
set -euo pipefail
TC="${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}"
case "$TC" in *" "*) echo "the toolchain path must not contain spaces: $TC" >&2; exit 1;; esac
mkdir -p "$TC/dl"
cd "$TC"

fetch() {  # url file sha1
  [ -e "dl/$2" ] || curl -fSL -o "dl/$2" "$1"
  echo "$3  dl/$2" | shasum -a 1 -c -
}

if [ ! -x android-ndk-r16b/ndk-build ]; then
  fetch https://dl.google.com/android/repository/android-ndk-r16b-darwin-x86_64.zip ndk.zip \
    e51e615449b98c716cf912057e2682e75d55e2de
  unzip -q dl/ndk.zip
fi
if [ ! -e sdk/platforms/android-28/android.jar ]; then
  fetch https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip clt.zip \
    37fb7dd41005b3b4ca6ea48ac27074b6fc4e3236
  mkdir -p sdk/cmdline-tools
  rm -rf sdk/cmdline-tools/latest sdk/cmdline-tools/cmdline-tools
  unzip -q dl/clt.zip -d sdk/cmdline-tools
  mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
  yes | sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$TC/sdk" "build-tools;30.0.3" "platforms;android-28" >/dev/null
fi
rm -rf dl
echo "toolchain ready in $TC"
