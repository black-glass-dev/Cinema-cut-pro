#include "timeline_core_v2/timeline_core.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string_view>
#include <vector>

using namespace timeline_core_v2;

namespace {
constexpr std::size_t kTrackCount = 20;
constexpr std::int64_t kClipDurationFrames = 100;
constexpr std::int64_t kClipSpacingFrames = 120;
constexpr std::size_t kIterations = 30;
std::uint64_t benchmark_sink{};

Flicks frames(std::int64_t value) {
    const auto converted = frameToFlicks(value, FrameRate{24, 1});
    if (!converted) std::abort();
    return *converted;
}

Project makeProject(std::size_t total_clip_count) {
    Project project{1, {24, 1}, {}, {}};
    project.tracks.reserve(kTrackCount);
    const auto clips_per_track = total_clip_count / kTrackCount;
    const auto extra_clips = total_clip_count % kTrackCount;
    std::uint64_t next_clip_id = 1;
    for (std::size_t track_index = 0; track_index < kTrackCount; ++track_index) {
        const auto clip_count = clips_per_track + (track_index < extra_clips ? 1U : 0U);
        Track track{TrackId{static_cast<std::uint64_t>(track_index + 1U)}, false, false, {}};
        track.clips.reserve(clip_count);
        for (std::size_t clip_index = 0; clip_index < clip_count; ++clip_index) {
            const auto start_frame = static_cast<std::int64_t>(clip_index) * kClipSpacingFrames;
            track.clips.push_back(Clip{
                ClipId{next_clip_id++}, track.id,
                TimeRange{Flicks{0}, frames(kClipDurationFrames)}, frames(start_frame),
                TimeRange{Flicks{0}, frames(1'000)}, false, 0, false, true});
        }
        project.tracks.push_back(std::move(track));
    }
    return project;
}

template <class Operation>
bool measure(std::string_view label, std::size_t clip_count, Operation&& operation) {
    std::vector<double> samples;
    samples.reserve(kIterations);
    for (std::size_t iteration = 0; iteration < kIterations; ++iteration) {
        const auto start = std::chrono::steady_clock::now();
        if (!operation()) {
            std::cerr << "benchmark operation failed: " << label << '\n';
            return false;
        }
        const auto finish = std::chrono::steady_clock::now();
        samples.push_back(std::chrono::duration<double, std::micro>(finish - start).count());
    }
    std::sort(samples.begin(), samples.end());
    const auto p95_index = (samples.size() - 1U) * 95U / 100U;
    std::cout << label << " clips=" << clip_count << " tracks=" << kTrackCount
              << " iterations=" << kIterations << " p50_us=" << samples[samples.size() / 2U]
              << " p95_us=" << samples[p95_index] << '\n';
    return true;
}

bool benchmarkScenario(std::size_t clip_count) {
    const Project project = makeProject(clip_count);
    if (!validateProject(project)) {
        std::cerr << "generated benchmark project failed validation\n";
        return false;
    }
    const auto clips_per_track = (clip_count + kTrackCount - 1U) / kTrackCount;
    const auto viewport_start = frames(static_cast<std::int64_t>(clips_per_track / 2U) *
                                       kClipSpacingFrames);
    const TimeRange viewport{viewport_start, frames(1'000)};
    const Flicks overscan = frames(300);
    SnapConfig snap_config;
    snap_config.threshold = frames(5);
    const auto playhead = frames(500);

    if (!measure("validate", clip_count, [&]() {
            const auto result = validateProject(project);
            benchmark_sink += result.has_value() ? 1U : 0U;
            return result.has_value();
        })) {
        return false;
    }
    if (!measure("visible_query", clip_count, [&]() {
            const auto result = visibleClipIds(project, viewport, overscan);
            if (!result) return false;
            benchmark_sink += static_cast<std::uint64_t>(result->size());
            return true;
        })) {
        return false;
    }
    if (!measure("snap", clip_count, [&]() {
            const auto result = snap(project, viewport_start, playhead, snap_config);
            if (!result) return false;
            benchmark_sink += result->position.value >= 0 ? 1U : 0U;
            return true;
        })) {
        return false;
    }

    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = project.tracks.front().clips.front().id;
    move.target_track_id = project.tracks.front().id;
    move.target = frames(static_cast<std::int64_t>(clips_per_track + 1U) * kClipSpacingFrames);
    if (!measure("apply_move", clip_count, [&]() {
            const auto result = applyEdit(project, move);
            if (!result) return false;
            benchmark_sink += result->project_hash;
            return true;
        })) {
        return false;
    }
    return true;
}
}  // namespace

int main() {
    if (!benchmarkScenario(500) || !benchmarkScenario(10'000)) return 1;
    std::cout << "host benchmark sink=" << benchmark_sink << '\n';
    return 0;
}
