# jutf7 registers its charset through the java.nio.charset.spi.CharsetProvider
# service; the provider and its charsets are only reached via ServiceLoader.
-keep class com.beetstra.jutf7.** { *; }

# The vendored mail stack logs through a pluggable facade and builds MIME
# objects by name in a few places; keep it intact rather than chasing
# individual reflection sites. The size win from shrinking it is small.
-keep class com.fsck.k9.mail.** { *; }
-keep class net.thunderbird.** { *; }

# Compile-time-only annotations and JVM-only classpath references pulled in by
# the vendored stack's dependencies.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.naming.**
-dontwarn org.apache.hc.client5.http.impl.**
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.conscrypt.**
-dontwarn org.brotli.dec.**
-dontwarn com.squareup.moshi.**
