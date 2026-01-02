# JNA classes
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <methods>;
}

-keep class uniffi.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-dontwarn com.sun.jna.**
-dontwarn java.awt.**

-keepattributes *Annotation*,Signature,InnerClasses

-keep class org.mlm.mages.push.AppPushService { *; }
-keep class org.mlm.mages.push.RaiseToForegroundService { *; }

-dontwarn sun.misc.Unsafe

-keep class androidx.datastore.preferences.protobuf.** { *; }
-keep class androidx.datastore.preferences.PreferencesProto$** { *; }
