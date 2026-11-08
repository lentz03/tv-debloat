#!/bin/bash
# Builds GalleryHome.apk with the Debian/Ubuntu Android tool packages (aapt, dx, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
JAR=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -A assets -0 ttf -I "$JAR" -F build/res.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR" \
  -d build/classes $(find src build/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=21 --output=build/dex/classes.dex build/classes
cp build/res.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
# The signing key must stay the same, or Android refuses to update the installed app.
KS=${KS:-}
for k in keystore.jks ../signing/galleryhome-signing.jks; do [ -z "$KS" ] && [ -f "$k" ] && KS=$k; done
[ -n "$KS" ] || { echo "No signing key: put galleryhome-signing.jks in ../signing/ (see README.md)" >&2; exit 1; }
apksigner sign --ks "$KS" --ks-pass pass:galleryhome --key-pass pass:galleryhome \
  --out GalleryHome.apk build/aligned.apk
apksigner verify GalleryHome.apk && ls -la GalleryHome.apk
