# Third-Party Software Inventory

This initial Timeline Core v2 host implementation adds no runtime third-party library. The existing app has a larger dependency inventory; this table records only relevant repository declarations and verified source identity, not a complete license audit.

| Dependency | Version/source | License | Use | Verification |
|---|---|---|---|---|
| C++ standard library | Host GCC 12.2; platform NDK pin in app is not installed | Toolchain-provided | Core language/runtime | Host C++23 `<expected>` probe passed; Android toolchain unverified. |
| FFmpeg | tag `n8.1.3`, commit `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7` | FFmpeg build script configures LGPL-only; verify final binary/options before distribution | Existing native media path | GitHub tag resolves to commit; no Android build performed. |
| GoogleTest / RapidCheck | No project pin added | N/A | Requested native unit/property testing | NOT ADDED; do not claim these suites ran. |

Before adding a dependency, record its upstream tag/SHA or resolved artifact, license, and reason. The full existing application dependency/license inventory is not completed by this v2 core slice.
