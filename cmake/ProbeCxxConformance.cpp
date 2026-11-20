#include <iostream>
#include <version>

#if __has_include(<expected>)
#include <expected>
#endif
#if __has_include(<mdspan>)
#include <mdspan>
#endif
#if __has_include(<format>)
#include <format>
#endif
#if __has_include(<generator>)
#include <generator>
#endif
#if __has_include(<flat_map>)
#include <flat_map>
#endif
#if __has_include(<inplace_vector>)
#include <inplace_vector>
#endif
#if __has_include(<simd>)
#include <simd>
#endif

#define TL_STRINGIFY_INNER(value) #value
#define TL_STRINGIFY(value) TL_STRINGIFY_INNER(value)

int main() {
    std::cout << "#pragma once\n";
    std::cout << "#define TL_CXX_FEATURE_PROBE_CPLUSPLUS " << __cplusplus << "L\n";
#ifdef __cpp_lib_expected
    std::cout << "#define TL_HAVE_STD_EXPECTED 1\n";
#else
    std::cout << "#define TL_HAVE_STD_EXPECTED 0\n";
#endif
#ifdef __cpp_lib_mdspan
    std::cout << "#define TL_HAVE_STD_MDSPAN 1\n";
#else
    std::cout << "#define TL_HAVE_STD_MDSPAN 0\n";
#endif
#ifdef __cpp_lib_format
    std::cout << "#define TL_HAVE_STD_FORMAT 1\n";
#else
    std::cout << "#define TL_HAVE_STD_FORMAT 0\n";
#endif
#ifdef __cpp_lib_generator
    std::cout << "#define TL_HAVE_STD_GENERATOR 1\n";
#else
    std::cout << "#define TL_HAVE_STD_GENERATOR 0\n";
#endif
#ifdef __cpp_lib_flat_map
    std::cout << "#define TL_HAVE_STD_FLAT_MAP 1\n";
#else
    std::cout << "#define TL_HAVE_STD_FLAT_MAP 0\n";
#endif
#ifdef __cpp_lib_inplace_vector
    std::cout << "#define TL_HAVE_STD_INPLACE_VECTOR 1\n";
#else
    std::cout << "#define TL_HAVE_STD_INPLACE_VECTOR 0\n";
#endif
#ifdef __cpp_lib_simd
    std::cout << "#define TL_HAVE_STD_SIMD 1\n";
#else
    std::cout << "#define TL_HAVE_STD_SIMD 0\n";
#endif
    return 0;
}
