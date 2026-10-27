# Mantem as classes do ML Kit e do AccessibilityService intactas.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.app.Service { *; }