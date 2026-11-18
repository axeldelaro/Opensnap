# OpenSnap R8 / ProGuard Security & Compatibility Rules

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.axeldelaro.opensnap.**$$serializer { *; }
-keepclassmembers class io.github.axeldelaro.opensnap.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.axeldelaro.opensnap.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep SQLCipher JNI & Room classes
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Keep BouncyCastle cryptographic providers
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep WebRTC native JNI bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep ZXing QR Core
-keep class com.google.zxing.** { *; }
