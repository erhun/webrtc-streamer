cmake_minimum_required(VERSION 3.18)
project(scrcpy_native NONE)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
# Use the matching Chromium toolchain recipe, not incompatible generic NDK libc++.
add_custom_target(scrcpy_native ALL
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_LIST_DIR}/../../../tools/build_native.py
    WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}/../../../..
    VERBATIM)
