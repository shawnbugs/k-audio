#!/usr/bin/env bash
# 本地打包环境第 0 步：用 root 装系统包。
#   wsl -d Ubuntu-24.04 -u root -- bash /mnt/d/AiWork/Android/k-audio-app/tools/wsl-root-packages.sh
# 官方源直连慢，走 Clash 代理也只有几 KB/s（2026-11-09 实测，apt 卡了十几分钟）；阿里云镜像直连快得多。
# 做法：包索引照旧走代理更新（也很慢，所以 7 天内更新过就跳过），要下载的包地址换成阿里云、curl 下到 apt 缓存，
# 再离线安装。不改系统软件源。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
PROXY=${PROXY:-http://127.0.0.1:7897}
MIRROR=${MIRROR:-http://mirrors.aliyun.com/ubuntu}
P=(-o "Acquire::http::Proxy=$PROXY" -o "Acquire::https::Proxy=$PROXY" -o Acquire::Retries=5)
# gcc：ffmpeg 的 configure 要用宿主机编译器编几个辅助小程序（交叉编译本身用 NDK 的 clang）
PKGS=(openjdk-17-jdk-headless make pkg-config python3-venv python3-pip curl ca-certificates
      xz-utils bzip2 unzip file autotools-dev automake git rsync gcc libc6-dev)
dpkg --configure -a
if [ -z "$(find /var/lib/apt/lists -maxdepth 1 -name '*_InRelease' -mtime -7 2>/dev/null)" ]; then
    apt-get "${P[@]}" update -qq
fi
cd /var/cache/apt/archives
apt-get install -y --no-install-recommends --print-uris "${PKGS[@]}" | { grep "^'" || true; } | while read -r uri name size _; do
    uri=${uri//\'/}
    url=$(echo "$uri" | sed -E "s#^https?://(archive|security)\.ubuntu\.com/ubuntu#$MIRROR#")
    [ -f "$name" ] && [ "$(stat -c %s "$name")" = "$size" ] && continue
    echo "下载 $name"
    curl -fsS --retry 3 -o "partial/$name" "$url"
    mv "partial/$name" "$name"
done
apt-get install -y -qq --no-install-recommends --no-download "${PKGS[@]}"
java -version
gcc --version | head -1
