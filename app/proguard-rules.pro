# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.lightningds.**$$serializer { *; }
-keepclassmembers class com.lightningds.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# jaudiotagger: frame bodies and tag fields are created by reflection
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
# smbj and its event bus (mbassador uses annotations/reflection)
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
# BouncyCastle (MD4/HMAC/AES-CMAC used by SMB authentication and signing)
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**
