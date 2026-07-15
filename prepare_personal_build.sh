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
  # Recursively init nested submodules inside this repo (e.g. libime has kenlm)
  git -C "$path" submodule update --init --recursive --depth 1 2>/dev/null || \
    echo "  (nested submodule init had issues for $path)"
done < <(git config --file .gitmodules --get-regexp 'submodule\..*\.url$')

PREBUILT_DIR=lib/fcitx5/src/main/cpp/prebuilt

# NOTE: fcitx5-rime is intentionally kept at the UPSTREAM version (cloned by the
# loop above from fcitx/fcitx5-rime) instead of switching to fxliang/fcitx5-rime.
# The fxliang frontend requires InputMethodEngineV4Point1, which is injected by a
# local patch (fcitx5-alt-trigger-v4point1.patch) onto the fcitx5 core. That patch
# no longer applies cleanly against the floating upstream fcitx5 master, so the
# plugin build fails with "unknown class name 'InputMethodEngineV4Point1'".
# Upstream fcitx5-rime is API-compatible with upstream fcitx5 master and builds
# reliably. The bundled 万象 (wanxiang) data under plugin/rime/src/main/cpp/ is
# frontend-version-independent, so 万象 keeps working. Trade-off: we lose the
# fxliang alt-trigger (Shift_L) behaviour; it can be re-added later once fcitx5 is
# pinned to a version the patch applies against.

# update prebuilt
echo "updating prebuilt"
git -C "$PREBUILT_DIR" remote add gh https://github.com/fxliang/prebuilt.git 2>/dev/null || \
  git -C "$PREBUILT_DIR" remote set-url gh https://github.com/fxliang/prebuilt.git
git -C "$PREBUILT_DIR" fetch -v gh master
git -C "$PREBUILT_DIR" checkout gh/master

echo "personal build sources prepared"
