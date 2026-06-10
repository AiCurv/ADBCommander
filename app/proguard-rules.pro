# Keep libadb-android classes
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# Keep BouncyCastle crypto classes used by ADB pairing
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Conscrypt classes
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Keep ADB key/certificate classes
-keep class com.adbcommander.AdbManager$AdbConnectionManager { *; }
