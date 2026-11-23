# R8 shrinks the LIBRARIES only (mainly material-icons-extended, ~40 MB of unused icons in the APK).
# The app's own code is kept whole and nothing is renamed, so reflection, JSON field names, Room,
# WorkManager workers, widgets and receivers behave exactly as in the unshrunk build.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class com.tamir.hamasa.** { *; }

# Samsung Health Data SDK (local AAR, no consumer rules) + its Gson runtime: reflection everywhere.
-keep class com.samsung.** { *; }
-keep interface com.samsung.** { *; }
-dontwarn com.samsung.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Parcelable CREATOR fields used through reflection.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Common optional annotations / classes some libraries reference.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.parcelize.**
-dontwarn org.slf4j.**
