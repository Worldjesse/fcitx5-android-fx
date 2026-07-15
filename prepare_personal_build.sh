#!/usr/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# Best-effort submodule init: tolerate individual failures (e.g. rime/prebuilt
# upstream URLs may be unreachable). We self-heal critical directories below.
echo "initializing submodules (best-effort)"
git config core.filemode false
git submodule sync --recursive
git submodule update --init --recursive --jobs 4 || echo "submodule update had failures; will self-heal critical dirs"

# Self-heal: ensure three critical dirs exist as proper git repos.
# If a dir is a broken empty gitlink, remove it; otherwise clone fresh.
ensure_repo() {
  local dir="$1"
  local url="$2"
  if [ -d "$dir" ]; then
    # If it's a submodule pointer (file .git) or an actual .git dir, nuke and re-clone
    if [ -f "$dir/.git" ] || [ -d "$dir/.git" ]; then
      echo "resetting existing $dir"
      rm -rf "$dir"
    else
      echo "warning: $dir exists and is not a git repo, leaving as-is"
      return 0
    fi
  fi
  if [ ! -d "$dir" ]; then
    echo "cloning $url -> $dir"
    git clone "$url" "$dir"
  fi
}

ensure_repo plugin/rime/src/main/cpp/fcitx5-rime https://github.com/fxliang/fcitx5-rime.git
ensure_repo lib/fcitx5/src/main/cpp/prebuilt   https://github.com/fxliang/prebuilt.git
ensure_repo lib/fcitx5/src/main/cpp/fcitx5     https://github.com/fcitx/fcitx5.git

RIME_DIR=plugin/rime/src/main/cpp/fcitx5-rime
FCITX5_DIR=lib/fcitx5/src/main/cpp/fcitx5
PREBUILT_DIR=lib/fcitx5/src/main/cpp/prebuilt

# update fcitx5-rime to fxliang master
echo "updating fcitx5-rime"
git -C "$RIME_DIR" remote add gh https://github.com/fxliang/fcitx5-rime.git 2>/dev/null || \
  git -C "$RIME_DIR" remote set-url gh https://github.com/fxliang/fcitx5-rime.git
git -C "$RIME_DIR" fetch -v gh master
git -C "$RIME_DIR" checkout gh/master
sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
git -C "$FCITX5_DIR" checkout -- .
git -C "$FCITX5_DIR" apply "$RIME_DIR/fcitx5-alt-trigger-v4point1.patch" || \
  echo "fcitx5 patch already applied or failed"

# update prebuilt
echo "updating prebuilt"
git -C "$PREBUILT_DIR" remote add gh https://github.com/fxliang/prebuilt.git 2>/dev/null || \
  git -C "$PREBUILT_DIR" remote set-url gh https://github.com/fxliang/prebuilt.git
git -C "$PREBUILT_DIR" fetch -v gh master
git -C "$PREBUILT_DIR" checkout gh/master

echo "personal build sources prepared"
