# SPDX-License-Identifier: AGPL-3.0-only

# Called from the C++ library by name when a stream is lost.
-keepclasseswithmembers class app.mumcord.mobile.platform.audio.NativeAudio {
    native <methods>;
    public static void onStreamError(int, int, int);
}
