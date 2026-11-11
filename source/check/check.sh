#!/bin/sh
# CarPlay Ultra-Lite compatibility check. Runs a read-only check on the head unit over adb and copies the
# report next to this script (report/cp-climate-check.md and .json). Nothing on the head unit is changed;
# the checker and its cache are put in /data/local/tmp and removed again.
#
# usage: sh check.sh                      as adb's shell user (enough for every check except who signed the
#                                         installed mods and their settings)
#        sh check.sh --root "su -c"       also as root, with the command your unit uses to run a shell command
#                                         as root (for example "su -c", or "/data/local/tmp/nemesis-d root -c")
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR="$HERE/cp-climate-check.jar"
DEV_JAR=/data/local/tmp/cp-climate-check.jar
DEV_OUT=/data/local/tmp/cp-climate-check
DEV_DATA=/data/local/tmp/cp-climate-check-data
ROOT=""
if [ "$1" = "--root" ]; then ROOT="$2"; fi

if ! adb get-state >/dev/null 2>&1; then
    echo "No head unit on adb. Connect it (USB, or HMG-API's adb mod) and run this again."
    exit 1
fi
adb push "$JAR" "$DEV_JAR" >/dev/null
# app_process as the shell user can't write /data/dalvik-cache, so the jar gets a cache of its own
RUN="rm -rf $DEV_OUT $DEV_DATA; mkdir -p $DEV_DATA/dalvik-cache; ANDROID_DATA=$DEV_DATA CLASSPATH=$DEV_JAR app_process /system/bin ioniq6.cpclimate.check.Main $DEV_OUT; chmod -R 755 $DEV_OUT"
if [ -n "$ROOT" ]; then
    adb shell "$ROOT \"$RUN\""
else
    adb shell "$RUN"
fi
mkdir -p "$HERE/report"
adb pull "$DEV_OUT/cp-climate-check.md" "$HERE/report/" >/dev/null
adb pull "$DEV_OUT/cp-climate-check.json" "$HERE/report/" >/dev/null
if [ -n "$ROOT" ]; then
    adb shell "$ROOT \"rm -rf $DEV_DATA $DEV_JAR\"" >/dev/null 2>&1 || true
else
    adb shell "rm -rf $DEV_DATA $DEV_JAR" >/dev/null 2>&1 || true
fi
echo
echo "Report: $HERE/report/cp-climate-check.md"
echo "Send report/cp-climate-check.md and report/cp-climate-check.json back, or give them to your own Claude."
