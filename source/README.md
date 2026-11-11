# CarPlay Ultra-Lite: source (climate and video branch)

The source for the **CarPlay Ultra-Lite** HMG-API mod (mod id `cp-climate`), as it is on the branch
`feature/climate-and-video`. It starts from the 1.0.0 release source and adds:

- **More climate in CarPlay:** rear seat heat, front seat ventilation (cooling), rear climate zones, the
  climate system's on/off state, and fixes found while testing in the car.
- **Video playback** (experimental): CarPlay's video playback feature (AirPlay video from the iPhone),
  played on the head unit with ExoPlayer through the iPhone's network.

The branch numbers its own builds **1.0.4 to 1.1.2**. That numbering is separate from the 1.3.x test builds in the
main README. Everything here was built and tested on one car: a **Hyundai Ioniq 6, CEEV, Canada, firmware
250818** (head unit build `CEEV.CAN.0000.V014.010.250818`), with iOS 27.2. This is an unofficial mod; use it at your own risk.

## Status

| Part | State |
|---|---|
| Climate as in 1.0.0 (temperatures, fan, A/C, AUTO, SYNC, vents, recirculation, front seat heat, defrost) | Works in the car |
| Car picture in Apple's Climate app (the phone builds the car from `/info`, see below) | Works in the car (1.0.6) |
| Front seat cooling levels −1 to −3 from CarPlay | Car follows each step (1.1.1). CarPlay's dots are drawn one step off; 1.1.2 tries holding back the echo |
| Rear seat heat; one rear zone (layout C) | Works in the car (1.0.7 to 1.1.0) |
| Two rear zones (layout D) | Works in the car (1.1.1); no car picture (the theme has no picture for those seats) |
| Climate off: last setpoint instead of the car's 17.0, or "no value" (`blank_when_off`) | 1.1.2, not yet tried in the car |
| Video: the phone's video messages reach the head unit and are answered | Works in the car (1.0.5) |
| Video: playback through the phone's proxy | 1.1.0 reached the proxy and was refused at the TLS handshake. 1.1.2 tries three handshakes and sends answers off the main thread; not yet tried in the car |

## Layout

```
mod/                    the HMG-API mod package source
  mod.json              id, version, systems, hook points, native recipe, network rules
  settings.json         settings shown in the HMG-API app (table below)
  build.sh              package step run by HMG-API's mkmodpkg.sh: merges the video engine into hooks.jar
  recipes/              carplay-climate-m1.recipe: libCarPlayCore.so memory patch (built by tools/build.py)
  src/ioniq6/cpclimate/ the Java hooks loaded into DiPO (the CarPlay receiver app)
  tools/video/          the video engine (ExoPlayer, BouncyCastle TLS), merged into hooks.jar by build.sh
    fetch-libs.sh       downloads its libraries and R8 (not kept in git) and checks their SHA-256
native/                 the code the recipe adds to libCarPlayCore.so (Thumb-2, no libc)
tools/                  build.py (native code to recipe), LIVI data generators, listener fingerprints
data/                   LIVI's vehicle state description (opack, bplist) and the Rust source that made it
tests/                  host tests (Java, C, Python)
check/                  the read-only compatibility check (cp-climate-check) and its adb runner
```

## How it works

### In DiPO's native library (`native/`, `mod/recipes/`)

`tools/build.py` compiles `native/seg.S` and `native/hooks.c` into one block. It appends the block to a copy of the
stock `libCarPlayCore.so` (MD5 `d3a47296437df56fb1ffc7ac3dfca4cb`) and branches into it from four places.
HMG-API's `mkmemrecipe.py` then turns the difference into a memory recipe, so the library on disk is never changed:

| Site | Stock code | What the hook does |
|---|---|---|
| `0x9c20` | `AirPlayCopyServerInfo` | `/info`: adds `vehicleStateProtocolInfo`, `displayPanels`, the bottom-dock `viewArea`, and `videoPlaybackInfo` |
| `0x12430` | SETUP reply | adds `enabledFeatures: [vehicleStateProtocol, videoPlayback]` when the phone offered them |
| `0x11718` | unsupported stream type | sets up the vehicle state and video playback streams |
| `0xaf10` | `POST /command` | answers the phone's video commands with 200 and passes them to Java (UDP 127.0.0.1:7136); a sender loop takes Java's answers (UDP 7137) and posts them to the phone with the stream's id |

