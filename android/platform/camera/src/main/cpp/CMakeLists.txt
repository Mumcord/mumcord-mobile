# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.31)
project(mumcord_camera_push LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(mumcord_camera_push SHARED camera_push.cpp)

# The engine's C function is declared in the bridge's header. It is looked up in the engine's
# library at run time (see camera_push.cpp), so nothing is linked against it here.
target_include_directories(mumcord_camera_push PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../bridge/include")
target_compile_options(mumcord_camera_push PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
target_link_libraries(mumcord_camera_push PRIVATE log)
