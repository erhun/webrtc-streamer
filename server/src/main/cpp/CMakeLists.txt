cmake_minimum_required(VERSION 3.18)
project(scrcpy_native CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WEBRTC_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../webrtc_materials)
set(WEBRTC_INCLUDE ${WEBRTC_ROOT}/include)

add_library(scrcpy_native SHARED
    scrcpy_passthrough_encoder.cc
    scrcpy_video_encoder_factory.cc
    scrcpy_opus_audio_encoder.cc
    scrcpy_audio_encoder_factory.cc
    scrcpy_peer_connection.cc
    jni_bridge.cc
)

target_include_directories(scrcpy_native PRIVATE
    ${WEBRTC_INCLUDE}
    ${WEBRTC_INCLUDE}/third_party/abseil-cpp
)

target_compile_definitions(scrcpy_native PRIVATE
    WEBRTC_POSIX
    WEBRTC_ANDROID
    WEBRTC_LINUX
    WEBRTC_ARCH_ARM64
)

target_compile_options(scrcpy_native PRIVATE
    -fno-rtti
    -fno-exceptions
)

target_link_libraries(scrcpy_native PRIVATE
    ${WEBRTC_ROOT}/lib/arm64-v8a/libjingle_peerconnection_so.so
)
