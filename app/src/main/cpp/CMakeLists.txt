cmake_minimum_required(VERSION 3.22.1)
project("surveillance")

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

# ==================== Precompiled FastCam Client ====================
add_library(fast_cam_client SHARED IMPORTED)
set_target_properties(fast_cam_client PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libfast_cam_client.so"
)

# ==================== Surveillance Library ====================
set(SURVEILLANCE_SOURCES
    surveillance/native_motion.cpp
    surveillance/motion_pipeline_v2.cpp
    surveillance/texture_tracker.cpp
    # Camera/EGL: AHardwareBuffer → EGLImage → GL_TEXTURE_EXTERNAL_OES
    # binding for the ImageReader-based zero-copy camera path.
    camera/HardwareBufferTextureBinder.cpp
    # DiLink 5 Qualcomm fast_cam_capture JNI bridge
    camera/qcarcam_bridge.cpp
)

add_library(surveillance SHARED ${SURVEILLANCE_SOURCES})

target_include_directories(surveillance PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/surveillance
    ${CMAKE_SOURCE_DIR}/camera
    ${CMAKE_SOURCE_DIR}/include
)

# Base libraries — `EGL` and `GLESv2` added for HardwareBufferTextureBinder.
set(SURVEILLANCE_LIBS android log m EGL GLESv2 fast_cam_client)

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

# ==================== od library ====================
# Standalone utility library with host-authorization gate. Same hardening as surveillance:
# all symbols hidden, fully stripped, sections GC'd — only the two JNI entry points remain.
#
# od/od.cpp is a closed component and is NOT part of this repository. When it is
# absent (clean public clone), the prebuilt libod.so in jniLibs/ is used at runtime
# and this target is skipped so the native build still configures and completes.
if(EXISTS ${CMAKE_SOURCE_DIR}/od/od.cpp)
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
else()
    message(STATUS "od/od.cpp not present — skipping 'od' target; prebuilt libod.so in jniLibs/ is used at runtime.")
endif()

# ==================== NEON SIMD Optimization ====================
# Enable NEON for ARM architectures
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARM64: NEON is mandatory, but we need to define the macro explicitly
    target_compile_definitions(surveillance PRIVATE __ARM_NEON__=1)
    target_compile_options(surveillance PRIVATE -O3 -ffast-math)
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
