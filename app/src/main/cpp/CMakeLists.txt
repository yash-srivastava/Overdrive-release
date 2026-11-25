cmake_minimum_required(VERSION 3.22.1)
project("surveillance")

# Strip the checkout location from __FILE__ and debug paths so native libraries are the same
# wherever they are built (local folder, private overlay or CI) and carry no machine paths.
get_filename_component(OD_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${OD_REPO_ROOT}/=")

set(CMAKE_CXX_STANDARD 17)

# NOTE: Secrets library removed - encryption now uses pure Java (Safe.java)
# This provides 100% stability for app_process daemons at the cost of
# slightly lower security (Java vs native obfuscation).

# ==================== OpenCV-mobile (Static Linking) ====================
# opencv-mobile provides static libraries (~3MB total vs ~20MB shared)
# https://github.com/nihui/opencv-mobile
set(OPENCV_DIR "${CMAKE_SOURCE_DIR}/opencv")
set(OPENCV_LIB_DIR "${OPENCV_DIR}/lib/${ANDROID_ABI}")
set(OPENCV_INCLUDE_DIR "${OPENCV_DIR}/include")

# Check for opencv-mobile static libs AND headers
if(EXISTS "${OPENCV_LIB_DIR}/libopencv_core.a" AND EXISTS "${OPENCV_INCLUDE_DIR}/opencv2")
    message(STATUS "opencv-mobile found at ${OPENCV_LIB_DIR}")
    set(OPENCV_AVAILABLE TRUE)
    
    # opencv-mobile static libraries (order matters for linking!)
    # Link in reverse dependency order: video -> imgproc -> core
    set(OPENCV_LIBS
        "${OPENCV_LIB_DIR}/libopencv_video.a"
        "${OPENCV_LIB_DIR}/libopencv_imgproc.a"
        "${OPENCV_LIB_DIR}/libopencv_core.a"
    )
    
    # Verify all required libs exist
    foreach(lib ${OPENCV_LIBS})
        if(NOT EXISTS ${lib})
            message(WARNING "Missing opencv-mobile lib: ${lib}")
            set(OPENCV_AVAILABLE FALSE)
        endif()
    endforeach()
elseif(EXISTS "${OPENCV_LIB_DIR}/libopencv_java4.so")
    # Fallback: old shared library
    message(STATUS "OpenCV shared lib found (consider switching to opencv-mobile for smaller APK)")
    set(OPENCV_AVAILABLE TRUE)
    set(OPENCV_USE_SHARED TRUE)
    add_library(opencv SHARED IMPORTED)
    set_target_properties(opencv PROPERTIES IMPORTED_LOCATION "${OPENCV_LIB_DIR}/libopencv_java4.so")
else()
    message(WARNING "OpenCV not found or incomplete. Libs: ${OPENCV_LIB_DIR}, Headers: ${OPENCV_INCLUDE_DIR}/opencv2")
    message(WARNING "Run: ./gradlew :app:downloadOpenCV")
    set(OPENCV_AVAILABLE FALSE)
endif()

# ==================== Surveillance Library ====================
set(SURVEILLANCE_SOURCES
    surveillance/native_motion.cpp
    surveillance/rgba_readback.cpp
    surveillance/motion_pipeline_v2.cpp
    surveillance/texture_tracker.cpp
    # Camera/EGL: AHardwareBuffer → EGLImage → GL_TEXTURE_EXTERNAL_OES
    # binding for the ImageReader-based zero-copy camera path.
    camera/HardwareBufferTextureBinder.cpp
    # CPU recording engine: camera strip -> encoder YUV 2x2 grid + overlay.
    camera/CpuMosaicCompositor.cpp
)

add_library(surveillance SHARED ${SURVEILLANCE_SOURCES})

target_include_directories(surveillance PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/surveillance
    ${CMAKE_SOURCE_DIR}/camera
)

# Base libraries — `EGL` and `GLESv2` added for HardwareBufferTextureBinder.
set(SURVEILLANCE_LIBS android log m EGL GLESv2)

# Add OpenCV if available
if(OPENCV_AVAILABLE)
    target_include_directories(surveillance PRIVATE ${OPENCV_DIR}/include)
    
    if(OPENCV_USE_SHARED)
        # Old shared library path
        list(APPEND SURVEILLANCE_LIBS opencv)
    else()
        # opencv-mobile static linking
        # Static libs need to be linked in dependency order + system libs
        # Add OpenMP from NDK (required by OpenCV built with OpenMP)
        find_library(OPENMP_LIB omp)
        if(OPENMP_LIB)
            list(APPEND SURVEILLANCE_LIBS ${OPENCV_LIBS} ${OPENMP_LIB} z)
        else()
            # Fallback: link against static omp from NDK
            list(APPEND SURVEILLANCE_LIBS ${OPENCV_LIBS} -fopenmp -static-openmp z)
            # OpenMP is static here; drop the build machine's toolchain dir clang adds as RUNPATH.
            list(APPEND SURVEILLANCE_LIBS -fno-openmp-implicit-rpath)
        endif()
    endif()
    
    target_compile_definitions(surveillance PRIVATE HAVE_OPENCV=1)
else()
    target_compile_definitions(surveillance PRIVATE HAVE_OPENCV=0)
endif()

target_link_libraries(surveillance ${SURVEILLANCE_LIBS})

# ==================== Hardening for Surveillance Library ====================
# Hide all symbols except JNI exports (JNIEXPORT uses __attribute__((visibility("default"))))
# This makes reverse engineering much harder - internal functions are not visible in .so
target_compile_options(surveillance PRIVATE
    -fvisibility=hidden          # Hide all symbols by default
    -fvisibility-inlines-hidden  # Hide inline functions
    -ffunction-sections          # Put each function in its own section
    -fdata-sections              # Put each data item in its own section
)

# Force NDEBUG in release configs (same rationale as the od target below):
# don't rely on the toolchain's implicit default to strip debug-only paths.
target_compile_definitions(surveillance PRIVATE
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:RelWithDebInfo>:NDEBUG>
)

# Strip symbols and remove unused sections in release
set_target_properties(surveillance PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-all,--exclude-libs,ALL"
)

# ==================== DiLink 5 Camera Library ====================
# Keep the direct camera bridge out of libsurveillance so DiLink 3/4 retain
# their existing native load graph.
#
# Production safety policy: use the app-owned CPU copy + GL_TEXTURE_2D upload
# path from the first frame. The direct DMA-BUF/EGLImage compositor remains
# compiled for controlled experiments, but can only be selected by changing
# this code flag to 1 and rebuilding the APK.
set(OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA 0)

add_library(fast_cam_release_guard SHARED
    camera/fast_cam_release_guard.c
)
target_include_directories(fast_cam_release_guard PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)
target_link_libraries(fast_cam_release_guard dl)
target_compile_options(fast_cam_release_guard PRIVATE
    -ffunction-sections
    -fdata-sections
)
set_target_properties(fast_cam_release_guard PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,-z,relro,-z,now"
)

add_library(dilink5_camera SHARED
    camera/fast_cam_bridge.cpp
    camera/qcarcam_bridge.cpp
)
target_include_directories(dilink5_camera PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/camera
    ${CMAKE_SOURCE_DIR}/include
)
target_link_libraries(dilink5_camera
    android
    EGL
    GLESv2
    log
    m
)
target_compile_options(dilink5_camera PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_compile_definitions(dilink5_camera PRIVATE
    OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA=${OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA}
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:RelWithDebInfo>:NDEBUG>
)
set_target_properties(dilink5_camera PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-all,--exclude-libs,ALL"
)

# ==================== Camera2 360 Library ====================
# NDK Camera2 bridge for the camera2 mode only; no other mode loads it.
add_library(camera2_avm SHARED
    camera/camera2_avm_bridge.cpp
)
target_link_libraries(camera2_avm
    camera2ndk
    android
    log
)
target_compile_options(camera2_avm PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_compile_definitions(camera2_avm PRIVATE
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:RelWithDebInfo>:NDEBUG>
)
set_target_properties(camera2_avm PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-all,--exclude-libs,ALL"
)

# ==================== od library ====================
# Standalone utility library with host-authorization gate. Same hardening as surveillance:
# all symbols hidden, fully stripped, sections GC'd — only the two JNI entry points remain.
add_library(od SHARED od/od.cpp)
target_include_directories(od PRIVATE ${CMAKE_SOURCE_DIR}/od)
target_link_libraries(od android log m)
target_compile_options(od PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -O3 -ffast-math
)
# Force NDEBUG in release configs so the #ifndef NDEBUG host-auth bypass in
# od.cpp is preprocessed out (don't rely on the toolchain's implicit default).
target_compile_definitions(od PRIVATE
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:RelWithDebInfo>:NDEBUG>
)
set_target_properties(od PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-all,--exclude-libs,ALL"
)

# Optional private transport. Public builds package its verified prebuilt;
# source builds must not also supply a jniLibs copy of the same library.
set(OD_PRIVATE_PARKED "${CMAKE_CURRENT_SOURCE_DIR}/../../../../private/app/src/main/cpp/parked")
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/parked/CMakeLists.txt")
    add_subdirectory(parked)
elseif(EXISTS "${OD_PRIVATE_PARKED}/CMakeLists.txt")
    # Official build: the private overlay supplies the source; the public prebuilt
    # (src/oss/jniLibs) is not on this build's jniLibs path and must not be in main.
    file(GLOB OD_MAIN_PARKED_COPIES "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/*/libodpark.so")
    if(OD_MAIN_PARKED_COPIES)
        message(FATAL_ERROR "Remove libodpark.so from src/main/jniLibs: ${OD_MAIN_PARKED_COPIES}")
    endif()
    add_subdirectory("${OD_PRIVATE_PARKED}" parked)
endif()

# ==================== NEON SIMD Optimization ====================
# Enable NEON for ARM architectures
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARM64: NEON is mandatory, but we need to define the macro explicitly
    target_compile_definitions(surveillance PRIVATE __ARM_NEON__=1)
    target_compile_options(surveillance PRIVATE -O3 -ffast-math)
    target_compile_definitions(dilink5_camera PRIVATE __ARM_NEON__=1)
    target_compile_options(dilink5_camera PRIVATE -O3 -ffast-math)
    message(STATUS "NEON enabled for arm64-v8a")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # ARM32: Enable NEON with compiler flags
    target_compile_options(surveillance PRIVATE -O3 -ffast-math -mfpu=neon -mfloat-abi=softfp)
    target_compile_definitions(surveillance PRIVATE __ARM_NEON__=1)
    message(STATUS "NEON enabled for armeabi-v7a")
else()
    # x86/x86_64: No NEON
    target_compile_options(surveillance PRIVATE -O3 -ffast-math)
    message(STATUS "NEON not available for ${ANDROID_ABI}")
endif()

# ==================== Voice front-end ====================
# The voice assistant's car-microphone input: SpeexDSP echo cancelling and noise suppression over
# the two cabin mics and the speaker reference. Loaded only when that input is selected.
set(SPEEXDSP_DIR ${CMAKE_SOURCE_DIR}/third_party/speexdsp)
add_library(voice_frontend SHARED
    voice/voice_frontend.c
    ${SPEEXDSP_DIR}/libspeexdsp/mdf.c
    ${SPEEXDSP_DIR}/libspeexdsp/preprocess.c
    ${SPEEXDSP_DIR}/libspeexdsp/filterbank.c
    ${SPEEXDSP_DIR}/libspeexdsp/fftwrap.c
    ${SPEEXDSP_DIR}/libspeexdsp/kiss_fft.c
    ${SPEEXDSP_DIR}/libspeexdsp/kiss_fftr.c
)
target_include_directories(voice_frontend PRIVATE
    ${CMAKE_SOURCE_DIR}/voice
    ${SPEEXDSP_DIR}/include
    ${SPEEXDSP_DIR}/libspeexdsp
)
# SpeexDSP's configure switches, given here instead of a config.h: floating point, bundled KISS FFT.
target_compile_definitions(voice_frontend PRIVATE
    FLOATING_POINT
    USE_KISS_FFT
    EXPORT=
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:RelWithDebInfo>:NDEBUG>
)
target_compile_options(voice_frontend PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -O2
)
target_link_libraries(voice_frontend m)
set_target_properties(voice_frontend PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-all,--exclude-libs,ALL"
)
