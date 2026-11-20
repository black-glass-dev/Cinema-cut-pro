#include <jni.h>

#include "timeline_core_v2/timeline_core.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <new>
#include <optional>
#include <span>
#include <unordered_map>
#include <type_traits>
#include <utility>
#include <vector>

namespace {
using namespace timeline_core_v2;

constexpr std::size_t kTrackWidth = 2;
constexpr std::size_t kClipWidth = 9;
constexpr std::size_t kMarkerWidth = 2;
constexpr std::size_t kCommandWidth = 10;
constexpr std::size_t kOutputHeaderWidth = 9;
constexpr std::size_t kOutputClipWidth = 7;
constexpr std::size_t kMaxArrayValues = 4'000'000;
constexpr std::size_t kMaxCommands = 100'000;
constexpr std::size_t kMaxIdsPerCommand = 1'000'000;
constexpr std::uint64_t kTrackLocked = 1U << 0U;
constexpr std::uint64_t kTrackAllowsOverlap = 1U << 1U;
constexpr std::uint64_t kClipLocked = 1U << 0U;
constexpr std::uint64_t kClipGroupLocked = 1U << 1U;
constexpr std::uint64_t kClipEnabled = 1U << 2U;

struct NativeHistorySession {
    std::uint64_t project_id{};
    Project current;
    TimelineHistory history{50};

    NativeHistorySession(std::uint64_t id, Project project) noexcept
        : project_id(id), current(std::move(project)), history(50) {}
};

static_assert(std::is_nothrow_move_constructible_v<NativeHistorySession>);

std::mutex g_history_mutex;
std::optional<NativeHistorySession> g_history_session;

std::uint64_t fromJavaId(jlong value) noexcept {
    return std::bit_cast<std::uint64_t>(value);
}

jlong toJavaId(std::uint64_t value) noexcept {
    return std::bit_cast<jlong>(value);
}

bool copyLongArray(JNIEnv* env, jlongArray source, std::vector<jlong>& destination) {
    if (source == nullptr) return false;
    const jsize length = env->GetArrayLength(source);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxArrayValues) return false;
    destination.resize(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetLongArrayRegion(source, 0, length, destination.data());
        if (env->ExceptionCheck() == JNI_TRUE) return false;
    }
    return true;
}

