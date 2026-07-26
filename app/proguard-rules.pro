# ProGuard rules for Vokii Translator
-keepattributes *Annotation*, Signature, Exception
-keep class com.vokii.translator.** { *; }
# HMS keep rules removed — the v2 architecture is HMS-free (no HMS dependency,
# no agconnect-services.json, no com.huawei.* imports). The rules below were
# carried over from the pre-refactor build and kept a phantom package.
# -dontwarn com.huawei.hms.**
# -keep class com.huawei.hms.** { *; }