# SPDX-License-Identifier: AGPL-3.0-only

# Both are reached only from the native library through JNI, which the shrinker cannot see.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
-keep class app.mumcord.mobile.platform.securestore.SystemCertificates { *; }
