#!/bin/sh
# Downloads the video engine's build tools and libraries (not kept in git) into libs/ and r8.jar, from Maven
# Central and Google's Maven, and checks each against the SHA-256 the engine was built and tested with.
# ExoPlayer modules published as .aar contribute their classes.jar.
#   sh mod/tools/video/fetch-libs.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
CENTRAL=https://repo1.maven.org/maven2
GOOGLE=https://dl.google.com/dl/android/maven2
mkdir -p "$HERE/libs"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

# out-file  repo  group/artifact path  version  sha256
while read -r out repo path ver sum; do
    [ -n "$out" ] || continue
    dest="$HERE/$out"
    if [ -f "$dest" ] && [ "$(shasum -a 256 "$dest" | cut -d' ' -f1)" = "$sum" ]; then continue; fi
    base=$(basename "$path")
    url="$repo/$path/$ver/$base-$ver"
    if curl -fsSL "$url.jar" -o "$W/dl" 2>/dev/null; then
        cp "$W/dl" "$dest"
    else
        curl -fsSL "$url.aar" -o "$W/dl.aar"
        (cd "$W" && rm -f classes.jar && unzip -q -o dl.aar classes.jar)
        cp "$W/classes.jar" "$dest"
    fi
    got=$(shasum -a 256 "$dest" | cut -d' ' -f1)
    if [ "$got" != "$sum" ]; then
        echo "fetch-libs: $out has SHA-256 $got, expected $sum" >&2
        rm -f "$dest"
        exit 1
    fi
    echo "fetch-libs: $out"
done <<EOF
r8.jar $GOOGLE com/android/tools/r8 8.5.35 4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294
libs/annotation-1.3.0.jar $GOOGLE androidx/annotation/annotation 1.3.0 97dc45afefe3a1e421da42b8b6e9f90491477c45fc6178203e3a5e8a05ee8553
libs/bcprov-jdk18on-1.78.1.jar $CENTRAL org/bouncycastle/bcprov-jdk18on 1.78.1 add5915e6acfc6ab5836e1fd8a5e21c6488536a8c1f21f386eeb3bf280b702d7
libs/bctls-jdk18on-1.78.1.jar $CENTRAL org/bouncycastle/bctls-jdk18on 1.78.1 483bd1582d3957adfe100747f22c6da0ff9532d6464f9c454181f99bfa44e52b
libs/bcutil-jdk18on-1.78.1.jar $CENTRAL org/bouncycastle/bcutil-jdk18on 1.78.1 d9fa56f97b0f761ce3bc8d9d74c5d7137a987bf5bd3abfe1003f9bafa45a1d2f
libs/exoplayer-common-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-common 2.19.1 43a1b8b5a978a6b702c18da890f8246c1aa560eb849bfdd1195bcf88c4547379
libs/exoplayer-container-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-container 2.19.1 4ec1d9dd09960a08a742bd69ccb47140ca4ee565cfb501288c4b5397d86a469f
libs/exoplayer-core-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-core 2.19.1 32912eac809c92f09e16cedc1981e1fee34041400e4dbdd0196f98470409b163
libs/exoplayer-database-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-database 2.19.1 755713eb73af98f300324c32461e0d575e3f1aea5181ae8fcb3b3a8b1e2e5da3
libs/exoplayer-datasource-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-datasource 2.19.1 9a21e83eb54cccf4cd20a1760d5d7f6ea00b89dff9382399db8446c7f05b16ec
libs/exoplayer-decoder-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-decoder 2.19.1 f2792fadc0e26f2a3a6d3c9bc10b3c1c13651cb9377a1756b46f0908110ff17a
libs/exoplayer-extractor-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-extractor 2.19.1 1b6efc380df5d224b0967526bc230095c71bc722a608280c4985a27972c22eb9
libs/exoplayer-hls-2.19.1.jar $GOOGLE com/google/android/exoplayer/exoplayer-hls 2.19.1 74c4271fd92e15a86c2645aea192a04f3e24d39349b5c5951539fcce28f2296a
libs/guava-31.1-android.jar $CENTRAL com/google/guava/guava 31.1-android 32ac2ed709d96d278b5d2e3e5cea178fa4939939c525fb647532f013308db309
EOF
echo "fetch-libs: all present and verified"
