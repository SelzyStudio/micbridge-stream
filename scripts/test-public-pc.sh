#!/usr/bin/env bash
set -euo pipefail
work="${1:?isolated workspace}"
root="$(cd "$work/../.." && pwd)"
export PATH="$root/toolchains/pc/msys64/ucrt64/bin:$root/toolchains/pc/msys64/usr/bin:$PATH"
src="$work/checkouts/apollo"
mkdir -p "$work/testing/pc"
g++ -std=c++17 -pthread -O1 -I"$src" -I"$src/third-party/googletest/googletest/include" -I"$src/third-party/googletest/googletest" \
 "$src/tests/unit/test_microphone_owner.cpp" "$src/tests/unit/test_mic_render_queue.cpp" "$src/tests/unit/test_stream_activity.cpp" \
 "$src/third-party/googletest/googletest/src/gtest-all.cc" "$src/third-party/googletest/googletest/src/gtest_main.cc" \
 -o "$work/testing/pc/regression.exe"
"$work/testing/pc/regression.exe" --gtest_output=json:"$(cygpath -m "$work/testing/pc/regression.json")"
