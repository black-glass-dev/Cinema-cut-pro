# MotionCam Pro RAW (`.mcraw`)

## Current implementation

`.mcraw` documents are recognized by their original filename, MIME type, or the MotionCam container signature. They are imported as video assets, analyzed with the vendored MotionCam C++ decoder, and routed to a dedicated native preview engine. ExoPlayer and LibVLC remain available and unchanged for ordinary media; neither is used to parse a `.mcraw` recording.

The preview path decodes one 16-bit Bayer frame at a time and renders a bilinear demosaic (up to 960 px on the longest edge while paused/scrubbing; 480 px during playback). It uses the recording's CFA pattern, black/white levels, white balance, and forward color matrix when those fields are present. Frame timestamps are normalized relative to the first video frame. During playback it follows source time and skips late frames rather than making the clip run slower when decoding cannot keep up. Grade-only updates now change the parameters consumed by the existing playback loop rather than cancelling and restarting playback; paused grade rerenders are coalesced. Android Debug builds compile this decoder and renderer with `-O2` while preserving symbols. The preview applies exposure, temperature/tint (including an approximate reciprocal-Kelvin-to-relative-temperature mapping), Lift/Gamma/Gain/Offset wheels, and three-zone color balance; brightness, contrast, and saturation use the viewport's basic color matrix. Curves, HSL, LUTs, and other advanced grade tools are not yet reflected in the MCRAW viewport. This lightweight conversion is an editing preview, **not** a full MotionCam ISP or a color-accurate RAW development pipeline.

The decoder sources are pinned to upstream `mirsadm/motioncam-decoder` commit `b931ec0e2466c8217c49f13df925531b78f76e33`. License summaries and bundled dependency notices are in `app/src/main/cpp/third_party/motioncam_decoder/THIRD_PARTY_NOTICES.md` and are packaged in the APK under `third_party/motioncam/`.

## Bounded H.264/MP4 export

A limited real export route now connects `.mcraw` clips to the Media3 Transformer backend. Before Transformer receives a clip, the native MotionCam decoder loads each Bayer frame and the app's lightweight renderer develops it to 8-bit sRGB pixels. An Android H.264 encoder that accepts `COLOR_FormatYUV420Flexible` encodes a temporary video-only MP4; Media3 then applies the regular validated trims, supported clip effects, sequential composition, resize, and final H.264/MP4 encode. Temporary files are kept in app cache and removed after export or cancellation.

This is a video export path, not a RAW master or full-resolution sensor-data delivery. The selected output's long edge limits the intermediate render (up to 3840 pixels), and Bayer frames above 24 megapixels are rejected to bound working memory. It is an 8-bit SDR conversion based on the preview demosaic/color-matrix path and can require two H.264 encodes. Sensor bit depth does **not** mean the output retains that precision. Only H.264/MP4 is selectable; ProRes 422 Proxy/LT/422/HQ/4444, APV, 10-bit output, MOV/MKV, HDR, and 10-bit mastering are not implemented or advertised. A compatible flexible-YUV Android encoder must support the intermediate size, frame rate, and bitrate, in addition to the final Media3 output encoder.

The native `.mcraw` embedded PCM audio decoder is still not connected to this route. Export settings default to video-only for MotionCam sources; requesting source audio is rejected with an explanation. Variable-frame-rate or unverified timestamp cadence remains blocked. Native preview and export do not implement the full MotionCam ISP, stabilization, advanced MCRAW grades, or proxies.

## Explicit limitations

- The app's native Android build is currently filtered to `arm64-v8a`.
- The decoder validates MCRAW container version 3; malformed or unsupported recordings remain in the media bin with an analysis error and are not added to the timeline.
- Export is conditional on the device's actual H.264 encoder accepting flexible YUV input at the selected dimensions, rate, and bitrate; validation checks this, but target-device runtime validation is still required.
- Export is 8-bit SDR H.264/MP4 only. There is no ProRes/APV/10-bit path, no embedded-audio export for `.mcraw`, no full MotionCam ISP render, and no proxy generation.
- Gyroscope and accelerometer presence is reported, but stabilization is not applied.
- Real-time frame rate, memory use, camera color, rotation behavior, encoder behavior, and document-provider permissions still need validation with a short MotionCam Pro recording on a physical device.

## Tests

`python3 tools/run_native_export_tests.py` builds and runs the host C++ export tests, the Bayer preview renderer test, and the upstream decoder, gyro, and accelerometer regression tests. The host suite does not compile JNI or prove Android device playback; the GitHub Actions Android build and on-device validation are required for those checks.
