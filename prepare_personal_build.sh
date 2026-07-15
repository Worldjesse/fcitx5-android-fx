#!/usr/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

git config core.filemode false

# This repo's HEAD has NO submodule gitlinks (submodules were never
# `git submodule add`-ed; only .gitmodules declares them). So `git submodule
# update --init` cannot work. Instead we read .gitmodules and git clone every
# missing submodule directly.
echo "cloning all submodules from .gitmodules"
while IFS=' ' read -r key url; do
  # key = submodule.<path>.url, url = https://github.com/...
  section="${key%.url}"
  section="${section#submodule.}"
  path="$section"
  if [ -d "$path/.git" ] || [ -f "$path/.git" ]; then
    echo "  skip (exists): $path"
    continue
  fi
  echo "  cloning $url -> $path"
  mkdir -p "$(dirname "$path")"
  rm -rf "$path"
  git clone --depth 1 "$url" "$path" || echo "  CLONE FAILED: $path"
done < <(git config --file .gitmodules --get-regexp 'submodule\..*\.url$')

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
