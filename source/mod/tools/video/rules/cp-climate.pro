# cp-climate's own code: HMG-API loads its entry by name and it uses reflection on DiPO and itself
-keep class ioniq6.cpclimate.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# readable stack traces in the head unit's log
-dontobfuscate
# the libraries reference things Android 4.4 or this build doesn't have (unused paths)
-dontwarn **
-ignorewarnings
