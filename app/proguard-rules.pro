# ProGuard rules for Vokii Translator
-keepattributes *Annotation*, Signature, Exception
-keep class com.vokii.translator.** { *; }
-dontwarn com.huawei.hms.**
-keep class com.huawei.hms.** { *; }