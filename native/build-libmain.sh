#!/bin/bash
# Incremental MAME (atetris) Android build -> 16 KB-aligned, stripped libmain.so in QuickTetris.
# MAME tree at mame0289-1018-gd1bdede14cb with native/mame-bgfx-chain-hook.patch applied.
set -euo pipefail
S=${TMPDIR:-/tmp}
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.3.13750724
export SDL_INSTALL_ROOT=${SDL_INSTALL_ROOT:?set to the SDL3 arm64 install prefix}
R=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
J=$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a
cd "${MAME_DIR:-$HOME/mame}"
make android-arm64 SOURCES=src/mame/atari/atetris.cpp -j"$(nproc)" > "$S/mame-build.log" 2>&1 || { tail -30 "$S/mame-build.log"; exit 1; }
cd build/projects/sdl3/mame/gmake-android-arm64
L=../../../../../android-project/app/src/main/libs/arm64-v8a/libmain.so
rm -f "$L"
LINK=$(make config=release mame -n 2>/dev/null | grep -- "-o $L ")
eval "$LINK -Wl,-z,max-page-size=16384"
cp "$L" "$J/libmain.so"
"$R/llvm-strip" --strip-unneeded "$J/libmain.so"
"$R/llvm-readelf" -lW "$J/libmain.so" | awk '/LOAD/{print "align", $NF}' | sort -u
