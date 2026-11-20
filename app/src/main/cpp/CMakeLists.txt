# CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("videoeditor")

# The app target consumes the C++23 v2 core; keep host tests/benchmarks in the root CMake project.
set(TL_BUILD_TESTS OFF CACHE BOOL "Build Timeline Core v2 host tests" FORCE)
set(TL_BUILD_BENCHMARKS OFF CACHE BOOL "Build Timeline Core v2 host benchmarks" FORCE)
add_subdirectory(timeline_core_v2)

add_library(
        transform_engine
        SHARED
        native_transform_jni.cpp
        matrix_math.cpp
        transform_engine.cpp
        keyframe_interpolator.cpp
        crop_perspective.cpp
        motion_path.cpp
        blend_mode_processor.cpp
        native_motion_blur.cpp)

find_library(
        log-lib
        log)

find_library(
        mediandk-lib
        mediandk)

find_library(
        android-lib
        android)

if(NOT DEFINED FFMPEG_PREFIX)
    set(FFMPEG_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/ffmpeg-android/prefix")
endif()
set(FFMPEG_INCLUDE_DIR "${FFMPEG_PREFIX}/include")
if(NOT EXISTS "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h")
    message(FATAL_ERROR "FFmpeg headers are missing. Build the pinned LGPL FFmpeg dependencies first: ${FFMPEG_PREFIX}")
endif()

foreach(ffmpeg_component IN ITEMS avutil avcodec avformat swscale swresample)
    set(ffmpeg_library "${FFMPEG_PREFIX}/lib/lib${ffmpeg_component}.so")
    if(NOT EXISTS "${ffmpeg_library}")
        message(FATAL_ERROR "Required FFmpeg shared library is missing: ${ffmpeg_library}")
    endif()
    add_library("ffmpeg_${ffmpeg_component}" SHARED IMPORTED GLOBAL)
    set_target_properties("ffmpeg_${ffmpeg_component}" PROPERTIES
            IMPORTED_LOCATION "${ffmpeg_library}")
endforeach()

add_library(
        ffmpeg_decoder
        SHARED
        ffmpeg_decoder.cpp)
target_compile_features(ffmpeg_decoder PRIVATE cxx_std_17)
target_include_directories(ffmpeg_decoder PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(
        ffmpeg_decoder
        ffmpeg_avformat
        ffmpeg_avcodec
        ffmpeg_swscale
        ffmpeg_swresample
        ffmpeg_avutil
        ${mediandk-lib}
        ${android-lib}
        ${log-lib})

find_library(
        jnigraphics-lib
        jnigraphics)

# MotionCam Pro .mcraw decoder. The decoder itself is upstream C++17; the
# small Android-only JNI wrapper exposes RAW frames and metadata to Kotlin.
set(MOTIONCAM_DECODER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/motioncam_decoder)
add_library(
        motioncam_decoder
        STATIC
        ${MOTIONCAM_DECODER_ROOT}/lib/Decoder.cpp
        ${MOTIONCAM_DECODER_ROOT}/lib/RawData.cpp
        ${MOTIONCAM_DECODER_ROOT}/lib/RawData_Legacy.cpp)
target_include_directories(
        motioncam_decoder
        PUBLIC
        ${MOTIONCAM_DECODER_ROOT}/lib/include
        ${MOTIONCAM_DECODER_ROOT}/thirdparty)
target_compile_features(motioncam_decoder PUBLIC cxx_std_17)
# RAW demosaic and decompression run on every preview frame. Keep debug-device builds optimized
# enough to be usable while preserving native symbols for diagnosing issues.
target_compile_options(motioncam_decoder PRIVATE $<$<CONFIG:Debug>:-O2>)
set_target_properties(motioncam_decoder PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
        motioncam_preview
        SHARED
        mcraw_jni.cpp
        mcraw_preview_renderer.cpp)
target_include_directories(motioncam_preview PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(motioncam_preview PRIVATE cxx_std_17)
target_compile_options(motioncam_preview PRIVATE $<$<CONFIG:Debug>:-O2>)
target_link_libraries(
        motioncam_preview
        motioncam_decoder
        ${log-lib}
        ${jnigraphics-lib})

target_link_libraries(
        transform_engine
        ${log-lib}
        ${jnigraphics-lib})

add_library(
        export_core
        SHARED
        export_core.cpp
        render_frame.cpp
        transform_eval.cpp
        color_eval.cpp
        lut_processor.cpp
        frame_compositor.cpp
        blend_mode_processor.cpp
        audio_mixer.cpp
        native_export_jni.cpp)

target_link_libraries(
        export_core
        ${log-lib})

add_library(
        native_timeline_core
        SHARED
        timeline_core_v2_jni.cpp
)
target_compile_features(native_timeline_core PRIVATE cxx_std_23)
target_link_libraries(
        native_timeline_core
        timeline_core_v2
        ${log-lib}
)
