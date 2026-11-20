# Playback, live grading, and bounded export

**Current verification:** GitHub Actions run `39808454671` passed Android Kotlin/native CMake compilation, JVM tests, C++ host tests (including the Bayer preview renderer and upstream MotionCam decoder), FFmpeg script syntax, and a second FFmpeg Gradle-output validation. The gated debug APK assembled successfully; the workflow verified its bundled MotionCam/FFmpeg license assets, generated a SHA-256 sidecar, uploaded the artifact, and published prerelease `android-test-75-1`. [Download the APK](https://github.com/islamyousuf032-glitch/Cinema-cut-pro/releases/download/android-test-75-1/Cinema-Cut-Pro-debug-75-1.apk) · [SHA-256 sidecar](https://github.com/islamyousuf032-glitch/Cinema-cut-pro/releases/download/android-test-75-1/Cinema-Cut-Pro-debug-75-1.apk.sha256). It is debug-signed and for device testing only. No physical-device test has been performed; the user will do device testing.

## Playback routes

- `PreviewEngineType.AUTO` continues to use Media3 ExoPlayer for ordinary Android media. ExoPlayer/Media3 and LibVLC remain available; the timeline, project model, orientation, and editor/timeline UI are unchanged.
- `FFMPEG_NATIVE` is now a distinct native FFmpeg playback engine; it is no longer described as an alias for LibVLC. Its JNI path demuxes a source file descriptor with FFmpeg, attempts Android MediaCodec for H.264/HEVC, and can use compiled FFmpeg software decoders where available. ExoPlayer remains responsible for source audio. The exact decoder, surface, codec, and source-provider behavior still needs device validation.
- SAF/content-URI access and MIME hints are supported in the playback plumbing, but provider-specific permissions and seekability vary. A MIME hint cannot add a missing decoder. Do not claim universal codec/container support.
- The reported 4K 10-bit HLG issue remains undiagnosed without the original sample or exact stream metadata and device logs. Do not infer its codec, profile, transfer function, or failure cause from the label alone.

### MotionCam RAW (`.mcraw`)

`.mcraw` files use the vendored native MotionCam decoder and a dedicated C++ Bayer preview renderer; they are routed automatically and are not handed to ExoPlayer/LibVLC for RAW parsing. The preview uses camera metadata when present, follows source timestamps, and skips late frames rather than slowing the timeline. It reuses native frame/output buffers and two Bitmap buffers; paused/scrub previews are capped at 960 pixels and playback at 480 pixels on the long edge. These are performance optimizations, **not** a smooth-playback guarantee.

The RAW viewport supports live exposure, temperature/tint, primary Lift/Gamma/Gain/Offset, three-zone color balance, and viewport brightness/contrast/saturation. Curves, HSL, LUTs, and other advanced effects are not reflected there; the grading screen identifies this limitation. The renderer is a lightweight editing preview, not a full MotionCam ISP or a color-accurate RAW development pipeline. A bounded `.mcraw` video-export bridge now develops Bayer frames through that renderer into a temporary 8-bit SDR H.264 file for the regular Media3 export sequence; it can require two encodes and is not a RAW-preserving or 10-bit route. Embedded RAW audio and stabilization are not implemented. The MotionCam Apache-2.0 license and bundled dependency notices are included in the app assets.

### Live grading

- Media3 preview and the supported Media3 export effect share the adjustment/curve implementation. Supported master/RGB/luma/hue/saturation curves are merged into the effective parameters and rendered through the GPU curve texture.
- When the grading screen is opened from a non-Media3 engine for ordinary video, it temporarily routes the preview through Media3's GPU effect graph, then restores the requested engine. ExoPlayer and LibVLC are retained.
- MCRAW uses its native supported subset. Unsupported MCRAW curve/HSL/LUT features remain visibly identified rather than being represented as applied.
- Export validation rejects unsupported multi-node/color-layer, animated, or other unsupported grades instead of silently dropping them.

## Bounded Media3 export

`ExportScreen` validates through `ExportSettingsViewModel` and `ExportBackendFactory` against the actual Media3 Transformer backend. The foreground queue selects and validates that same backend again. The legacy `ExportValidator` now only checks runtime source accessibility and storage; it no longer makes independent codec/container decisions.

The implemented export route is intentionally limited to H.264/MP4, 8-bit SDR, AAC for supported ordinary video, compatible constant-frame-rate sources, one visible video track, and sequential cut-only edits. Validation checks encoder dimensions/orientation, frame rate, bitrate and keyframe settings, AAC capabilities/source audio, source trims, and supported adjustments. `.mcraw` clips are first developed by the native Bayer renderer into a temporary, video-only 8-bit SDR MP4, then use the same Media3 H.264/MP4 path; an Android H.264 encoder with flexible-YUV input must support that intermediate size/rate/bitrate. MCRAW embedded PCM audio is not exported. Unsupported HDR output, 10-bit output, alpha, HEVC/AV1, ProRes (all profiles), APV, MOV/MKV, multiple tracks, audio mixes, overlays, transforms, gaps/overlaps/transitions, and unsupported grades remain unavailable or rejected. The MCRAW bridge is lossy 8-bit SDR, not a full MotionCam ISP or RAW-preserving master. Encoder capability checks are device-dependent and do not guarantee a complete export on every phone.

Size estimates use overflow-safe arithmetic. At runtime, the queue checks source readability/free space; the backend checks the staging output path before rendering. The output uses app-scoped Movies storage and is published only after a non-empty MP4 is produced.

## Remaining validation

- Android Kotlin/JNI/CMake compilation, JVM and native host tests, FFmpeg script/Gradle-output checks, APK assembly, and bundled-license verification all passed in run `39808454671`; only on-device validation remains.
- On-device playback, export, audio synchronization, device-specific encoders, MotionCam frame rate/memory/color, and content-provider behavior remain for the user to test. A short unmodified `.mcraw` sample and any target-device logs will help diagnose failures.
- For the reported 4K 10-bit HLG source, collect the original file (or a short unmodified sample), container/video/audio codec and profile, bit depth, frame rate, HDR transfer/primaries, rotation, and playback logs before drawing conclusions.
