# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.31)
project(mumcord_video_gl LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(mumcord_video_gl SHARED video_gl.cpp)

# The engine's C functions are declared in the bridge's header. They are looked up in the
# engine's library at run time (see video_gl.cpp), so nothing is linked against it here.
target_include_directories(mumcord_video_gl PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../bridge/include")
target_compile_options(mumcord_video_gl PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
target_link_libraries(mumcord_video_gl PRIVATE GLESv3 log)
