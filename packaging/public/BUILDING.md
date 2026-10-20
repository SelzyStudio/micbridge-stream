# Build the public candidate

Use `MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip`, not just the small GitHub source ZIP. Extract it to a new folder. The companion contains complete application source snapshots, nested initialized submodules, codec/OpenSSL/Opus source archives, exact MSYS2 package recipes and verified upstream inputs, Java source JARs and notices.

Run `python scripts/Bootstrap-PublicSourceBuild.py --destination C:\NewMicBridgeBuild`. It verifies source/input hashes before copying, requires a new destination, and extracts pinned native source trees. It installs no application. This source layout avoids relying on missing nested Git submodules or the historical orphan codec binary repository.

Supply tools under the new workspace's `toolchains` directory:

- `android/jdk`: JDK 17; `android/sdk`: Android SDK platform 36, build-tools 36.0.0, NDK 27.0.12077973, cmdline-tools 16.0. Accept SDK licenses yourself. The Gradle wrapper uses Gradle 8.13 and downloads its distribution/dependencies from their configured public repositories.
- `pc/msys64`: MSYS2 UCRT64 CMake, Ninja, GCC 16.2, Node/npm, Perl and libraries matching `dependency-locks/MSYS2-SOURCE-RECIPES.json`. Native linked library sources/patches and recipes accompany this source asset. See each PKGBUILD's build/make/check requirements. GCC, CRT and winpthreads source inputs are included too.
- Unpack the pinned make and NASM packages using `scripts/Prepare-PublicBuildTools.py`; this creates only workspace-local tool folders. That helper expects the workspace's `toolchains/packaging/7zip-26.04/7z.exe`; alternatively unpack those official zstd/tar packages with existing trusted tools to the paths shown in the script.

From the new workspace, use its MSYS2 bash and pass `/c/NewMicBridgeBuild/distribution-work/2026-10-20-r1` as WORK:

```text
bash scripts/build-public-android-native.sh WORK arm64-v8a
bash scripts/build-public-android-native.sh WORK armeabi-v7a
bash scripts/build-public-android-native.sh WORK x86
bash scripts/build-public-android-native.sh WORK x86_64
python scripts/Use-PublicAndroidNative.py
pwsh -NoProfile -File scripts/Build-PublicAndroid.ps1
bash scripts/build-public-windows-openssl.sh WORK
bash scripts/build-public-windows-codecs.sh WORK
```

Before host build run `npm ci --ignore-scripts` in `WORK/checkouts/apollo`, using the pinned package-lock and workspace-scoped cache. Then `bash scripts/build-public-pc.sh WORK`. The code uses the newly built OpenSSL and all eight codec archives explicitly. It does not fall back to the old prebuilt codec artifact. The Windows package helper requires NSIS in the UCRT64 tools and creates separate app files only.

Android release build produces unsigned APKs. Use Android SDK `zipalign -P 16 4`, then `apksigner sign` with your own retained release key and `apksigner verify`. Never commit a keystore or password. A community rebuild cannot update our signed application without our private key; choose its own application ID/signing identity for separate distribution.

The published candidate was built in an isolated local checkout. Selected Android tests 422 and PC tests 22 passed. This is not a claim of bit-for-bit reproducibility or a clean build on another physical PC. The bootstrap checks archive integrity; it does not certify cross-machine runtime behavior.

Sources: [OpenSSL LTS policy](https://openssl-library.org/policies/releasestrat/), [Android API desugaring](https://developer.android.com/studio/write/java8-support), [Android 16KB page sizes](https://developer.android.com/guide/practices/page-sizes).
