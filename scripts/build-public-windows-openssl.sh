#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace}"
root="$(cd "$work/../.." && pwd)"
export PATH="$work/tools/make/usr/bin:$work/tools/nasm/ucrt64/bin:$root/toolchains/pc/msys64/ucrt64/bin:$root/toolchains/pc/msys64/usr/bin:$PATH"
mkdir -p "$work/build/windows-openssl" "$work/logs"
cd "$work/build/windows-openssl"
if [[ ! -f libcrypto.a ]]; then
 perl "$work/dependency-sources/openssl-3.5.9/Configure" mingw64 \
  no-shared no-tests no-module no-dso no-legacy no-autoload-config \
  --prefix="$(cygpath -m "$work/native/windows-openssl")" \
  > "$work/logs/windows-openssl-configure.log" 2>&1
 make -j2 build_libs > "$work/logs/windows-openssl-build.log" 2>&1
fi
make install_dev > "$work/logs/windows-openssl-install.log" 2>&1
echo WINDOWS_OPENSSL_BUILT
