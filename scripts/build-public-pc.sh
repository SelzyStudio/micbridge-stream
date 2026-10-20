#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace}"
root="$(cd "$work/../.." && pwd)"
export PATH="$work/tools/make/usr/bin:$work/tools/nasm/ucrt64/bin:$root/toolchains/pc/msys64/ucrt64/bin:$root/toolchains/pc/msys64/usr/bin:$PATH"
unset CODECOV_TOKEN
export npm_config_cache="$(cygpath -m "$work/npm-cache")"
export npm_config_audit=false npm_config_fund=false npm_config_update_notifier=false
export BRANCH=micbridge-public-rc1 BUILD_VERSION=2026.1020.1-micbridge-rc1 COMMIT=997940627f8942f8091610280ceffead55bbf0ce
cmake -S "$(cygpath -m "$work/checkouts/apollo")" -B "$(cygpath -m "$work/build/pc")" -G Ninja \
 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTS=OFF -DBUILD_DOCS=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
 -DCMAKE_PREFIX_PATH="$(cygpath -m "$work/native/windows-openssl");$(cygpath -m "$root/toolchains/pc/msys64/ucrt64");$(cygpath -m "$root/toolchains/pc/msys64/ucrt64/lib/node_modules/npm/bin")" \
 -DOPENSSL_ROOT_DIR="$(cygpath -m "$work/native/windows-openssl")" -DOPENSSL_USE_STATIC_LIBS=TRUE \
 -DFFMPEG_PREPARED_BINARIES="$(cygpath -m "$work/native/windows-codecs")" \
 > "$work/logs/pc-configure.log" 2>&1
cmake --build "$(cygpath -m "$work/build/pc")" --parallel 4 > "$work/logs/pc-build.log" 2>&1
echo PUBLIC_PC_BUILT
