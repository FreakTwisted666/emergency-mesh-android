# Add project specific ProGuard rules here.
# Meshrabiya
-keep class com.ustadmobile.meshrabiya.** { *; }
-dontwarn com.ustadmobile.meshrabiya.**

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not kept.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ZXing
-keep class com.google.zxing.** { *; }
