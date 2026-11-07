#!/usr/bin/env bash
# 用 Android NDK 交叉编译 ffmpeg 可执行文件（arm64-v8a），带 MP3 / Vorbis / Opus 音频编码器，
# 和 x264 / x265 视频编码器（0.3.0 起，移植 PC 版的视频剪辑；x264/x265 是 GPL，整个 ffmpeg 随之变成 GPL，自用无所谓）。
# 在 Linux 上跑（GitHub Actions 的 ubuntu-latest 自带 NDK）；产物 out/ffmpeg，由 CI 复制成
# app/src/main/jniLibs/arm64-v8a/libffmpeg.so。本文件一改，CI 缓存键就变，会重新编译。
set -euo pipefail

API=29                       # 与 app 的 minSdk 一致
FFMPEG_VER=7.1.1
LAME_VER=3.100
OGG_VER=1.3.5
VORBIS_VER=1.3.7
OPUS_VER=1.5.2
X265_VER=4.1                 # x264 没有正式版号，用官方 stable 分支

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

echo "==== x264 (stable)"
if ! fetch "https://code.videolan.org/videolan/x264/-/archive/stable/x264-stable.tar.bz2" "x264-stable"; then
    # videolan 偶尔连不上，退到 GitHub 镜像的同名分支
    rm -rf "$SRC/x264-stable"
    curl -fL --retry 4 --retry-delay 5 -o "$SRC/x264-gh.tar.gz" "https://github.com/mirror/x264/archive/refs/heads/stable.tar.gz"
    tar -xf "$SRC/x264-gh.tar.gz" -C "$SRC"   # 解出来同样是 x264-stable/
fi
(
    cd "$SRC/x264-stable"
    # x264 的 configure 不是 autotools：编译器从 CC 环境变量取，汇编也交给 CC（NDK clang）
    STRINGS="$TC/bin/llvm-strings" ./configure \
        --prefix="$PREFIX" --host=$HOST --sysroot="$TC/sysroot" \
        --enable-static --enable-pic --disable-cli --disable-opencl
    make -j"$JOBS"
    make install
)

echo "==== x265 $X265_VER"
fetch "https://bitbucket.org/multicoreware/x265_git/downloads/x265_$X265_VER.tar.gz" "x265_$X265_VER"
# x265 的 CMakeLists 还写着 cmake 2.8 时代的策略，CMake 4 会直接报错；单独装一个 3.x 用
if [ ! -x "$WORK/venv/bin/cmake" ]; then
    python3 -m venv "$WORK/venv"
    "$WORK/venv/bin/pip" install -q "cmake>=3.20,<4"
fi
x265_build() { # x265_build ON|OFF（汇编）
    local bdir="$SRC/x265-build"
    rm -rf "$bdir"
    # 不用 NDK 的 toolchain 文件：x265 编 aarch64 汇编时直接调 CMAKE_CXX_COMPILER、不带 --target，
    # 只有 NDK 的「aarch64-linux-android29-clang++」这种自带目标的包装脚本才能编对
    "$WORK/venv/bin/cmake" -S "$SRC/x265_$X265_VER/source" -B "$bdir" \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
        -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" \
        -DCMAKE_AR="$AR" -DCMAKE_RANLIB="$RANLIB" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_LIBNUMA=OFF -DENABLE_PIC=ON \
        -DENABLE_ASSEMBLY="$1" \
        -DAARCH64_RUNTIME_CPU_DETECT=OFF \
        -DENABLE_NEON_DOTPROD=OFF -DENABLE_NEON_I8MM=OFF -DENABLE_SVE=OFF -DENABLE_SVE2=OFF
    make -C "$bdir" -j"$JOBS"
    make -C "$bdir" install
}
# 先带 NEON 汇编编（快好几倍）；编不过就退回纯 C，至少能用
if ! x265_build ON; then
    echo "!!!! x265 带汇编编译失败，改用纯 C 版本（H.265 导出会慢很多）"
    x265_build OFF
fi
# x265.pc 里的 C++ 运行库按宿主机写的；换成 NDK 的静态 libc++，ffmpeg 才能静态链进去、不依赖 libc++_shared.so
sed -i 's/^Libs.private:.*/Libs.private: -lc++_static -lc++abi -lm -ldl/' "$PREFIX/lib/pkgconfig/x265.pc"
cat "$PREFIX/lib/pkgconfig/x265.pc"

echo "==== ffmpeg $FFMPEG_VER"
# ffmpeg.org 在 CI 上偶尔被重置连接，退到 GitHub 官方镜像的同版本标签
if ! fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz" "ffmpeg-$FFMPEG_VER"; then
    curl -fL --retry 4 --retry-delay 5 -o "$SRC/ffmpeg-gh.tar.gz" "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$FFMPEG_VER.tar.gz"
    tar -xf "$SRC/ffmpeg-gh.tar.gz" -C "$SRC"
    mv "$SRC/FFmpeg-n$FFMPEG_VER" "$SRC/ffmpeg-$FFMPEG_VER"
fi
(
    cd "$SRC/ffmpeg-$FFMPEG_VER"
    # 编码器只留要用的：音频格式 7 种 +「保持原格式」重编码时各容器的默认编码器 + 视频 H.264/H.265 +
    # bmp（取视频画面预览用，同 PC 版）。滤镜（trim/concat/scale/fade…）和解码器保持默认全开。
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
        --enable-gpl \
        --enable-libmp3lame --enable-libvorbis --enable-libopus --enable-libx264 --enable-libx265 \
        --disable-encoders \
        --enable-encoder=libmp3lame,libvorbis,libopus,aac,flac,alac,ac3,wmav2,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_f32le \
        --enable-encoder=libx264,libx265,bmp \
        || { tail -n 80 ffbuild/config.log; exit 1; }
    make -j"$JOBS"
    cp ffmpeg "$OUT/ffmpeg"
)
"$STRIP" "$OUT/ffmpeg"
file "$OUT/ffmpeg" || true
ls -la "$OUT/ffmpeg"
# 只许依赖系统自带的库：要是混进了 libc++_shared.so 之类，手机上会直接起不来
NEEDED=$("$TC/bin/llvm-readelf" -d "$OUT/ffmpeg" | grep NEEDED || true)
echo "$NEEDED"
if echo "$NEEDED" | grep -vqE '\[(libc|libm|libdl|libz|liblog)\.so\]'; then
    echo "ffmpeg 依赖了系统里没有的库"; exit 1
fi
echo "OK -> $OUT/ffmpeg"
