cmake_minimum_required(VERSION 3.22.1)
project("chipit_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# To enable full native llama.cpp compilation:
# 1. Install NDK + CMake in Android Studio SDK Manager
# 2. Clone llama.cpp into app/src/main/cpp/llama.cpp
# 3. Uncomment externalNativeBuild in app/build.gradle.kts

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    add_subdirectory(llama.cpp)
    set(HAS_LLAMA_CPP TRUE)
else()
    set(HAS_LLAMA_CPP FALSE)
endif()

add_library(chipit_llama SHARED llama_jni.cpp)

target_link_libraries(chipit_llama android log)
if(HAS_LLAMA_CPP)
    target_compile_definitions(chipit_llama PRIVATE CHIPIT_HAS_LLAMA=1)
    target_link_libraries(chipit_llama llama)
endif()
