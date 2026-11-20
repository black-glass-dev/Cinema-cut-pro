#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <motioncam/Decoder.hpp>
#include <nlohmann/json.hpp>

#include "mcraw_preview_renderer.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <limits>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

#define LOG_TAG "MotionCamDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using Json = nlohmann::json;

struct DecoderHandle {
    explicit DecoderHandle(int ownedSourceFd) : sourceFd(ownedSourceFd) {}
    ~DecoderHandle() {
        audioDecoder.reset();
        decoder.reset();
        if (sourceFd >= 0) close(sourceFd);
    }

    std::mutex mutex;
    int sourceFd = -1;
    std::unique_ptr<motioncam::Decoder> decoder;
    std::unique_ptr<motioncam::Decoder> audioDecoder;
    std::vector<uint8_t> previewRawBytes;
    Json previewFrameMetadata;
    cinema::mcraw::RgbaPreview previewBitmap;
    std::vector<int16_t> pendingAudioSamples;
    int64_t pendingAudioTimestampNs = -1;
    size_t pendingAudioOffset = 0;
    std::string lastError;
};

DecoderHandle* asHandle(const jlong value) {
    return reinterpret_cast<DecoderHandle*>(static_cast<intptr_t>(value));
}

void throwIoException(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

FILE* fdToFile(const int fd) {
    const int duplicate = dup(fd);
    if (duplicate < 0) return nullptr;
    FILE* file = fdopen(duplicate, "rb");
    if (file == nullptr) close(duplicate);
    return file;
}

void ensureAudioDecoder(DecoderHandle& handle) {
    if (handle.audioDecoder != nullptr) return;
    FILE* file = fdToFile(handle.sourceFd);
    if (file == nullptr) throw motioncam::IOException("Unable to duplicate MotionCam audio source");
    handle.audioDecoder = std::make_unique<motioncam::Decoder>(file);
    (void)handle.audioDecoder->loadAudio();
}

std::vector<double> readNumbers(const Json& object, const char* key) {
    std::vector<double> result;
    if (!object.is_object()) return result;
    const auto it = object.find(key);
    if (it == object.end() || !it->is_array()) return result;
    result.reserve(it->size());
    for (const auto& element : *it) {
        if (element.is_number()) result.push_back(element.get<double>());
        else result.push_back(0.0);
    }
    return result;
}

double readNumber(const Json& object, const char* key, const double fallback) {
    if (!object.is_object()) return fallback;
    const auto it = object.find(key);
    return it != object.end() && it->is_number() ? it->get<double>() : fallback;
}

cinema::mcraw::BayerPreviewMetadata makePreviewMetadata(
    const Json& container,
    const Json& frame) {
    cinema::mcraw::BayerPreviewMetadata result;
    std::string pattern = "rggb";
    if (container.contains("sensorArrangment") && container["sensorArrangment"].is_string()) {
        pattern = container["sensorArrangment"].get<std::string>();
    } else if (container.contains("sensorArrangement") && container["sensorArrangement"].is_string()) {
        pattern = container["sensorArrangement"].get<std::string>();
    }
    std::transform(pattern.begin(), pattern.end(), pattern.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    result.cfaPattern = pattern;

    const auto black = readNumbers(container, "blackLevel");
    for (size_t index = 0; index < result.blackLevel.size() && index < black.size(); ++index) {
        result.blackLevel[index] = black[index];
    }
    result.whiteLevel = readNumber(container, "whiteLevel", 65535.0);

    const auto neutral = readNumbers(frame, "asShotNeutral");
    if (neutral.size() >= 3 && neutral[0] > 0.0 && neutral[1] > 0.0 && neutral[2] > 0.0) {
        // DNG AsShotNeutral is the neutral camera response; inverse ratios provide a
        // useful white-balance approximation before the DNG forward color matrix.
        const double greenNeutral = neutral[1];
        result.whiteBalanceGain = {{
            std::clamp(greenNeutral / neutral[0], 0.125, 8.0),
            1.0,
            std::clamp(greenNeutral / neutral[2], 0.125, 8.0),
        }};
    }

    auto forward = readNumbers(container, "forwardMatrix1");
    if (forward.size() < 9) forward = readNumbers(container, "forwardMatrix2");
    if (forward.size() >= 9) {
        bool valid = true;
        for (size_t index = 0; index < 9; ++index) {
            valid = valid && std::isfinite(forward[index]);
            result.forwardMatrix1[index] = forward[index];
        }
        result.hasForwardMatrix1 = valid;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeOpen(JNIEnv* env, jobject, const jint fd) {
    if (fd < 0) {
        throwIoException(env, "Invalid MotionCam file descriptor");
        return 0;
    }

    const int sourceFd = dup(fd);
    if (sourceFd < 0) {
        throwIoException(env, std::string("Unable to duplicate MotionCam file: ") + std::strerror(errno));
        return 0;
    }

    auto handle = std::make_unique<DecoderHandle>(sourceFd);
    FILE* file = fdToFile(sourceFd);
    if (file == nullptr) {
        throwIoException(env, std::string("Unable to open MotionCam file: ") + std::strerror(errno));
        return 0;
    }

    try {
        handle->decoder = std::make_unique<motioncam::Decoder>(file);
        if (handle->decoder->getFrames().empty()) {
            throw motioncam::IOException("MotionCam recording contains no video frames");
        }
    } catch (const std::exception& error) {
        LOGE("Could not open MCRAW: %s", error.what());
        throwIoException(env, error.what());
        return 0;
    } catch (...) {
        throwIoException(env, "Unknown error opening MotionCam recording");
        return 0;
    }

    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeClose(JNIEnv*, jobject, const jlong value) {
    delete asHandle(value);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetFrameCount(JNIEnv*, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return 0;
    std::lock_guard<std::mutex> guard(handle->mutex);
    const size_t count = handle->decoder->getFrames().size();
    return count > static_cast<size_t>(std::numeric_limits<jint>::max())
        ? std::numeric_limits<jint>::max() : static_cast<jint>(count);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetFrameTimestampNs(
    JNIEnv* env, jobject, const jlong value, const jint index) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return 0;
    std::lock_guard<std::mutex> guard(handle->mutex);
    const auto& frames = handle->decoder->getFrames();
    if (index < 0 || static_cast<size_t>(index) >= frames.size()) {
        handle->lastError = "MCRAW frame index is out of bounds";
        throwIoException(env, handle->lastError);
        return 0;
    }
    return static_cast<jlong>(frames[static_cast<size_t>(index)]);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetContainerMetadataJson(
    JNIEnv* env, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return toJavaString(env, "{}");
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        return toJavaString(env, handle->decoder->getContainerMetadata().dump());
    } catch (const std::exception& error) {
        handle->lastError = error.what();
        throwIoException(env, handle->lastError);
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetFrameMetadataJson(
    JNIEnv* env, jobject, const jlong value, const jint index) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return toJavaString(env, "{}");
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        const auto& frames = handle->decoder->getFrames();
        if (index < 0 || static_cast<size_t>(index) >= frames.size()) {
            throw motioncam::IOException("MCRAW frame index is out of bounds");
        }
        Json metadata;
        handle->decoder->loadFrameMetadata(frames[static_cast<size_t>(index)], metadata);
        return toJavaString(env, metadata.dump());
    } catch (const std::exception& error) {
        handle->lastError = error.what();
        throwIoException(env, handle->lastError);
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeDecodeFrameToBitmap(
    JNIEnv* env, jobject, const jlong value, const jint index, const jint requestedMaximumEdge,
    const jfloatArray requestedGradeParams,
    jobject reusableBitmap) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) {
        throwIoException(env, "MotionCam decoder is closed");
        return nullptr;
    }

    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        const auto& frames = handle->decoder->getFrames();
        if (index < 0 || static_cast<size_t>(index) >= frames.size()) {
            throw motioncam::IOException("MCRAW frame index is out of bounds");
        }

        auto& rawBytes = handle->previewRawBytes;
        auto& frameMetadata = handle->previewFrameMetadata;
        handle->decoder->loadFrame(frames[static_cast<size_t>(index)], rawBytes, frameMetadata);
        const int width = frameMetadata.at("width").get<int>();
        const int height = frameMetadata.at("height").get<int>();
        if (width < 2 || height < 2 || static_cast<uint64_t>(width) * static_cast<uint64_t>(height) > 64'000'000ULL) {
            throw motioncam::IOException("MCRAW frame dimensions are outside safe preview limits");
        }
        const uint64_t expectedBytes = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * sizeof(uint16_t);
        if (rawBytes.size() < expectedBytes) {
            throw motioncam::IOException("MCRAW decoder returned a truncated RAW frame");
        }

        // Preview callers still request at most 2048 px. The export bridge may request up to 4K
        // long edge for an 8-bit SDR intermediate, subject to the encoder and memory limits.
        const int maximumEdge = std::clamp(requestedMaximumEdge, 64, 3840);
        cinema::mcraw::PreviewGrade grade;
        if (requestedGradeParams != nullptr) {
            if (env->GetArrayLength(requestedGradeParams) != 15) {
                throw motioncam::IOException("MotionCam grade payload must contain exactly 15 values");
            }
            jfloat values[15]{};
            env->GetFloatArrayRegion(requestedGradeParams, 0, 15, values);
            if (env->ExceptionCheck()) return nullptr;
            grade.exposureStops = values[0];
            grade.temperature = values[1];
            grade.tint = values[2];
            for (size_t channel = 0; channel < 3; ++channel) {
                grade.lift[channel] = values[3 + channel];
                grade.gamma[channel] = values[6 + channel];
                grade.gain[channel] = values[9 + channel];
                grade.offset[channel] = values[12 + channel];
            }
        }
        int rotationDegrees = 0;
        const auto rotation = frameMetadata.find("rotationDegrees");
        if (rotation != frameMetadata.end() && rotation->is_number()) {
            rotationDegrees = static_cast<int>(rotation->get<double>());
        }

        std::string renderError;
        if (!cinema::mcraw::renderBayerPreview(
                reinterpret_cast<const uint16_t*>(rawBytes.data()),
                width,
                height,
                makePreviewMetadata(handle->decoder->getContainerMetadata(), frameMetadata),
                maximumEdge,
                handle->previewBitmap,
                &renderError,
                grade,
                rotationDegrees)) {
            throw motioncam::IOException(renderError.empty() ? "Could not render MCRAW preview" : renderError);
        }
        const auto& preview = handle->previewBitmap;

        jobject bitmap = reusableBitmap;
        AndroidBitmapInfo bitmapInfo{};
        bool needsNewBitmap = bitmap == nullptr ||
            AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            bitmapInfo.width != static_cast<uint32_t>(preview.width) ||
            bitmapInfo.height != static_cast<uint32_t>(preview.height);
        if (env->ExceptionCheck()) return nullptr;

        if (needsNewBitmap) {
            jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
            jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
            if (bitmapClass == nullptr || configClass == nullptr) {
                throw motioncam::IOException("Android Bitmap classes are unavailable");
            }
            jfieldID argbField = env->GetStaticFieldID(
                configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
            if (argbField == nullptr) throw motioncam::IOException("ARGB_8888 bitmap configuration is unavailable");
            jobject config = env->GetStaticObjectField(configClass, argbField);
            jmethodID createBitmap = env->GetStaticMethodID(
                bitmapClass,
                "createBitmap",
                "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
            if (createBitmap == nullptr) throw motioncam::IOException("Bitmap.createBitmap is unavailable");
            bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap, preview.width, preview.height, config);
            if (env->ExceptionCheck() || bitmap == nullptr) return nullptr;
            if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
                throw motioncam::IOException("Android could not inspect the new preview bitmap");
            }
        }
        if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            bitmapInfo.width != static_cast<uint32_t>(preview.width) ||
            bitmapInfo.height != static_cast<uint32_t>(preview.height)) {
            throw motioncam::IOException("Android returned an incompatible preview bitmap");
        }

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
            throw motioncam::IOException("Could not lock preview bitmap pixels");
        }
        for (int y = 0; y < preview.height; ++y) {
            auto* row = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * bitmapInfo.stride;
            const uint32_t* sourceRow = preview.pixels.data() + static_cast<size_t>(y) * preview.width;
            for (int x = 0; x < preview.width; ++x) {
                const uint32_t argb = sourceRow[x];
                row[static_cast<size_t>(x) * 4u] = static_cast<uint8_t>((argb >> 16u) & 0xffu);
                row[static_cast<size_t>(x) * 4u + 1u] = static_cast<uint8_t>((argb >> 8u) & 0xffu);
                row[static_cast<size_t>(x) * 4u + 2u] = static_cast<uint8_t>(argb & 0xffu);
                row[static_cast<size_t>(x) * 4u + 3u] = static_cast<uint8_t>((argb >> 24u) & 0xffu);
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        handle->lastError.clear();
        return bitmap;
    } catch (const std::exception& error) {
        handle->lastError = error.what();
        LOGE("MCRAW preview failed: %s", handle->lastError.c_str());
        throwIoException(env, handle->lastError);
        return nullptr;
    } catch (...) {
        handle->lastError = "Unknown error decoding MotionCam frame";
        throwIoException(env, handle->lastError);
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetAudioSampleRateHz(
    JNIEnv*, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return 0;
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        return handle->decoder->audioSampleRateHz();
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetAudioChannelCount(
    JNIEnv*, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return 0;
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        return handle->decoder->numAudioChannels();
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeHasGyroData(JNIEnv*, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(handle->mutex);
    return handle->decoder->hasGyroData() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeHasAccelerometerData(JNIEnv*, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(handle->mutex);
    return handle->decoder->hasAccelerometerData() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeResetAudio(JNIEnv* env, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        handle->audioDecoder.reset();
        handle->pendingAudioSamples.clear();
        handle->pendingAudioTimestampNs = -1;
        handle->pendingAudioOffset = 0;
        ensureAudioDecoder(*handle);
        handle->lastError.clear();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        handle->lastError = error.what();
        throwIoException(env, handle->lastError);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeReadNextAudioChunk(
    JNIEnv* env, jobject, const jlong value, const jshortArray targetSamples, const jlongArray targetTimestamp) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr || handle->decoder == nullptr || targetSamples == nullptr || targetTimestamp == nullptr) return -1;
    std::lock_guard<std::mutex> guard(handle->mutex);
    try {
        ensureAudioDecoder(*handle);
        if (handle->pendingAudioOffset >= handle->pendingAudioSamples.size()) {
            motioncam::AudioChunk chunk;
            if (!handle->audioDecoder->loadAudio().next(chunk)) return 0;
            handle->pendingAudioTimestampNs = chunk.first;
            handle->pendingAudioSamples = std::move(chunk.second);
            handle->pendingAudioOffset = 0;
            if (handle->pendingAudioSamples.empty()) return 0;
        }

        const jsize capacity = env->GetArrayLength(targetSamples);
        if (capacity <= 0) return -1;
        const size_t remaining = handle->pendingAudioSamples.size() - handle->pendingAudioOffset;
        const size_t count = std::min(remaining, static_cast<size_t>(capacity));
        env->SetShortArrayRegion(
            targetSamples,
            0,
            static_cast<jsize>(count),
            reinterpret_cast<const jshort*>(handle->pendingAudioSamples.data() + handle->pendingAudioOffset));
        if (env->ExceptionCheck()) return -1;

        if (env->GetArrayLength(targetTimestamp) > 0) {
            int64_t timestampNs = handle->pendingAudioTimestampNs;
            const int sampleRate = handle->audioDecoder->audioSampleRateHz();
            const int channelCount = handle->audioDecoder->numAudioChannels();
            if (timestampNs >= 0 && sampleRate > 0 && channelCount > 0) {
                const uint64_t sampleFrames = handle->pendingAudioOffset / static_cast<size_t>(channelCount);
                const uint64_t offsetNs = sampleFrames * 1'000'000'000ULL / static_cast<uint64_t>(sampleRate);
                if (offsetNs <= static_cast<uint64_t>(std::numeric_limits<int64_t>::max() - timestampNs)) {
                    timestampNs += static_cast<int64_t>(offsetNs);
                }
            }
            const jlong timestamp = static_cast<jlong>(timestampNs);
            env->SetLongArrayRegion(targetTimestamp, 0, 1, &timestamp);
        }
        handle->pendingAudioOffset += count;
        return static_cast<jint>(count);
    } catch (const std::exception& error) {
        handle->lastError = error.what();
        throwIoException(env, handle->lastError);
        return -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_timeline_media_NativeMcrawDecoder_nativeGetLastError(JNIEnv* env, jobject, const jlong value) {
    DecoderHandle* handle = asHandle(value);
    if (handle == nullptr) return toJavaString(env, "MotionCam decoder is closed");
    std::lock_guard<std::mutex> guard(handle->mutex);
    return toJavaString(env, handle->lastError);
}
