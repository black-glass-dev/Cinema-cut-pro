# FFmpeg Android integration status

## Source and license review

- FFmpeg `n8.1.3` is pinned to upstream commit `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7`. Its default build is LGPL v2.1 or later; GPL/version-3 and nonfree options remain disabled. The app packages the LGPL notice and build metadata.
- `tools/build_ffmpeg_android.sh` pins NDK `27.2.12479018`, builds arm64-v8a/API 24 shared libraries, enables JNI, and checks the requested decoder/demuxer configuration. The Gradle task `:app:buildFfmpegAndroid` supplies headers/libraries to CMake and packages the FFmpeg libraries and notices.
- The configured software decoders include H.264, HEVC, AV1, VP8, VP9, ProRes, ProRes RAW, and APV. Android MediaCodec wrappers are included for H.264, HEVC, AV1, VP8, VP9, MPEG-2, and MPEG-4. The MOV/MP4 and Matroska/MKV demuxers are enabled, as are the APV parser and raw APV demuxer. This is build-time inclusion, not a promise that a device's hardware decoder supports every codec/profile.
- Build products stay under `app/build/ffmpeg-android`; the pinned source is retained between builds. The script protects its private build and install-prefix directories and avoids recursively deleting the generated prefix.
- The previously inspected EkinStrop prebuilt remains unselected: its advertised minimum API 28 conflicts with this app's minSdk 24, and its inspected repository did not provide a reproducible binary-build recipe.

## Playback and grading status

- `FFMPEG_NATIVE` remains a distinct selectable engine; ExoPlayer/Media3 and LibVLC are retained. FFmpeg demuxes the video source and attempts a matching Android MediaCodec decoder, then retries with an included FFmpeg software decoder when the hardware path fails. ExoPlayer remains responsible for source audio.
- Media3/FFmpeg can demux MP4/MOV and MKV containers when the contained stream is supported; ProRes, ProRes RAW, and APV decoder components are built into the FFmpeg path. Actual playback still depends on the file's exact codec, profile, bit depth, pixel format, frame rate, and decoder behavior. The FFmpeg preview converts frames to 8-bit RGBA, so this change does **not** promise a full-precision 10-bit/HDR preview or real-time ProRes/APV playback on every device.
- Native FFmpeg handle access and close are serialized through one gate. Engine construction/switch failures are caught and the controller attempts Media3 recovery. These lifecycle protections and decoder fallback are source/build-verified, not device-runtime proof.
- Standard clips can use the FFmpeg route explicitly. For live color grading, the editor can temporarily route supported non-MCRAW sources through the Media3 GPU effect graph, then restore the requested engine. MCRAW remains on its C++ Bayer preview renderer; curves, HSL, LUTs, and advanced effects remain unsupported on that route.
- The shared Media3 preview/export effect path renders supported curves. Export validation rejects unsupported layers, animation, and effects instead of silently dropping them.

## Verification and device limits

- FFmpeg pins, flags, task wiring, and packaging paths above are source configuration only in this workspace; the current session has no JDK, Android SDK, or NDK, so this FFmpeg Android build was not run.
- A GitHub API metadata lookup showed run 40202985334 with `status=completed`, `conclusion=success`, and head SHA `e068a5f21570f14a0256d92b2960e8ef8b9d7e24`; the job logs were not inspected and that SHA does not identify the source revision tested by this local checkpoint. Exclude it from current build evidence. The metadata's 2026-10-25 creation date is retained as a remote record, not used as current-checkout chronology.
- No physical-device playback test or valid output-file inspection is claimed here. Do not infer support for all codecs, HDR/10-bit media, MotionCam files, or any single sample without examining exact metadata and device logs. Record codec/profile, pixel format/bit depth, frame rate, color transfer/primaries, resolution, and audio streams when diagnosing failures.
