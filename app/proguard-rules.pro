# Add project specific ProGuard rules here.
# Keep all classes referenced by reflection (AccessibilityService declared in manifest).
-keep class com.zai.autoclicker.** { *; }

# Kotlin metadata
-dontwarn kotlin.**
