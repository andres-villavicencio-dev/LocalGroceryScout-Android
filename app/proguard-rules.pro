# Add project specific ProGuard rules here.
# Keep all Hilt entry points and Retrofit models
-keep class com.localscout.app.data.remote.dto.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
