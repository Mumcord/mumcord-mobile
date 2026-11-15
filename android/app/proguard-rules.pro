# SPDX-License-Identifier: AGPL-3.0-only
#
# R8 rules of the release build. Code shrinking removes and renames everything the Kotlin code
# does not reach by name, so everything that native code or reflection reaches by name has to
# be kept here. None of these libraries ships its own rules.

# JNA reads the generated bindings by reflection (the library interface, structures and
# callbacks) and looks up its own classes from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
# JNA's desktop helpers refer to AWT, which Android does not have; they are never called here.
-dontwarn java.awt.**

# The UniFFI bindings of the engine (package app.mumcord.mobile.bridge): JNA maps their
# functions, structures and callback interfaces by name.
-keep class app.mumcord.mobile.bridge.** { *; }

# The certificate verifier of the engine (Rust crate rustls-platform-verifier) calls its Kotlin
# half through JNI by class and method name.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }

# The audio library (android/platform/audio, audio_io.cpp) reports stream errors by calling
# this method by name.
-keep class app.mumcord.mobile.platform.audio.NativeAudio {
    static void onStreamError(int, int, int);
}
