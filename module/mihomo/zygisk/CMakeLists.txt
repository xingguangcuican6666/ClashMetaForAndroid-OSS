cmake_minimum_required(VERSION 3.22)
project(mihomo_zygisk)

# Build against NDK API 21 (Android 5.0)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Strip symbols to minimise .so size
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--strip-all")

add_library(mihomo_zygisk SHARED main.cpp)

target_include_directories(mihomo_zygisk PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(mihomo_zygisk
    log
    android
)

# Instruct the linker to keep only the Zygisk entry points
set_target_properties(mihomo_zygisk PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
