#!/usr/bin/env bash
# Packs the app image of `./gradlew appImage` for Linux, in build/dist:
#   peresos-linux-amd64.deb   for `sudo apt install ./peresos-linux-amd64.deb`: /opt/peresos, the
#                             peresos command in /usr/bin, and Peresós in the menu of apps
#   peresos-linux-x64.tar.gz  the same folder, for install.sh on any other Linux
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)
image=build/jpackage/peresos
root=build/deb/peresos
rm -rf build/deb build/dist
mkdir -p build/dist "$root/DEBIAN" "$root/opt" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/256x256/apps"

cp -a "$image" "$root/opt/peresos"
ln -s /opt/peresos/bin/peresos "$root/usr/bin/peresos"
cp build/icons/peresos.png "$root/usr/share/icons/hicolor/256x256/apps/peresos.png"
sed 's|@PERESOS@|/opt/peresos/bin/peresos|; s|@ICON@|peresos|' packaging/linux/peresos.desktop > "$root/usr/share/applications/peresos.desktop"

cat > "$root/DEBIAN/control" <<CONTROL
Package: peresos
Version: $version
Section: utils
Priority: optional
Architecture: amd64
Maintainer: DeepTelar <https://github.com/deeptelar/peresos/issues>
Homepage: https://github.com/deeptelar/peresos
Recommends: libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfreetype6, libfontconfig1
Installed-Size: $(du -sk "$root" | cut -f1)
Description: A sloth that eats one item of your backlog a day
 Peresós takes the things you keep putting off, one a day, and prepares the finished
 version with Gemma 4 in your local Ollama. You only say yes or no. It needs Ollama:
 run peresos doctor after installing it.
CONTROL

chmod -R u+rwX,go-w "$root"
dpkg-deb --root-owner-group --build "$root" build/dist/peresos-linux-amd64.deb
tar -C build/jpackage -czf build/dist/peresos-linux-x64.tar.gz peresos
ls -l build/dist
