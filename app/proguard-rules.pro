# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose
-dontwarn org.jetbrains.**
-keep class androidx.compose.** { *; }
