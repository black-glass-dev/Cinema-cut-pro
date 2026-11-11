---
name: kotlin-jni-boundary
description: Design safe, fast Kotlin↔C++ boundaries for an Android media app — RegisterNatives/fbjni registration, exception translation, opaque handle lifecycles, direct ByteBuffer/AHardwareBuffer zero-copy, thread ownership, and a virtualized Compose timeline that never computes edit math. Use when writing JNI wrappers, fixing native crashes or leaks at the boundary, moving heavy logic from Kotlin to C++, or building a high-performance Compose timeline/canvas.
license: MIT
---

# Kotlin ↔ JNI boundary

## 1. Registration

- Use `JNI_OnLoad` + `RegisterNatives` with explicit signatures, or `fbjni`'s `makeNativeMethod`.
  Name-mangled lookup (`Java_com_example_Foo_bar`) is forbidden: it silently breaks on R8 obfuscation.
- `@FastNative`/`@CriticalNative` only for primitive-only hot paths, and only with explicit registration.
- Cache `jclass`/`jmethodID` once (global refs), delete local refs in loops that create objects.

## 2. Errors

- C++ exceptions never cross the boundary. Wrap every entry point; translate to a Java exception
  (`IllegalStateException`/custom `TimelineException` with a stable error code).
- Preferred shape: `std::expected<T, EditError>` in C++, mapped to `Result`-like Kotlin types; errors carry
  a code enum + offending ids so the UI can show a useful message.
- Never swallow an error to keep the UI "happy" — silent success on a rejected edit is a data-loss bug.

## 3. Handles and lifetime

- Native objects returned as `long` pointers must be wrapped in `AutoCloseable` (or `Cleaner` as backstop),
  with a double-free guard and a documented owner thread.
- Every native session (`Player`, `Renderer`, `Exporter`) has explicit `create/start/stop/release`;
  leaks detected by ASan/LeakCanary in the instrumentation suite.
- Kotlin must not hold a reference to native memory beyond its owner's lifetime; snapshots are immutable
  value types (copy small structs, not pointers).

## 4. Zero copy

- `AHardwareBuffer` (from `ImageReader`/`MediaCodec` output) → `VkImage` via
  `VK_EXT_external_memory_android_hardware_buffer`; keep a reference until the GPU fence signals.
- `ByteBuffer.allocateDirect` for PCM and pixel staging; never `ByteArray` copies per frame.
- Avoid `Bitmap` round-trips in the preview loop entirely.

## 5. Threading

- One render thread, one audio callback, one worker pool (Taskflow). Document each thread's affinity in
  `docs/ARCHITECTURE.md`.
- UI thread only enqueues commands; heavy calls never run on it (ANR guard). Any blocking call from Kotlin
  into native must be named `...Blocking` and off the UI thread.

## 6. Compose timeline

- Virtualize: custom `LazyLayout`/`Layout` measuring only visible ± overscan clips; the tick ruler drawn
  with a single `Canvas` pass using `drawLine`/`drawText` on a native-backed snapshot.
- One gesture state machine (`Idle/Dragging/Trimming/Scrubbing/Rolling/Sliding`), mutually exclusive;
  every transition unit-tested; pan/zoom via `detectTransformGestures` with explicit anchor math.
- Ghost/preview state comes **from native** (`computeTrim`) — Compose must not compute trim math.
- Stability: use `@Immutable` state, `derivedStateOf` for visible ranges, keyed `items`, avoid allocations
  per frame; verify with a recomposition-count test and Macrobenchmark.
- Haptics on snap, TalkBack labels (clip name, position, duration), min 48 dp touch targets.

## 7. Checklist before merging JNI changes

- [ ] Registered in `JNI_OnLoad`, signature test exists
- [ ] Null/array-bounds checks and local-ref budget verified
- [ ] Exception path returns a Java exception, not a crash
- [ ] Handle lifecycle test (create/release/release) passes under ASan
- [ ] No blocking call on the UI thread
- [ ] Kotlin side has no duplicated edit semantics
- [ ] Benchmark shows the boundary is not the bottleneck (single crossing per frame, batched calls)
