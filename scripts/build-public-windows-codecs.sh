#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace}"
root="$(cd "$work/../.." && pwd)"
export PATH="$work/tools/make/usr/bin:$work/tools/nasm/ucrt64/bin:$root/toolchains/pc/msys64/ucrt64/bin:$root/toolchains/pc/msys64/usr/bin:$PATH"
export MSYS2_ROOT="$(cygpath -m "$root/toolchains/pc/msys64")"
export PKG_CONFIG_PATH="$(cygpath -m "$root/toolchains/pc/msys64/ucrt64/lib/pkgconfig")"
export MSYSTEM=UCRT64
mkdir -p "$work/logs"
cmake -S "$(cygpath -m "$work/dependency-sources/build-deps-recipe")" \
 -B "$(cygpath -m "$work/build/windows-codecs")" -G Ninja \
 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
 -DCMAKE_INSTALL_PREFIX="$(cygpath -m "$work/native/windows-codecs")" \
 -DCMAKE_PREFIX_PATH="$(cygpath -m "$root/toolchains/pc/msys64/ucrt64")" \
 -DBASH_EXECUTABLE="$(cygpath -m "$root/toolchains/pc/msys64/usr/bin/bash.exe")" \
 -DMAKE_EXECUTABLE="$(cygpath -m "$work/tools/make/usr/bin/make.exe")" \
 -DMSYS2_OPTION=3 -DPARALLEL_BUILDS=4 \
 > "$work/logs/windows-codecs-configure.log" 2>&1
cmake --build "$(cygpath -m "$work/build/windows-codecs")" --parallel 4 \
 > "$work/logs/windows-codecs-build.log" 2>&1
cmake --install "$(cygpath -m "$work/build/windows-codecs")" \
 > "$work/logs/windows-codecs-install.log" 2>&1
echo WINDOWS_CODECS_BUILT
