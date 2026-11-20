# Agent Contract for Timeline Core v2

- Work only on the active Arena branch. Do not switch branches or push elsewhere.
- The C++ core is the sole authority for time/edit/snap/history semantics; Kotlin is UI/orchestration/persistence DTO only. No second edit-math implementation or silent fallback.
- Use checked integer `Flicks`/rational time and validate-then-commit. A rejected edit changes neither project nor history hash. Locked track/clip/group edits fail with stable error codes.
- Every semantic change needs named unit/property/regression tests. Never disable/delete tests or invent output/benchmark numbers. Record exact commands, exits, and logs.
- Keep C++23 as the portable baseline; probe C++26 and toolchain APIs on the actual compiler before using them. Verify all new dependency versions and licenses before pinning.
- JNI uses explicit `RegisterNatives` signatures; translate errors at the boundary; never allow C++ exceptions across JNI. Heavy work stays off the UI thread.
- Compose timeline must virtualize visible clips plus overscan and delegate edit/ghost math to the authoritative core.
- Run the available host gate after core changes. Run Android Gradle/native/device gates when their toolchains exist. Label unavailable device/performance assertions `UNVERIFIED`.
- End each implementation phase with a conventional commit and a five-line `docs/WORKLOG.md` entry. Final report is in Bengali and contains reproducible evidence plus gaps.
