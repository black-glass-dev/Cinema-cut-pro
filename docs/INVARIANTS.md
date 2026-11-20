# Timeline Core v2 Invariant Matrix

`PARTIAL` means the named native host test passes but app/JNI migration or platform evidence remains incomplete. `UNVERIFIED` means source exists but its required Kotlin/Android gate has not run. `NOT IMPLEMENTED` means no implementation claim is made.

| ID | Invariant | Current test / evidence | Status |
|---|---|---|---|
| INV-01 | Clip duration is derived from its integer source-range duration. | `testINV01_DurationIsDerivedFromSourceRange`; shared `computeTrim` start/end regression. | PARTIAL |
| INV-02 | Clip end is start plus duration; project duration is the maximum clip end. | `testINV02_ClipEndAndProjectDurationUseIntegerFlicks`; split/roll/slide cases. | PARTIAL |
| INV-03 | Same-track overlap is rejected unless the track explicitly allows it. | `testINV03_OverlapsRejectUnlessTrackAllowsThem`. | PARTIAL |
| INV-04 | A rejected edit preserves the input project hash and history state. | `testINV04_RejectedMoveIsAtomicAndLockedClipCannotMove`, `testINV04_BatchedEditsAreAtomicAndHistoryHasSingleEntry`, `testINV04_NoOpDoesNotCreateOrClearHistory`; duplicate delete regression. | PARTIAL |
| INV-05 | Locked track, clip, and group state is respected by mutations. | `testINV05_TrackClipGroupAndRippleLocksRejectAtomically` verifies a locked member blocks another member's move/ripple follower and blocks insertion. `testINV05_GroupLockChangesAreAtomicAndNativeHistoryManaged` verifies group-wide lock/unlock, ungrouped single-clip behavior, immutable preview input, and native undo/redo. Kotlin payload/UI/serialization test sources remain unrun. | PARTIAL |
| INV-06 | Undo restores the exact prior project hash. | `testINV06_UndoRestoresExactProjectHash`, `testINV06_07_DeltaHistoryRoundTripsStructuralEditsAndClipOrder`, `testINV06_UndoRejectsExternalMutationWithoutChangingHistoryOrProject`; 10,000 randomized multi-track history operations. | PARTIAL |
| INV-07 | Redo restores the post-edit hash and history remains bounded. | `testINV07_RedoAndBoundedHistoryRestoreHashes`; 10,000 randomized multi-track history operations undo/redo and retained-history traversal. | PARTIAL |
| INV-08 | Trim ranges remain inside available media bounds. | `testINV08_TrimSourceBoundsRespectAvailableRange`, including exact-boundary and out-of-range cases. | PARTIAL |
| INV-09 | A minimum-duration violation rejects without clamping. | `testINV09_MinimumDurationRejectsRatherThanClamping`. | PARTIAL |
| INV-10 | Snap ordering is deterministic; any Kotlin mirror matches native over 10,000 cases. | `testINV10_SnapMatchesIndependentOracleForTenThousandCases` checks 10,000 varied native results against a separate host oracle. No Kotlin/native mirror or parity claim exists. | PARTIAL |
| INV-11 | Stateless APIs are pure; mutable history has explicit thread affinity. | `testINV11_PureEditsNeverMutateTheirInput`; `testINV11_OneClipTenThousandRandomUndoRedoCycles`; 10,000 randomized multi-track history operations. `TimelineHistory` is thread-confined; JNI session source holds a mutex. Android JNI ABI/link and thread behavior remain unverified. | PARTIAL |
| INV-12 | Project serialization is versioned and round-trips deterministically. | `testINV12_VersionedSerializationHasGoldenAndRoundTripCoverage` checks golden bytes, round-trip hash, unsupported version, truncation, flags, and trailing bytes; deterministic mutation tests are seeded and are not libFuzzer. | PARTIAL |
| INV-13 | Render-graph cache keys include source, parameters, and timebase. | No v2 render graph/cache-key model. | NOT IMPLEMENTED |
| INV-14 | Export frame matches preview at time T. | No v2 preview/export graph integration or parity evidence. | NOT IMPLEMENTED |
| INV-15 | Native load failure disables editing with no divergent fallback. | Source tests: `testINV15_NativeUnavailableRejectsPreviewCommitAndSnapWithoutFallback`, `testINV15_DragOnClipIsDisabledWhenNativeCoreIsUnavailable`, `clipEditGestureBecomesPanWhenNativeCoreIsUnavailable`. Kotlin/Android tests were not compiled or run. | UNVERIFIED |
| INV-16 | Zero-copy hardware-buffer lifetime is correct under sanitizers. | No v2 renderer or Android hardware-buffer path exists. | NOT IMPLEMENTED / UNVERIFIED |

Latest direct C++23 runner evidence is **170,624 assertions** (`./tools/run_timeline_core_tests.sh`). It does not establish Kotlin/JNI integration, Compose behavior, Android safety, or device performance.
