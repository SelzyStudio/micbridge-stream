# load common dependencies
# this file will also load platform specific dependencies

# boost, this should be before Simple-Web-Server as it also depends on boost
include(dependencies/Boost_Sunshine)

# submodules
# moonlight common library
set(ENET_NO_INSTALL ON CACHE BOOL "Don't install any libraries built for enet")
add_subdirectory("${CMAKE_SOURCE_DIR}/third-party/moonlight-common-c/enet")

# web server
add_subdirectory("${CMAKE_SOURCE_DIR}/third-party/Simple-Web-Server")

# libdisplaydevice
add_subdirectory("${CMAKE_SOURCE_DIR}/third-party/libdisplaydevice")

# common dependencies
include("${CMAKE_MODULE_PATH}/dependencies/nlohmann_json.cmake")
find_package(OpenSSL REQUIRED)
find_package(PkgConfig REQUIRED)
find_package(Threads REQUIRED)
pkg_check_modules(CURL REQUIRED libcurl)

# miniupnp
pkg_check_modules(MINIUPNP miniupnpc REQUIRED)
include_directories(SYSTEM ${MINIUPNP_INCLUDE_DIRS})

# MicBridge pinned source-built dependencies (no historical prebuilt codec fallback).
if(NOT DEFINED FFMPEG_PREPARED_BINARIES)
    message(FATAL_ERROR "Set FFMPEG_PREPARED_BINARIES to the pinned source-built codec prefix")
endif()
if(WIN32)
    set(FFMPEG_PLATFORM_LIBRARIES mfplat ole32 strmiids mfuuid vpl)
endif()
set(FFMPEG_LIBRARIES
    "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libswscale.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libavutil.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libcbs.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libSvtAv1Enc.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libx264.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libx265.a"
    "${FFMPEG_PREPARED_BINARIES}/lib/libhdr10plus.a"
    ${FFMPEG_PLATFORM_LIBRARIES})

set(FFMPEG_INCLUDE_DIRS
        "${FFMPEG_PREPARED_BINARIES}/include")

# platform specific dependencies
if(WIN32)
    include("${CMAKE_MODULE_PATH}/dependencies/windows.cmake")
elseif(UNIX)
    include("${CMAKE_MODULE_PATH}/dependencies/unix.cmake")

    if(APPLE)
        include("${CMAKE_MODULE_PATH}/dependencies/macos.cmake")
    else()
        include("${CMAKE_MODULE_PATH}/dependencies/linux.cmake")
    endif()
endif()
