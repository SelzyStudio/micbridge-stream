#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace path}"
abi="${2:?Android ABI}"
root="$(cd "$work/../.." && pwd)"
sdk="$root/toolchains/android/sdk"
export ANDROID_NDK_ROOT="$sdk/ndk/27.0.12077973"
export PATH="$work/tools/make/usr/bin:$work/tools/nasm/ucrt64/bin:$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin:$root/toolchains/pc/msys64/ucrt64/bin:$root/toolchains/pc/msys64/usr/bin:$PATH"
case "$abi" in
  arm64-v8a) target=android-arm64 ;;
  armeabi-v7a) target=android-arm ;;
  x86) target=android-x86 ;;
  x86_64) target=android-x86_64 ;;
  *) exit 2 ;;
esac
mkdir -p "$work/build/android/$abi/openssl" "$work/logs" "$work/native/android/$abi"
prefix="$work/native/android/$abi/openssl"
cd "$work/build/android/$abi/openssl"
if [[ ! -f libcrypto.a ]]; then
  perl "$work/dependency-sources/openssl-3.5.9/Configure" "$target" \
    no-shared no-tests no-module no-dso no-legacy no-autoload-config \
    -D__ANDROID_API__=21 -fPIC --prefix="$prefix" \
    > "$work/logs/openssl-$abi-configure.log" 2>&1
  make -j4 build_libs > "$work/logs/openssl-$abi-build.log" 2>&1
fi
make install_dev > "$work/logs/openssl-$abi-install.log" 2>&1
opusbuild="$work/build/android/$abi/opus"
cmake -S "$(cygpath -m "$work/dependency-sources/opus-1.6.1")" -B "$(cygpath -m "$opusbuild")" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$(cygpath -m "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake")" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF \
  -DOPUS_DRED=OFF -DOPUS_OSCE=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  > "$work/logs/opus-$abi-configure.log" 2>&1
cmake --build "$(cygpath -m "$opusbuild")" --parallel 4 > "$work/logs/opus-$abi-build.log" 2>&1
cmake --install "$(cygpath -m "$opusbuild")" --prefix "$(cygpath -m "$work/native/android/$abi/opus")" \
  > "$work/logs/opus-$abi-install.log" 2>&1
echo "NATIVE_ABI_BUILT=$abi"
