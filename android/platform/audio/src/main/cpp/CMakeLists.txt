# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.31)
project(mumcord_audio_io LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(mumcord_audio_io SHARED audio_io.cpp)

# The engine's C functions are declared in the bridge's header. They are looked up in the
# engine's library when the app starts (see audio_io.cpp), so nothing is linked against it here.
target_include_directories(mumcord_audio_io PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../bridge/include")
target_compile_options(mumcord_audio_io PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
target_link_libraries(mumcord_audio_io PRIVATE aaudio log)

# The test probe (a generated signal in place of the microphone, a level meter on the speaker
# side, a simulated loss of a stream) exists only in debug builds. Release builds do not contain
# its code or its symbols.
target_compile_definitions(mumcord_audio_io PRIVATE "$<$<CONFIG:Debug>:MUMCORD_AUDIO_PROBE>")
