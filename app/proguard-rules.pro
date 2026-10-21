# R8 rules for the release build. Libraries bring their own rules (kotlinx.serialization, OkHttp,
# WorkManager, Compose); these cover what they don't. Keep the mapping file next to each AAB.

# Ktor and its logging refer to JVM classes Android doesn't have; they aren't used at run time.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**

# Readable crash reports in Play Console (with the uploaded mapping).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
