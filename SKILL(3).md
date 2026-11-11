---
name: media-pipeline-ffmpeg-vulkan
description: Build a native mobile media pipeline with FFmpeg 9 as software reference, MediaCodec for hardware decode/encode, and a Vulkan compositor with zero-copy YUV handling, correct color management, and audio clock sync. Use when integrating FFmpeg on Android, choosing between MediaCodec and software codecs, writing GLSL/Vulkan shaders for video compositing, handling HDR/PQ/HLG, or fixing A/V sync, color shifts, and export-vs-preview mismatch.
license: MIT
---

# Media pipeline: FFmpeg 9 + MediaCodec + Vulkan

## 0. Licensing first (blocks shipping, decide before coding)

- FFmpegKit is retired (archived 2025–2026). Do not depend on it; build FFmpeg yourself.
- LGPL build: `--disable-gpl --disable-nonfree --enable-shared --disable-static
  --enable-mediacodec --enable-jni --enable-vulkan --enable-lto
  --disable-programs --disable-doc`
- Dynamic linking + retain ability for the user to relink ⇒ LGPL obligations satisfiable; still ship
  license text, source offer, and list versions in `docs/THIRD_PARTY.md`.
- GPL-only components (x264, x265, rubberband) must be optional plugins disabled in the default build.
- Alternative integration options if a full build is impossible: FFmpegKitNext (source-build, Kotlin API)
  or prebuilt forks (e.g. ffmpeg-kit forks tracking FFmpeg 9). Document the choice in an ADR.

## 1. Decode path

- Hardware first: `MediaCodec` with a `Surface` output (zero copy into the compositor). Configure with
  the exact `MediaFormat`, handle `INFO_OUTPUT_FORMAT_CHANGED` (crop rect, color aspects, HDR static info).
- Fallback to FFmpeg software decode when the codec is unsupported or when frame-accurate random access
  is needed (e.g. `-ss` on a long GOP).
- Always honour `KEY_ROTATION`, `crop-left/right/top/bottom`, and `KEY_COLOR_*` — ignoring crop is the
  single most common "wrong picture" bug on Android.
- Build a keyframe index at import (FFmpeg `avformat_seek_file` + packet scan); store in SQLite so scrub
  seeks resolve in tens of milliseconds.
- Watch for VFR sources: normalize to the project timebase at the graph boundary, never inside a decoder.

## 2. Vulkan compositor

- Loader: `volk`; use `VK_KHR_sampler_ycbcr_conversion` + `VK_EXT_external_memory_android_hardware_buffer`
  to sample `AHardwareBuffer` images directly (no CPU copy, no `glReadPixels`).
- Shaders: precompiled SPIR-V at build time (`glslc`), loaded as byte arrays. **No runtime GLSL compile.**
- Pass fusion: brightness/contrast/saturation/LUT/transform can be one compute pass; measure
  `vkCmdPipelineBarrier` count — barriers, not math, dominate on mobile tilers.
- Precision: use `mediump` only where verified; colour math in `highp` with linear-light working space.
- Memory: `VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT` for transient attachments on tilers; budget textures
  explicitly and log peak allocation per frame in debug.
- Fallback: GLES 3.2 path behind the same interface, used only when Vulkan init fails on a device — and
  then the app must state it in the UI (avoid two silently divergent quality paths).

## 3. Color

- Input: BT.709 / BT.601 / BT.2020 matrix, limited vs full range, transfer (SDR gamma, PQ, HLG).
- Working space: linear BT.2020 (or linear BT.709 for SDR-only projects) — one decision, in one header.
- Output: project-defined (e.g. BT.709 limited SDR) with documented tone mapping for HDR inputs.
- Verify with golden frames: a ColorBars/checkerboard clip of known RGB values must round-trip within
  ±1 code value; a mismatch means a matrix or range bug, not "looks fine".

## 4. Audio

- Playback: Oboe with AAudio exclusive mode when available; callback size from `getFramesPerBurst`.
- Resampling: `soxr` (HQ) at the graph edge; never resample per clip.
- Sync: audio is the master clock; video present time derived from the audio frame position. Document the
  drop policy (drop-nearest for scrubbing, hold-last for still frames).
- Loudness: measure with EBU R128 (`libebur128`), targets configurable (-14/-16/-23 LUFS), limiter last.
- Clicks: every cut must be sample-aligned with a declared fade (even 1 ms) unless the user asks for hard cuts.

## 5. Export

- Prefer hardware encode via `MediaCodec` (`KEY_BITRATE_MODE`, `KEY_I_FRAME_INTERVAL`); mux with FFmpeg
  (`avformat_write_header`, per-stream timebase).
- Frame timeline must be generated from the same render-graph the preview uses (INV-14).
- Honest progress: report frames written / total frames; never interpolate a fake progress bar.
- Cancellation: cooperative flag checked per frame; always finalize the muxer on cancel to avoid
  unplayable files, and delete the partial file if the user cancels.

## 6. Test assets and verification

- Generated assets (checked in as small files): 1 s 24 fps test pattern, 29.97 fps clip, HDR10 clip,
  rotated clip, VFR screen recording, mono 48 kHz and 44.1 kHz audio.
- Verification: `ffprobe -v error -show_streams -show_format out.mp4` parsed in tests; assert duration,
  fps, stream count, `nb_frames` within tolerance; A/V drift < 1 frame over 5 minutes.
- Always run one long (10 min) export in CI nightly — short clips hide drift and memory growth.