jlongArray makeLongArray(JNIEnv* env, std::span<const jlong> values) {
    if (values.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    const auto length = static_cast<jsize>(values.size());
    jlongArray result = env->NewLongArray(length);
    if (result == nullptr) return nullptr;
    if (length > 0) {
        env->SetLongArrayRegion(result, 0, length, values.data());
        if (env->ExceptionCheck() == JNI_TRUE) return nullptr;
    }
    return result;
}

jlongArray makeErrorArray(JNIEnv* env, const Error& error, std::size_t width) {
    try {
        std::vector<jlong> result(width, 0);
        if (!result.empty()) result[0] = static_cast<jlong>(error.code);
        if (result.size() > 1) result[1] = toJavaId(error.clip_id);
        if (result.size() > 2) result[2] = toJavaId(error.track_id);
        return makeLongArray(env, result);
    } catch (...) {
        return nullptr;
    }
}

Result<Flicks> convertFrame(jlong frame, FrameRate rate) noexcept {
    return frameToFlicks(static_cast<std::int64_t>(frame), rate);
}

Result<Clip> parseClipRecord(const jlong* fields, FrameRate rate) noexcept {
    const std::uint64_t flags = fromJavaId(fields[7]);
    if ((flags & ~(kClipLocked | kClipGroupLocked | kClipEnabled)) != 0U) {
        return std::unexpected(Error{ErrorCode::InvalidArgument, fromJavaId(fields[0]),
                                     fromJavaId(fields[1])});
    }
    auto source_start = convertFrame(fields[2], rate);
    auto source_end = convertFrame(fields[3], rate);
    auto timeline_start = convertFrame(fields[4], rate);
    auto available_start = convertFrame(fields[5], rate);
    auto available_end = convertFrame(fields[6], rate);
    if (!source_start || !source_end || !timeline_start || !available_start || !available_end) {
        return std::unexpected(Error{ErrorCode::Overflow, fromJavaId(fields[0]),
                                     fromJavaId(fields[1])});
    }
    auto source_duration = checkedSubtract(*source_end, *source_start);
    auto available_duration = checkedSubtract(*available_end, *available_start);
    if (!source_duration || !available_duration) {
        return std::unexpected(Error{ErrorCode::Overflow, fromJavaId(fields[0]),
                                     fromJavaId(fields[1])});
    }
    return Clip{ClipId{fromJavaId(fields[0])},
                TrackId{fromJavaId(fields[1])},
                TimeRange{*source_start, *source_duration},
                *timeline_start,
                TimeRange{*available_start, *available_duration},
                (flags & kClipLocked) != 0U,
                fromJavaId(fields[8]),
                (flags & kClipGroupLocked) != 0U,
                (flags & kClipEnabled) != 0U};
}

Result<Project> parseProject(JNIEnv* env,
                             jlong project_id,
                             jlong fps_numerator,
                             jlong fps_denominator,
                             jlongArray track_array,
                             jlongArray clip_array,
                             jlongArray marker_array) {
    if (project_id == 0) return std::unexpected(Error{ErrorCode::InvalidArgument});
    const auto rate = makeFrameRate(static_cast<std::int64_t>(fps_numerator),
                                    static_cast<std::int64_t>(fps_denominator));
    if (!rate) return std::unexpected(rate.error());

    std::vector<jlong> track_data;
    std::vector<jlong> clip_data;
    std::vector<jlong> marker_data;
    if (!copyLongArray(env, track_array, track_data) ||
        !copyLongArray(env, clip_array, clip_data) ||
        !copyLongArray(env, marker_array, marker_data)) {
        return std::unexpected(Error{ErrorCode::InvalidArgument});
    }
    if (track_data.size() % kTrackWidth != 0U || clip_data.size() % kClipWidth != 0U ||
        marker_data.size() % kMarkerWidth != 0U) {
        return std::unexpected(Error{ErrorCode::InvalidArgument});
    }

    Project project;
    project.id = fromJavaId(project_id);
    project.frame_rate = *rate;
    std::unordered_map<std::uint64_t, std::size_t> track_indices;
    project.tracks.reserve(track_data.size() / kTrackWidth);
    for (std::size_t index = 0; index < track_data.size(); index += kTrackWidth) {
        const std::uint64_t id = fromJavaId(track_data[index]);
        const std::uint64_t flags = fromJavaId(track_data[index + 1]);
        if (id == 0U || (flags & ~(kTrackLocked | kTrackAllowsOverlap)) != 0U) {
            return std::unexpected(Error{ErrorCode::InvalidArgument, 0, id});
        }
        track_indices.emplace(id, project.tracks.size());
        project.tracks.push_back(Track{TrackId{id},
                                       (flags & kTrackLocked) != 0U,
                                       (flags & kTrackAllowsOverlap) != 0U,
                                       {}});
    }

    for (std::size_t index = 0; index < clip_data.size(); index += kClipWidth) {
        auto clip = parseClipRecord(clip_data.data() + index, *rate);
        if (!clip) return std::unexpected(clip.error());
        const auto target = track_indices.find(clip->track_id.value);
        if (target == track_indices.end()) {
            return std::unexpected(Error{ErrorCode::TrackNotFound, clip->id.value,
                                         clip->track_id.value});
        }
        project.tracks[target->second].clips.push_back(std::move(*clip));
    }

    project.markers.reserve(marker_data.size() / kMarkerWidth);
    for (std::size_t index = 0; index < marker_data.size(); index += kMarkerWidth) {
        auto position = convertFrame(marker_data[index + 1], *rate);
        if (!position) return std::unexpected(position.error());
        project.markers.push_back(Marker{fromJavaId(marker_data[index]), *position});
    }

    const auto valid = validateProject(project);
    if (!valid) return std::unexpected(valid.error());
    return project;
}

Result<std::vector<EditCommand>> parseCommands(const std::vector<jlong>& data, FrameRate rate) {
    if (data.empty()) return std::unexpected(Error{ErrorCode::InvalidArgument});
    const jlong count_value = data[0];
    if (count_value <= 0 || static_cast<std::uint64_t>(count_value) > kMaxCommands) {
        return std::unexpected(Error{ErrorCode::LimitExceeded});
    }
    const auto count = static_cast<std::size_t>(count_value);
    std::vector<EditCommand> commands;
    commands.reserve(count);
    std::size_t cursor = 1;
    for (std::size_t command_index = 0; command_index < count; ++command_index) {
        if (data.size() - cursor < kCommandWidth) {
            return std::unexpected(Error{ErrorCode::InvalidArgument});
        }
        const auto* fields = data.data() + cursor;
        cursor += kCommandWidth;
        if (fields[0] < 0 || fields[0] > static_cast<jlong>(EditKind::SetGroupLock) ||
            fields[7] < 0 || fields[7] > static_cast<jlong>(RippleScope::AllTracks) ||
            fields[8] < 0 || static_cast<std::uint64_t>(fields[8]) > kMaxIdsPerCommand ||
            (fields[9] != 0 && fields[9] != 1)) {
            return std::unexpected(Error{ErrorCode::InvalidArgument, fromJavaId(fields[1]),
                                         fromJavaId(fields[3])});
        }
        const auto id_count = static_cast<std::size_t>(fields[8]);
        if (id_count > data.size() - cursor) {
            return std::unexpected(Error{ErrorCode::InvalidArgument});
        }
        EditCommand command;
        command.kind = static_cast<EditKind>(fields[0]);
        if (command.kind == EditKind::SetGroupLock && fields[5] != 0 && fields[5] != 1) {
            return std::unexpected(Error{ErrorCode::InvalidArgument, fromJavaId(fields[1]),
                                         fromJavaId(fields[3])});
        }
        command.clip_id = ClipId{fromJavaId(fields[1])};
        command.other_clip_id = ClipId{fromJavaId(fields[2])};
        command.target_track_id = TrackId{fromJavaId(fields[3])};
        command.ripple = static_cast<RippleScope>(fields[7]);
        if (command.kind == EditKind::SetGroupLock) {
            // Protocol field 5 carries an explicit boolean for this metadata command, not a time.
            command.group_locked = fields[5] != 0;
        } else {
            auto target = convertFrame(fields[4], rate);
            auto delta = convertFrame(fields[5], rate);
            auto minimum = convertFrame(fields[6], rate);
            if (!target || !delta || !minimum) {
                return std::unexpected(Error{ErrorCode::Overflow, command.clip_id.value,
                                             command.target_track_id.value});
            }
            command.target = *target;
            command.delta = *delta;
            command.minimum_duration = *minimum;
        }
        command.clip_ids.reserve(id_count);
        for (std::size_t index = 0; index < id_count; ++index) {
            command.clip_ids.push_back(ClipId{fromJavaId(data[cursor + index])});
        }
        cursor += id_count;
        if (fields[9] == 1) {
            if (command.kind != EditKind::Insert || data.size() - cursor < kClipWidth) {
                return std::unexpected(Error{ErrorCode::InvalidArgument, command.clip_id.value,
                                             command.target_track_id.value});
            }
            auto inserted = parseClipRecord(data.data() + cursor, rate);
            if (!inserted) return std::unexpected(inserted.error());
            command.inserted_clip = std::move(*inserted);
            cursor += kClipWidth;
        }
        if (command.kind != EditKind::Delete && command.kind != EditKind::Move &&
            !command.clip_ids.empty()) {
            return std::unexpected(Error{ErrorCode::InvalidArgument, command.clip_id.value,
                                         command.target_track_id.value});
        }
        commands.push_back(std::move(command));
    }
    if (cursor != data.size()) return std::unexpected(Error{ErrorCode::InvalidArgument});
    return commands;
}

Result<std::vector<jlong>> encodeEditResult(const EditResult& result) {
    const std::size_t clip_count = [&result]() {
        std::size_t count = 0;
        for (const auto& track : result.project.tracks) count += track.clips.size();
        return count;
    }();
    std::vector<jlong> output;
    output.reserve(kOutputHeaderWidth + clip_count * kOutputClipWidth +
                   result.changed_clip_ids.size());
    output.insert(output.end(), {0, 0, 0, toJavaId(projectHash(result.project)),
                                 static_cast<jlong>(clip_count),
                                 static_cast<jlong>(result.changed_clip_ids.size()), 0, 0, 0});
    if (result.affected_range.has_value()) {
        auto start = flicksToFrame(result.affected_range->start, result.project.frame_rate);
        auto duration = flicksToFrame(result.affected_range->duration, result.project.frame_rate);
        if (!start || !duration) return std::unexpected(Error{ErrorCode::Overflow});
        output[6] = 1;
        output[7] = static_cast<jlong>(*start);
        output[8] = static_cast<jlong>(*duration);
    }
    for (const auto& track : result.project.tracks) {
        for (const auto& clip : track.clips) {
            auto source_end = rangeEnd(clip.source_range);
            if (!source_end) return std::unexpected(source_end.error());
            auto source_start_frame = flicksToFrame(clip.source_range.start, result.project.frame_rate);
            auto source_end_frame = flicksToFrame(*source_end, result.project.frame_rate);
            auto timeline_frame = flicksToFrame(clip.timeline_start, result.project.frame_rate);
            if (!source_start_frame || !source_end_frame || !timeline_frame) {
                return std::unexpected(Error{ErrorCode::Overflow, clip.id.value, clip.track_id.value});
            }
            const std::uint64_t flags = (clip.locked ? kClipLocked : 0U) |
                                        (clip.group_locked ? kClipGroupLocked : 0U) |
                                        (clip.enabled ? kClipEnabled : 0U);
            output.push_back(toJavaId(clip.id.value));
            output.push_back(toJavaId(clip.track_id.value));
            output.push_back(static_cast<jlong>(*source_start_frame));
            output.push_back(static_cast<jlong>(*source_end_frame));
            output.push_back(static_cast<jlong>(*timeline_frame));
            output.push_back(toJavaId(flags));
            output.push_back(toJavaId(clip.group_id));
        }
    }
    for (const auto id : result.changed_clip_ids) output.push_back(toJavaId(id.value));
    return output;
}

bool sameClipState(const Clip& lhs, const Clip& rhs) noexcept {
    return lhs.id == rhs.id && lhs.track_id == rhs.track_id &&
           lhs.source_range == rhs.source_range && lhs.timeline_start == rhs.timeline_start &&
           lhs.available_range == rhs.available_range && lhs.locked == rhs.locked &&
           lhs.group_id == rhs.group_id && lhs.group_locked == rhs.group_locked &&
           lhs.enabled == rhs.enabled;
}

std::vector<ClipId> changedClipIds(const Project& before, const Project& after) {
    std::unordered_map<std::uint64_t, const Clip*> old_clips;
    std::unordered_map<std::uint64_t, const Clip*> new_clips;
    for (const auto& track : before.tracks) {
        for (const auto& clip : track.clips) old_clips.emplace(clip.id.value, &clip);
    }
    for (const auto& track : after.tracks) {
        for (const auto& clip : track.clips) new_clips.emplace(clip.id.value, &clip);
    }
    std::vector<ClipId> changed;
    changed.reserve(old_clips.size() + new_clips.size());
    for (const auto& [id, old_clip] : old_clips) {
        const auto current = new_clips.find(id);
        if (current == new_clips.end() || !sameClipState(*old_clip, *current->second)) {
            changed.push_back(ClipId{id});
        }
    }
    for (const auto& [id, new_clip] : new_clips) {
        if (!old_clips.contains(id)) changed.push_back(ClipId{id});
    }
    std::sort(changed.begin(), changed.end());
    changed.erase(std::unique(changed.begin(), changed.end()), changed.end());
    return changed;
}

EditResult makeHistoryResult(const Project& before, const Project& after) {
    EditResult result{after, changedClipIds(before, after), std::nullopt, projectHash(after)};
    return result;
}

jlongArray nativeCommitEdits(JNIEnv* env,
                             jobject,
                             jlong project_id,
                             jlong fps_numerator,
                             jlong fps_denominator,
                             jlongArray track_array,
                             jlongArray clip_array,
                             jlongArray marker_array,
                             jlongArray command_array) noexcept {
    try {
        auto project = parseProject(env, project_id, fps_numerator, fps_denominator,
                                    track_array, clip_array, marker_array);
        if (!project) return makeErrorArray(env, project.error(), kOutputHeaderWidth);
        std::vector<jlong> command_data;
        if (!copyLongArray(env, command_array, command_data)) {
            return makeErrorArray(env, Error{ErrorCode::InvalidArgument}, kOutputHeaderWidth);
        }
        auto commands = parseCommands(command_data, project->frame_rate);
        if (!commands) return makeErrorArray(env, commands.error(), kOutputHeaderWidth);
        auto applied = applyEdits(*project, *commands);
        if (!applied) return makeErrorArray(env, applied.error(), kOutputHeaderWidth);
        auto encoded = encodeEditResult(*applied);
        if (!encoded) return makeErrorArray(env, encoded.error(), kOutputHeaderWidth);

        jlongArray output = makeLongArray(env, *encoded);
        if (output == nullptr || env->ExceptionCheck() == JNI_TRUE) return nullptr;

        std::lock_guard lock(g_history_mutex);
        const std::uint64_t session_id = project->id;
        if (g_history_session.has_value() && g_history_session->project_id == session_id &&
            projectHash(g_history_session->current) == projectHash(*project)) {
            auto committed = g_history_session->history.applyBatch(g_history_session->current,
                                                                    *commands);
            if (!committed) {
                env->DeleteLocalRef(output);
                return makeErrorArray(env, committed.error(), kOutputHeaderWidth);
            }
        } else {
            NativeHistorySession candidate(session_id, std::move(*project));
            auto committed = candidate.history.applyBatch(candidate.current, *commands);
            if (!committed) {
                env->DeleteLocalRef(output);
                return makeErrorArray(env, committed.error(), kOutputHeaderWidth);
            }
            g_history_session = std::move(candidate);
        }
        return output;
    } catch (const std::bad_alloc&) {
        return makeErrorArray(env, Error{ErrorCode::AllocationFailure}, kOutputHeaderWidth);
    } catch (...) {
        return makeErrorArray(env, Error{ErrorCode::InvalidEdit}, kOutputHeaderWidth);
    }
}

void nativeClearHistory(JNIEnv*, jobject, jlong project_id) noexcept {
    try {
        std::lock_guard lock(g_history_mutex);
        if (g_history_session.has_value() &&
            g_history_session->project_id == fromJavaId(project_id)) {
            g_history_session.reset();
        }
    } catch (...) {
        // Clearing history is best-effort cleanup and must never throw through JNI.
    }
}

jlongArray nativeHistoryTransition(JNIEnv* env,
                                   jlong project_id,
                                   jlong fps_numerator,
                                   jlong fps_denominator,
                                   jlongArray track_array,
                                   jlongArray clip_array,
                                   jlongArray marker_array,
                                   bool undo) noexcept {
    try {
        auto project = parseProject(env, project_id, fps_numerator, fps_denominator,
                                    track_array, clip_array, marker_array);
        if (!project) return makeErrorArray(env, project.error(), kOutputHeaderWidth);
        std::lock_guard lock(g_history_mutex);
        if (!g_history_session.has_value() || g_history_session->project_id != project->id ||
            projectHash(g_history_session->current) != projectHash(*project)) {
            const auto code = undo ? ErrorCode::NoUndo : ErrorCode::NoRedo;
            return makeErrorArray(env, Error{code}, kOutputHeaderWidth);
        }

        NativeHistorySession candidate = *g_history_session;
        const Project before = candidate.current;
        auto transition = undo ? candidate.history.undo(candidate.current)
                               : candidate.history.redo(candidate.current);
        if (!transition) return makeErrorArray(env, transition.error(), kOutputHeaderWidth);
        auto encoded = encodeEditResult(makeHistoryResult(before, candidate.current));
        if (!encoded) return makeErrorArray(env, encoded.error(), kOutputHeaderWidth);
        jlongArray output = makeLongArray(env, *encoded);
        if (output == nullptr || env->ExceptionCheck() == JNI_TRUE) return nullptr;

        g_history_session = std::move(candidate);
        return output;
    } catch (const std::bad_alloc&) {
        return makeErrorArray(env, Error{ErrorCode::AllocationFailure}, kOutputHeaderWidth);
    } catch (...) {
        return makeErrorArray(env, Error{ErrorCode::InvalidEdit}, kOutputHeaderWidth);
    }
}

jlongArray nativeUndo(JNIEnv* env,
                      jobject,
                      jlong project_id,
                      jlong fps_numerator,
                      jlong fps_denominator,
                      jlongArray track_array,
                      jlongArray clip_array,
                      jlongArray marker_array) noexcept {
    return nativeHistoryTransition(env, project_id, fps_numerator, fps_denominator,
                                   track_array, clip_array, marker_array, true);
}

jlongArray nativeRedo(JNIEnv* env,
                      jobject,
                      jlong project_id,
                      jlong fps_numerator,
                      jlong fps_denominator,
                      jlongArray track_array,
                      jlongArray clip_array,
                      jlongArray marker_array) noexcept {
    return nativeHistoryTransition(env, project_id, fps_numerator, fps_denominator,
                                   track_array, clip_array, marker_array, false);
}

jlongArray nativeApplyEdits(JNIEnv* env,
                            jobject,
                            jlong project_id,
                            jlong fps_numerator,
                            jlong fps_denominator,
                            jlongArray track_array,
                            jlongArray clip_array,
                            jlongArray marker_array,
                            jlongArray command_array) noexcept {
    try {
        auto project = parseProject(env, project_id, fps_numerator, fps_denominator,
                                    track_array, clip_array, marker_array);
        if (!project) return makeErrorArray(env, project.error(), kOutputHeaderWidth);
        std::vector<jlong> command_data;
        if (!copyLongArray(env, command_array, command_data)) {
            return makeErrorArray(env, Error{ErrorCode::InvalidArgument}, kOutputHeaderWidth);
        }
        auto commands = parseCommands(command_data, project->frame_rate);
        if (!commands) return makeErrorArray(env, commands.error(), kOutputHeaderWidth);
        auto applied = applyEdits(*project, *commands);
        if (!applied) return makeErrorArray(env, applied.error(), kOutputHeaderWidth);
        auto encoded = encodeEditResult(*applied);
        if (!encoded) return makeErrorArray(env, encoded.error(), kOutputHeaderWidth);
        return makeLongArray(env, *encoded);
    } catch (const std::bad_alloc&) {
        return makeErrorArray(env, Error{ErrorCode::AllocationFailure}, kOutputHeaderWidth);
    } catch (...) {
        return makeErrorArray(env, Error{ErrorCode::InvalidEdit}, kOutputHeaderWidth);
    }
}

jlongArray nativeSnap(JNIEnv* env,
                      jobject,
                      jlong project_id,
                      jlong fps_numerator,
                      jlong fps_denominator,
                      jlongArray track_array,
                      jlongArray clip_array,
                      jlongArray marker_array,
                      jlong input_frame,
                      jlong playhead_frame,
                      jlong threshold_frames,
                      jlong grid_interval_frames,
                      jint flags,
                      jlong exclude_clip_id) noexcept {
    constexpr std::size_t kSnapResultWidth = 6;
    try {
        auto project = parseProject(env, project_id, fps_numerator, fps_denominator,
                                    track_array, clip_array, marker_array);
        if (!project) return makeErrorArray(env, project.error(), kSnapResultWidth);
        if (flags < 0 || (static_cast<std::uint32_t>(flags) & ~31U) != 0U) {
            return makeErrorArray(env, Error{ErrorCode::InvalidArgument}, kSnapResultWidth);
        }
        auto input = convertFrame(input_frame, project->frame_rate);
        auto playhead = convertFrame(playhead_frame, project->frame_rate);
        auto threshold = convertFrame(threshold_frames, project->frame_rate);
        auto grid_interval = convertFrame(grid_interval_frames, project->frame_rate);
        if (grid_interval_frames < 0) {
            return makeErrorArray(env, Error{ErrorCode::InvalidArgument}, kSnapResultWidth);
        }
        if (!input || !playhead || !threshold || !grid_interval) {
            return makeErrorArray(env, Error{ErrorCode::Overflow}, kSnapResultWidth);
        }
        SnapConfig config;
        config.threshold = *threshold;
        const auto snap_flags = static_cast<std::uint32_t>(flags);
        config.snap_to_start = (snap_flags & 1U) != 0U;
        config.snap_to_playhead = (snap_flags & 2U) != 0U;
        config.snap_to_clip_edges = (snap_flags & 4U) != 0U;
        config.snap_to_markers = (snap_flags & 8U) != 0U;
        config.snap_to_grid = (snap_flags & 16U) != 0U;
        config.grid_interval = *grid_interval;
        auto snapped = snap(*project, *input, *playhead, config,
                            ClipId{fromJavaId(exclude_clip_id)});
        if (!snapped) return makeErrorArray(env, snapped.error(), kSnapResultWidth);
        auto position_frame = flicksToFrame(snapped->position, project->frame_rate);
        auto distance_frame = flicksToFrame(snapped->distance, project->frame_rate);
        if (!position_frame || !distance_frame) {
            return makeErrorArray(env, Error{ErrorCode::Overflow}, kSnapResultWidth);
        }
        const std::array<jlong, kSnapResultWidth> output{
            0,
            snapped->snapped ? 1 : 0,
            static_cast<jlong>(*position_frame),
            static_cast<jlong>(snapped->kind),
            toJavaId(snapped->target_id),
            static_cast<jlong>(*distance_frame)};
        return makeLongArray(env, output);
    } catch (const std::bad_alloc&) {
        return makeErrorArray(env, Error{ErrorCode::AllocationFailure}, kSnapResultWidth);
    } catch (...) {
        return makeErrorArray(env, Error{ErrorCode::InvalidEdit}, kSnapResultWidth);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass("com/example/timeline/engine/native/v2/NativeTimelineCoreV2");
    if (bridge == nullptr) return JNI_ERR;
    const std::array<JNINativeMethod, 6> methods{{
        {const_cast<char*>("nativeApplyEdits"),
         const_cast<char*>("(JJJ[J[J[J[J)[J"),
         reinterpret_cast<void*>(nativeApplyEdits)},
        {const_cast<char*>("nativeCommitEdits"),
         const_cast<char*>("(JJJ[J[J[J[J)[J"),
         reinterpret_cast<void*>(nativeCommitEdits)},
        {const_cast<char*>("nativeUndo"),
         const_cast<char*>("(JJJ[J[J[J)[J"),
         reinterpret_cast<void*>(nativeUndo)},
        {const_cast<char*>("nativeRedo"),
         const_cast<char*>("(JJJ[J[J[J)[J"),
         reinterpret_cast<void*>(nativeRedo)},
        {const_cast<char*>("nativeClearHistory"),
         const_cast<char*>("(J)V"),
         reinterpret_cast<void*>(nativeClearHistory)},
        {const_cast<char*>("nativeSnap"),
         const_cast<char*>("(JJJ[J[J[JJJJJIJ)[J"),
         reinterpret_cast<void*>(nativeSnap)},
    }};
    // Keep this explicit registration table synchronized with NativeTimelineCoreV2's external API.
    const jint registered = env->RegisterNatives(bridge, methods.data(),
                                                   static_cast<jint>(methods.size()));
    env->DeleteLocalRef(bridge);
    if (registered != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
