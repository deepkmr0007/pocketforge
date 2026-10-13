# R8 rules for the release build. Manifest components are kept by aapt2-generated rules.
# The app uses no reflection, serialization frameworks or JNI.
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Kotlin metadata is not needed at runtime (no kotlin-reflect).
-dontwarn kotlin.Metadata
