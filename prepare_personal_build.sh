#!/usr/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# Ensure all submodules are present before touching them
echo "initializing submodules"
git submodule sync --recursive
git submodule update --init --recursive --jobs 4

RIME_DIR=plugin/rime/src/main/cpp/fcitx5-rime
FCITX5_DIR=lib/fcitx5/src/main/cpp/fcitx5
PREBUILT_DIR=lib/fcitx5/src/main/cpp/prebuilt

# update fcitx5-rime
echo "updating fcitx5-rime"
git -C "$RIME_DIR" remote add gh https://github.com/fxliang/fcitx5-rime.git 2>/dev/null || git -C "$RIME_DIR" remote set-url gh https://github.com/fxliang/fcitx5-rime.git
git -C "$RIME_DIR" fetch -v gh master
git -C "$RIME_DIR" checkout gh/master
sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
git -C "$FCITX5_DIR" checkout -- .
git -C "$FCITX5_DIR" apply "$RIME_DIR/fcitx5-alt-trigger-v4point1.patch" || echo "fcitx5 patch already applied or failed"

# update prebuilt
echo "updating prebuilt"
git -C "$PREBUILT_DIR" remote add gh https://github.com/fxliang/prebuilt.git 2>/dev/null || git -C "$PREBUILT_DIR" remote set-url gh https://github.com/fxliang/prebuilt.git
git -C "$PREBUILT_DIR" fetch -v gh master
git -C "$PREBUILT_DIR" checkout gh/master

echo "personal build sources prepared"
