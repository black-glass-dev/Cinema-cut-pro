cmake_minimum_required(VERSION 3.22)
project(timeline_core_v2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(timeline_core_v2 STATIC
    src/timeline_core.cpp
)
target_include_directories(timeline_core_v2 PUBLIC include)
target_compile_features(timeline_core_v2 PUBLIC cxx_std_23)
target_compile_options(timeline_core_v2 PRIVATE
    -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Werror
)
set_target_properties(timeline_core_v2 PROPERTIES POSITION_INDEPENDENT_CODE ON)

option(TL_BUILD_TESTS "Build Timeline Core v2 host tests" ON)
if(TL_BUILD_TESTS)
    add_executable(timeline_core_v2_tests tests/timeline_core_test.cpp)
    target_link_libraries(timeline_core_v2_tests PRIVATE timeline_core_v2)
    target_compile_options(timeline_core_v2_tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Werror
    )
    add_test(NAME timeline_core_v2_tests COMMAND timeline_core_v2_tests)
endif()

option(TL_BUILD_BENCHMARKS "Build Timeline Core v2 host microbenchmarks" OFF)
if(TL_BUILD_BENCHMARKS)
    add_executable(timeline_core_v2_benchmark tests/timeline_core_benchmark.cpp)
    target_link_libraries(timeline_core_v2_benchmark PRIVATE timeline_core_v2)
    target_compile_options(timeline_core_v2_benchmark PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Werror
    )
endif()
