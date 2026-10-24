#!/usr/bin/env bash
# Packs the app image of `./gradlew appImage` for macOS, in build/dist: peresos-macos-arm64.tar.gz
# with Peresos.app, for install.sh. A download with curl gets no quarantine, so the app opens
# without being notarized.
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf build/dist
mkdir -p build/dist
# An app of Apple silicon runs only signed: ad hoc, with no developer account, is enough.
codesign --force --deep --sign - build/jpackage/Peresos.app
tar -C build/jpackage -czf "build/dist/peresos-macos-$(uname -m).tar.gz" Peresos.app
ls -l build/dist