`vehicleStateProtocolInfo` comes from `files/cpc-vspi.bplist`, which the Java side writes for the settings in use.
The LIVI copy embedded in the recipe is the fallback. The phone builds its car (seats, zones, picture) from this
description in `/info`, not from the vehicle state stream. That is why a wrong or stale description shows
Apple's placeholder instead of the car.

### In DiPO's Java (`mod/src/ioniq6/cpclimate/`)

| Class | Job |
|---|---|
| `CpClimate` | the HMG-API entry: reads settings, starts everything when DiPO loads |
| `VehicleState` | the vehicle state protocol (LIVI's model): the description, the values, the phone's register/read/write, update notifications, and holding a value the phone wrote while the car catches up |
| `CarClimate` | the car side. Once a second it reads DATCService (climate), USMService (seats, wheel) and BCMService (rear defrost) over Binder, and sends what the phone wrote as the stock screens' own calls |
| `ClimateMap` | car readings to CarPlay values and back (temperatures, fan steps, vents, seat states 2/3-5/6-8) |
| `VsChannel`, `OpackCodec`, `Opack`, `Bplist`, `ChaChaPoly` | the encrypted stream and its encodings |
| `VideoMessages`, `VideoUi` | video: the phone's messages from the native hook, answers back, and borrowing the screen for the player |
| `IapBridge`, `IapChannel`, `UiSync*`, `ClusterSink` | iAP2 and UI sync channels, the cluster panel |

### The video engine (`mod/tools/video/`)

When the phone queues a video (`insertPlayQueueItem` with a URL), the engine plays it with ExoPlayer 2.19.1 in a
full-screen overlay. The head unit has no internet, so every request goes through the phone's proxy. The proxy's address,
pre-shared key and authorization come from the phone's `setProxyParameters`. `PhoneProxy` does TLS with that key
(BouncyCastle, since Android 4.4 has no TLS 1.3), then `CONNECT host:port`. `TunnelHttp` speaks HTTPS through the tunnel
with an extra set of root certificates (`roots/`), because Android 4.4's store is out of date. The engine answers
the phone's playback messages (`playbackInfo`, seek, rate) the same way.

The phone's proxy refused the first handshake tried in the car (TLS 1.3, key bound to SHA-256). `PhoneProxy` now
tries TLS 1.3 with SHA-256 together with TLS 1.2 PSK suites, then TLS 1.3 with SHA-384, then TLS 1.2 PSK only. It keeps
the first one that works and logs each attempt.

Limits: no FairPlay-protected content (Apple TV+, most paid apps), and the head unit decodes H.264 only (no HEVC,
AC-3 or DTS).

### Climate additions in this branch

- **Extra seats** (`extra_seats`), in four layouts (`extras_layout`):
  - **A:** ventilation as Apple's separate SeatFan controls. iOS didn't show them.
  - **B:** ventilation as the cooling half of each front seat's own level (−1 to −3, car states 3 to 5), plus a rear zone.
  - **C:** as B, with the rear seats' heat as one control for the row (`seat_2ndRow`). Keeps the car picture.
  - **D:** as B, with rear left and rear right zones, each with its own seat. No car picture.
- **Rear zones:** Apple's Climate app makes one zone card per Temperature service. The car has no rear temperature, so
  the rear zones are read-only and show the driver's.
- **Climate on/off:** the cabin gets an HVACOn characteristic (iid 514), so the phone knows when the system is off.
- **Climate off:** the car reports 17.0 °C and an "invalid" flag (`0xff`) instead of the setpoint. The last setpoint seen while
  on is kept (`files/cpc-setpoints`). With `blank_when_off`, the zones get Apple's "no value" (`-6727`) instead.
- **Fixes:** OPACK's one-byte −1 (tag `0x07`) wasn't decoded, so writes of cooling level 1 were dropped. Seat cooling
  writes are now confirmed from the car's reading instead of echoed at once.

## Settings

| Key | Default | What it does |
|---|---|---|
| `carplay_ultra` | on | The CarPlay Ultra theme (needs the E-GMP CarPlay EV Routing mod's theme) |
| `rear_defrost` | on | CarPlay's rear window button runs the rear defrost (READY only) |
| `video_playback` | on | Offers CarPlay's video playback feature to the phone |
| `extra_seats` | off | Rear seat heat, front seat ventilation, steering wheel heat (experimental) |
| `extras_layout` | B | Layout A, B, C or D of the extra seat controls (above) |
| `rear_zone` | on | Rear climate zone(s), layouts B to D |
| `blank_when_off` | on | "No value" for the zone temperatures while the climate is off (test) |
| `wheel_heater` | off | Steering wheel heater button (CarPlay drew it over the vent position button) |
| `zone_power` | off | Per-seat power buttons. The passenger's is Driver Only; CarPlay drew the driver's over AUTO |
| `vehicle_variant` | DBX | Which theme car CarPlay draws (test) |
| `ambient_test` | off | A test setting that sends a colour to the mood light (experimental) |

Settings marked restart apply after the head unit restarts.

## Building

You need:
- a JDK (17 or later)
- the Android SDK with a platform jar (`ANDROID_HOME`)
- Python 3 with `pyelftools`
- LLVM's `clang` and `ld.lld` (for the native recipe)
- a checkout of HMG-API (built here with [crashahotrod/HMG-API](https://github.com/crashahotrod/HMG-API)) (for `tools/mkmodpkg.sh` and `tools/mkmemrecipe.py`)
- your own HMG-API author key

Packages you build are signed with your key, so HMG-API won't update a copy signed by someone else.

```sh
# 1. the video engine's libraries (about 32 MB, SHA-256 checked)
sh source/mod/tools/video/fetch-libs.sh

# 2. only if native/ changed: rebuild the recipe from the stock library pulled from your head unit
python3 source/tools/build.py <stock libCarPlayCore.so> <HMG-API checkout> <out dir> full
cp <out dir>/carplay-climate-m1.recipe source/mod/recipes/

# 3. the package: copy the mod into HMG-API's mods folder and run its packager
rsync -a --delete source/mod/ <HMG-API checkout>/mods/cp-climate/
cd <HMG-API checkout> && tools/mkmodpkg.sh mods/cp-climate <your author key>
# -> out/packages/cp-climate-<version>.zip; install it with the HMG-API app or `hmgapid engine install`
```

`build.sh` (run by `mkmodpkg.sh`) compiles the video engine against HMG-API's build of the mod's classes. R8 then
shrinks everything into one `classes.dex`, because Android 4.4's class loader reads only one. Without
`tools/video/src` the package keeps HMG-API's plain `hooks.jar`, with no video engine.

The compatibility check: `check/check.sh` runs `cp-climate-check.jar` over adb. Build the jar from `check/src`
(`javac --release 8` against the platform jar, then `d8 --min-api 19`), or take it from the release bundle.

## Tests

The tests run on the host; none needs the car.

```sh
AJ=$ANDROID_HOME/platforms/android-34/android.jar
javac -d /tmp/t -cp $AJ -sourcepath source/mod/src source/tests/ClimateMapTest.java
java -cp /tmp/t:$AJ ioniq6.cpclimate.ClimateMapTest   # others in tests/ take input files: see each one's main
mkdir -p /tmp/ht
cc -w -DCPC_HOST_TEST -DDIR=\"/tmp/ht/\" -DVSPI=\"source/data/vspi.bplist\" -I source/native \
   source/tests/host_test.c source/native/hooks.c -o /tmp/host_test && /tmp/host_test   # the native hooks
python3 source/mod/tools/video/check/api19.py classes.dex android-19/android.jar     # Android 4.4 API gaps
```

`mod/tools/video/tests` has host tests for the proxy handshake and the HTTP tunnel. They run against a local
BouncyCastle PSK server standing in for the phone.

## Credits and licence

GPL-3.0-or-later (`LICENSE` at the top of the repository).
- **Vehicle state protocol model:** based on [LIVI](https://github.com/f-io/LIVI) by Lasse Heitgres (GPL-3.0). See `data/LICENSE`,
  `data/NOTICE` and `data/LIVI-COMMIT`.
- **Libraries fetched at build time:**
  - BouncyCastle (MIT-style licence)
  - ExoPlayer, Guava and androidx.annotation (Apache 2.0)
  - R8 (BSD-3-Clause)
- **Root certificates in `roots/`:** public CA roots, as shipped by current operating systems.
