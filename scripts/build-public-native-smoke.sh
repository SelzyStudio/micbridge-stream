#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace}"
root="$(cd "$work/../.." && pwd)"
export PATH="$root/toolchains/pc/msys64/usr/bin:$PATH"
ndk="$root/toolchains/android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin"
common="$work/checkouts/artemis/app/src/main/jni/moonlight-core/moonlight-common-c"
prefix="$work/native/android/x86_64"
"$ndk/clang.exe" --target=x86_64-none-linux-android21 -fPIE -pie -Wl,-z,max-page-size=16384 \
 -I"$(cygpath -m "$common/src")" -I"$(cygpath -m "$common/enet/include")" -I"$(cygpath -m "$common/reedsolomon")" \
 -I"$(cygpath -m "$prefix/openssl/include")" -I"$(cygpath -m "$prefix/opus/include")" \
 "$(cygpath -m "$root/scripts/public-native-smoke.c")" "$(cygpath -m "$common/src/PlatformCrypto.c")" \
 "$(cygpath -m "$prefix/openssl/lib/libssl.a")" "$(cygpath -m "$prefix/openssl/lib/libcrypto.a")" \
 "$(cygpath -m "$prefix/opus/lib/libopus.a")" -ldl -lm -o "$(cygpath -m "$work/testing/native-smoke-x86_64")"
