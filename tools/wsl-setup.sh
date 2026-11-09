#!/usr/bin/env bash
# 本地打包环境：一次性安装（在 WSL 的 Ubuntu 里，以普通用户运行）。
# 前提：已用 root 装好 openjdk-17-jdk-headless make pkg-config python3-venv curl xz-utils bzip2 unzip file
#       autotools-dev automake git rsync；Windows 的 .wslconfig 设了 networkingMode=mirrored（才能用 Clash 7897）。
# 装到：~/android-sdk（命令行工具 + android-36 平台 + build-tools + 最新正式版 NDK），~/.gradle（代理设置）。
set -euo pipefail

PROXY_HOST=${PROXY_HOST:-127.0.0.1}
PROXY_PORT=${PROXY_PORT:-7897}
export https_proxy="http://$PROXY_HOST:$PROXY_PORT" http_proxy="http://$PROXY_HOST:$PROXY_PORT"

SDK="$HOME/android-sdk"
TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "==== 下载 Android 命令行工具"
    mkdir -p "$SDK/cmdline-tools"
    tmp=$(mktemp -d)
    curl -fL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
fi

SM=("$SDK/cmdline-tools/latest/bin/sdkmanager" "--sdk_root=$SDK" --proxy=http "--proxy_host=$PROXY_HOST" "--proxy_port=$PROXY_PORT")

echo "==== 接受许可"
yes | "${SM[@]}" --licenses >/dev/null || true

# NDK 取最新的正式版（同 GitHub ubuntu-latest 的 ANDROID_NDK_LATEST_HOME），不要 rc / beta
NDK_PKG=$("${SM[@]}" --list 2>/dev/null | grep -oE 'ndk;[0-9]+\.[0-9]+\.[0-9]+ ' | sort -t';' -k2 -V | tail -n1 | tr -d ' ')
echo "==== 安装 platforms;android-36 build-tools;35.0.0 $NDK_PKG"
"${SM[@]}" "platforms;android-36" "build-tools;35.0.0" "$NDK_PKG"

# Gradle 走代理（只写本机 ~/.gradle，不进仓库）
mkdir -p "$HOME/.gradle"
cat > "$HOME/.gradle/gradle.properties" <<EOF
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.0.0.1
org.gradle.daemon=false
EOF

echo "OK：SDK 在 $SDK，NDK = $(ls -d "$SDK"/ndk/* | tail -n1)"
