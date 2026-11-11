# cp-climate's own code: HMG-API loads its entry by name and it uses reflection on DiPO and itself
-keep class ioniq6.cpclimate.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# readable stack traces in the head unit's log
-dontobfuscate
# HMG-API's hook classes come from the head unit at run time. Nothing else may be missing: a blanket
# -dontwarn once hid that exoplayer-container wasn't in the build, and the first H.264 video crashed CarPlay
# (NoClassDefFoundError ParsableNalUnitBitArray, 2026-11-11). Android 4.4 API gaps are checked by check/api19.py.
-dontwarn hmgapi.hook.**
