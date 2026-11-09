#!/usr/bin/env bash
# 本地打包（GitHub Actions 用不了时的备用）：在 WSL 的 Ubuntu 里跑，做的事和 .github/workflows/android.yml 一样。
#   Windows 里执行：wsl -d Ubuntu-24.04 -- bash /mnt/d/AiWork/Android/k-audio-app/tools/wsl-build.sh
# 第一次先跑 tools/wsl-setup.sh 装 SDK / NDK。
# 1. 把代码同步到 WSL 自己的文件系统（~/build/k-audio，在 /mnt/d 上直接编会慢好几倍）；
# 2. build.sh 没改过就用上次编好的 ffmpeg，改了才重编（同 CI 的缓存键）；
# 3. gradlew assembleRelease，APK 复制回 Windows 的 apk\KAudio-<版本>.apk。
# 编完不留后台进程（Gradle 不开守护进程），WSL 空闲一会儿自己停；要立刻停就在 Windows 里 wsl --shutdown。
set -euo pipefail

SRC_WIN="$(cd "$(dirname "$0")/.." && pwd)"     # /mnt/d/AiWork/Android/k-audio-app
DST="$HOME/build/k-audio"
SDK="$HOME/android-sdk"
PROXY=${PROXY:-http://127.0.0.1:7897}
export https_proxy=$PROXY http_proxy=$PROXY

[ -d "$SDK/ndk" ] || { echo "还没装 SDK / NDK，先跑 tools/wsl-setup.sh"; exit 1; }
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME
ANDROID_NDK_HOME=$(ls -d "$SDK"/ndk/* | sort -V | tail -n1)

echo "==== 同步代码 $SRC_WIN -> $DST"
mkdir -p "$DST"
rsync -a --delete \
    --exclude .git/ --exclude .gradle/ --exclude build/ --exclude app/build/ --exclude apk/ --exclude dist/ \
    --exclude ffmpeg-android/work/ --exclude ffmpeg-android/out/ --exclude app/src/main/jniLibs/ \
    --exclude local.properties \
    "$SRC_WIN/" "$DST/"

echo "==== ffmpeg"
KEY=$(sha256sum "$DST/ffmpeg-android/build.sh" | cut -c1-16)
CACHE="$HOME/build/ffmpeg-cache/$KEY/ffmpeg"
if [ ! -f "$CACHE" ]; then
    echo "build.sh 变了（或第一次），重编 ffmpeg，约 5~15 分钟"
    rm -rf "$DST/ffmpeg-android/out"
    bash "$DST/ffmpeg-android/build.sh"
    mkdir -p "$(dirname "$CACHE")"
    cp "$DST/ffmpeg-android/out/ffmpeg" "$CACHE"
else
    echo "用缓存 $CACHE"
fi
mkdir -p "$DST/app/src/main/jniLibs/arm64-v8a"
cp "$CACHE" "$DST/app/src/main/jniLibs/arm64-v8a/libffmpeg.so"

echo "==== 编译正式版 APK"
cd "$DST"
echo "sdk.dir=$SDK" > local.properties
chmod +x ./gradlew
./gradlew assembleRelease --no-daemon --console=plain

VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
mkdir -p "$SRC_WIN/apk"
OUT="$SRC_WIN/apk/KAudio-$VER.apk"
cp app/build/outputs/apk/release/*.apk "$OUT"
ls -la "$OUT"
echo "OK -> $OUT"
