#!/usr/bin/env bash
# 用 Android NDK 交叉编译 ffmpeg 可执行文件（arm64-v8a），带 MP3 / Vorbis / Opus 编码器。
# 在 Linux 上跑（GitHub Actions 的 ubuntu-latest 自带 NDK）；产物 out/ffmpeg，由 CI 复制成
# app/src/main/jniLibs/arm64-v8a/libffmpeg.so。本文件一改，CI 缓存键就变，会重新编译。
set -euo pipefail

API=29                       # 与 app 的 minSdk 一致
FFMPEG_VER=7.1.1
LAME_VER=3.100
OGG_VER=1.3.5
VORBIS_VER=1.3.7
OPUS_VER=1.5.2

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
[ -d "$NDK" ] || { echo "找不到 NDK（ANDROID_NDK_LATEST_HOME / ANDROID_NDK_HOME）"; exit 1; }
echo "NDK: $NDK"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
SRC="$WORK/src"
PREFIX="$WORK/prefix"
OUT="$HERE/out"
mkdir -p "$SRC" "$PREFIX" "$OUT"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
HOST=aarch64-linux-android
export CC="$TC/bin/${HOST}${API}-clang"
export CXX="$TC/bin/${HOST}${API}-clang++"
export AR="$TC/bin/llvm-ar"
export RANLIB="$TC/bin/llvm-ranlib"
export STRIP="$TC/bin/llvm-strip"
export NM="$TC/bin/llvm-nm"
export CFLAGS="-O2 -fPIC"
# 16KB 页对齐：Android 15 起的 16KB 页设备也能执行
export LDFLAGS="-Wl,-z,max-page-size=16384"
# pkg-config 只看我们自己装的库，别把宿主机的混进来
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""
JOBS=$(nproc)

fetch() { # fetch <url> <目录名>
    local url=$1 dir=$2 file
    file="$SRC/$(basename "$url")"
    [ -d "$SRC/$dir" ] && return
    curl -fL --retry 4 --retry-delay 5 -o "$file" "$url"
    tar -xf "$file" -C "$SRC"
}

# 老 autotools 包的 config.sub 可能不认 android，换成系统里新的
fresh_config() {
    local f src
    for f in config.sub config.guess; do
        src=$(ls /usr/share/misc/$f /usr/share/automake-*/$f 2>/dev/null | head -n1 || true)
        [ -n "$src" ] && [ -f "$1/$f" ] && cp "$src" "$1/$f"
    done
    return 0
}

autotools_build() { # autotools_build <目录> <configure 参数...>
    local dir=$1; shift
    (
        cd "$SRC/$dir"
        fresh_config .
        ./configure --host=$HOST --prefix="$PREFIX" --enable-static --disable-shared --with-pic "$@"
        make -j"$JOBS"
        make install
    )
}

echo "==== lame $LAME_VER"
fetch "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz" "lame-$LAME_VER"
autotools_build "lame-$LAME_VER" --disable-frontend --disable-decoder --disable-gtktest

echo "==== libogg $OGG_VER"
fetch "https://downloads.xiph.org/releases/ogg/libogg-$OGG_VER.tar.xz" "libogg-$OGG_VER"
autotools_build "libogg-$OGG_VER"

echo "==== libvorbis $VORBIS_VER"
fetch "https://downloads.xiph.org/releases/vorbis/libvorbis-$VORBIS_VER.tar.xz" "libvorbis-$VORBIS_VER"
autotools_build "libvorbis-$VORBIS_VER" --with-ogg="$PREFIX" --disable-oggtest --disable-docs --disable-examples

echo "==== opus $OPUS_VER"
fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz" "opus-$OPUS_VER"
autotools_build "opus-$OPUS_VER" --disable-doc --disable-extra-programs

echo "==== ffmpeg $FFMPEG_VER"
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz" "ffmpeg-$FFMPEG_VER"
(
    cd "$SRC/ffmpeg-$FFMPEG_VER"
    # 编码器只留导出要用的：格式下拉框 7 种 +「保持原格式」重编码时各容器的默认编码器
    ./configure \
        --prefix="$PREFIX" \
        --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
        --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
        --sysroot="$TC/sysroot" \
        --pkg-config=pkg-config --pkg-config-flags=--static \
        --extra-cflags="-O2 -fPIC -I$PREFIX/include" \
        --extra-ldflags="-L$PREFIX/lib $LDFLAGS" \
        --extra-libs="-lm" \
        --enable-static --disable-shared --enable-pic \
        --disable-autodetect --enable-zlib \
        --disable-doc --disable-ffplay --disable-ffprobe --disable-debug \
        --disable-avdevice --disable-devices --disable-network --disable-hwaccels \
        --enable-libmp3lame --enable-libvorbis --enable-libopus \
        --disable-encoders \
        --enable-encoder=libmp3lame,libvorbis,libopus,aac,flac,alac,ac3,wmav2,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_f32le \
        || { tail -n 80 ffbuild/config.log; exit 1; }
    make -j"$JOBS"
    cp ffmpeg "$OUT/ffmpeg"
)
"$STRIP" "$OUT/ffmpeg"
file "$OUT/ffmpeg" || true
ls -la "$OUT/ffmpeg"
echo "OK -> $OUT/ffmpeg"
