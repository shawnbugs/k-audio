#!/usr/bin/env bash
# 本地打包环境第 0 步：用 root 装系统包（走 Clash 代理，官方源直连太慢）。
#   wsl -d Ubuntu-24.04 -u root -- bash /mnt/d/AiWork/Android/k-audio-app/tools/wsl-root-packages.sh
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
PROXY=${PROXY:-http://127.0.0.1:7897}
P=(-o "Acquire::http::Proxy=$PROXY" -o "Acquire::https::Proxy=$PROXY" -o Acquire::Retries=5)
dpkg --configure -a
apt-get "${P[@]}" update -qq
apt-get "${P[@]}" install -y -qq --no-install-recommends \
    openjdk-17-jdk-headless make pkg-config python3-venv python3-pip curl ca-certificates \
    xz-utils bzip2 unzip file autotools-dev automake git rsync gcc libc6-dev
# gcc：ffmpeg 的 configure 要用宿主机编译器编几个辅助小程序（交叉编译本身用 NDK 的 clang）
java -version
