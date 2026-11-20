# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.throwback.** {
    *** Companion;
}
-keepclasseswithmembers class app.throwback.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# JavaScript bridge methods called from injected scripts
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
