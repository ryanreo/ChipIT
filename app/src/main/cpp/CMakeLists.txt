cmake_minimum_required(VERSION 3.22.1)
project("chipit_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_program(HOST_GLSLC glslc)
if(HOST_GLSLC)
    message(STATUS "ChipIT: glslc found at ${HOST_GLSLC}; enabling GGML_VULKAN")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    message(STATUS "ChipIT: glslc not found on host; building CPU ARM NEON backend")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(chipit_llama SHARED llama_jni.cpp)

target_compile_options(chipit_llama PRIVATE -Wno-deprecated-declarations)
target_link_libraries(chipit_llama PRIVATE llama android log)
