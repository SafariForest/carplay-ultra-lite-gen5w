#!/bin/sh
# cp-climate's package step (run by HMG-API's tools/mkmodpkg.sh from its source root, after hooks.jar is built):
#   sh mods/cp-climate/build.sh <package folder>
# Merges the video engine (tools/video: ExoPlayer through the phone's proxy, BouncyCastle TLS 1.3) into
# hooks.jar: cp-climate's classes + the engine + its libraries, shrunk by R8 to one dex for Android 4.4.
# Without tools/video the package keeps HMG-API's plain hooks.jar (no engine: answers only).
set -e
PKG=${1:?usage: build.sh <package folder>}
HERE=$(cd "$(dirname "$0")" && pwd)
V=$HERE/tools/video
[ -d "$V/src" ] || exit 0
[ -f "$V/r8.jar" ] || { echo "build.sh: the video engine's libraries are missing: run sh $V/fetch-libs.sh first" >&2; exit 1; }
ID=$(basename "$HERE")
CORE=$PWD/out/mod-classes/$ID
[ -d "$CORE" ] || { echo "build.sh: no $CORE (HMG-API's mod build first)" >&2; exit 1; }
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
LIBS=$(ls "$V"/libs/*.jar | tr '\n' ':')
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/classes"
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$CORE:$LIBS" -d "$W/classes" $(find "$V/src" -name '*.java')
cp -R "$CORE"/. "$W/classes/"
(cd "$W/classes" && jar cf "$W/program.jar" .)
RULES=""
for r in "$V"/rules/*.pro; do RULES="$RULES --pg-conf $r"; done
# program: cp-climate + engine + libraries (androidx.annotation is compile-only)
PROGRAM="$W/program.jar"
for j in "$V"/libs/*.jar; do case "$(basename "$j")" in annotation-*) ;; *) PROGRAM="$PROGRAM $j" ;; esac; done
java -cp "$V/r8.jar" com.android.tools.r8.R8 --release --min-api 19 --lib "$ANDROID_JAR" --classpath "$V/libs/annotation-1.3.0.jar" \
    $RULES --output "$W/out.zip" $PROGRAM >"$W/r8.log" 2>&1 || { cat "$W/r8.log" >&2; exit 1; }
DEXES=$(unzip -l "$W/out.zip" | grep -c 'classes[0-9]*\.dex' || true)
[ "$DEXES" = 1 ] || { echo "build.sh: R8 made $DEXES dex files; Android 4.4's class loader reads only one" >&2; exit 1; }
mkdir -p "$W/jar" && (cd "$W/jar" && unzip -q "$W/out.zip" classes.dex)
rm -f "$PKG/hooks.jar"
(cd "$W/jar" && TZ=UTC touch -d 2026-01-01T00:00:00 classes.dex && zip -q -X "$PKG/hooks.jar" classes.dex)
echo "build.sh: hooks.jar with the video engine, $(wc -c < "$W/jar/classes.dex" | tr -d ' ') bytes of dex" >&2
