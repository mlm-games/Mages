-keepattributes *Annotation*,Signature,InnerClasses

-keep class org.mlm.mages.push.AppPushService { *; }
-keep class org.mlm.mages.push.RaiseToForegroundService { *; }

-dontwarn sun.misc.Unsafe

-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

-keep class mages.** { *; } # Needed for jna start error!