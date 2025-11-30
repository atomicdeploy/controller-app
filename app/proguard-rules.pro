# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room entities
-keep class com.atomicdeploy.controller.data.model.** { *; }

# Keep JmDNS
-keep class javax.jmdns.** { *; }

# Preserve line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Keep generic type information
-keepattributes Signature
