#include "timeline_core_v2/timeline_core.h"

#include <algorithm>
#include <array>
#include <bit>
#include <limits>
#include <map>
#include <numeric>
#include <new>
#include <tuple>
#include <utility>

namespace timeline_core_v2 {
namespace {

Error makeError(ErrorCode code, ClipId clip = {}, TrackId track = {}) noexcept {
    return Error{code, clip.value, track.value};
}

bool addOverflow(std::int64_t lhs, std::int64_t rhs, std::int64_t& out) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_add_overflow(lhs, rhs, &out);
#else
    if ((rhs > 0 && lhs > std::numeric_limits<std::int64_t>::max() - rhs) ||
        (rhs < 0 && lhs < std::numeric_limits<std::int64_t>::min() - rhs)) {
        return true;
    }
    out = lhs + rhs;
    return false;
#endif
}

std::uint64_t unsignedMagnitude(std::int64_t value) noexcept {
    if (value >= 0) return static_cast<std::uint64_t>(value);
    return static_cast<std::uint64_t>(-(value + 1)) + 1U;
}

bool multiplyOverflow(std::int64_t lhs, std::int64_t rhs, std::int64_t& out) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_mul_overflow(lhs, rhs, &out);
#else
    const bool negative = (lhs < 0) != (rhs < 0);
    const auto left_magnitude = unsignedMagnitude(lhs);
    const auto right_magnitude = unsignedMagnitude(rhs);
    constexpr std::uint64_t kNegativeLimit = std::uint64_t{1} << 63U;
    const auto limit = negative ? kNegativeLimit
                                : static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
    if (right_magnitude != 0 && left_magnitude > limit / right_magnitude) return true;
    const auto product = left_magnitude * right_magnitude;
    if (!negative) {
        out = static_cast<std::int64_t>(product);
    } else if (product == kNegativeLimit) {
        out = std::numeric_limits<std::int64_t>::min();
    } else {
        out = -static_cast<std::int64_t>(product);
    }
    return false;
#endif
}

Result<std::int64_t> multiplyDivideRoundEven(std::int64_t a,
                                             std::int64_t b,
                                             std::int64_t divisor) noexcept {
    if (divisor <= 0) return std::unexpected(makeError(ErrorCode::InvalidArgument));
    const auto g1 = std::gcd(unsignedMagnitude(a), static_cast<std::uint64_t>(divisor));
    a /= static_cast<std::int64_t>(g1);
    divisor /= static_cast<std::int64_t>(g1);
    const auto g2 = std::gcd(unsignedMagnitude(b), static_cast<std::uint64_t>(divisor));
    b /= static_cast<std::int64_t>(g2);
    divisor /= static_cast<std::int64_t>(g2);
    std::int64_t product{};
    if (multiplyOverflow(a, b, product)) {
        return std::unexpected(makeError(ErrorCode::Overflow));
    }
    const std::int64_t quotient = product / divisor;
    const std::int64_t remainder = product % divisor;
    const auto remMagnitude = unsignedMagnitude(remainder);
    const auto twiceRemainder = remMagnitude * 2U;
    const auto divisorMagnitude = static_cast<std::uint64_t>(divisor);
    const bool roundAway = twiceRemainder > divisorMagnitude ||
                           (twiceRemainder == divisorMagnitude && (quotient % 2 != 0));
    if (!roundAway) return quotient;
    std::int64_t rounded{};
    const std::int64_t adjustment = product < 0 ? -1 : 1;
    if (addOverflow(quotient, adjustment, rounded)) {
        return std::unexpected(makeError(ErrorCode::Overflow));
    }
    return rounded;
}

Result<Flicks> timeRangeEnd(const TimeRange& range) noexcept {
    if (range.duration.value <= 0) {
        return std::unexpected(makeError(ErrorCode::InvalidRange));
    }
    return checkedAdd(range.start, range.duration);
}

Track* findTrack(Project& project, TrackId id) noexcept {
    const auto it = std::find_if(project.tracks.begin(), project.tracks.end(),
                                 [id](const Track& track) { return track.id == id; });
    return it == project.tracks.end() ? nullptr : &*it;
}

std::pair<Track*, Clip*> findClip(Project& project, ClipId id) noexcept {
    for (auto& track : project.tracks) {
        const auto it = std::find_if(track.clips.begin(), track.clips.end(),
                                     [id](const Clip& clip) { return clip.id == id; });
        if (it != track.clips.end()) return {&track, &*it};
    }
    return {nullptr, nullptr};
}

std::pair<const Track*, const Clip*> findClip(const Project& project, ClipId id) noexcept {
    for (const auto& track : project.tracks) {
        const auto it = std::find_if(track.clips.begin(), track.clips.end(),
                                     [id](const Clip& clip) { return clip.id == id; });
        if (it != track.clips.end()) return {&track, &*it};
    }
    return {nullptr, nullptr};
}

bool groupIsLocked(const Project& project, const Clip& clip) noexcept {
    if (clip.group_locked) return true;
    if (clip.group_id == 0U) return false;
    for (const auto& track : project.tracks) {
        for (const auto& member : track.clips) {
            if (member.group_id == clip.group_id && member.group_locked) return true;
        }
    }
    return false;
}

Status checkUnlocked(const Project& project, const Track& track, const Clip& clip) noexcept {
    if (track.locked) return std::unexpected(makeError(ErrorCode::LockedTrack, clip.id, track.id));
    if (clip.locked) return std::unexpected(makeError(ErrorCode::LockedClip, clip.id, track.id));
    if (groupIsLocked(project, clip)) {
        return std::unexpected(makeError(ErrorCode::LockedGroup, clip.id, track.id));
    }
    return {};
}

void markChanged(std::vector<ClipId>& changed, ClipId id) {
    if (std::find(changed.begin(), changed.end(), id) == changed.end()) changed.push_back(id);
}

Status shiftFollowers(Project& project,
                      TrackId trigger_track,
                      Flicks boundary,
                      Flicks amount,
                      RippleScope scope,
                      ClipId exclude,
                      std::vector<ClipId>& changed) {
    if (amount.value == 0 || scope == RippleScope::None) return {};
    for (auto& track : project.tracks) {
        if (scope == RippleScope::SameTrack && track.id != trigger_track) continue;
        for (auto& clip : track.clips) {
            if (clip.id == exclude || clip.timeline_start < boundary) continue;
            if (track.locked) {
                return std::unexpected(makeError(ErrorCode::LockedTrack, clip.id, track.id));
            }
            if (clip.locked) {
                return std::unexpected(makeError(ErrorCode::LockedClip, clip.id, track.id));
            }
            if (groupIsLocked(project, clip)) {
                return std::unexpected(makeError(ErrorCode::LockedGroup, clip.id, track.id));
            }
            auto shifted = checkedAdd(clip.timeline_start, amount);
            if (!shifted) return std::unexpected(shifted.error());
            if (shifted->value < 0) {
                return std::unexpected(makeError(ErrorCode::InvalidRange, clip.id, track.id));
            }
            clip.timeline_start = *shifted;
            markChanged(changed, clip.id);
        }
    }
    return {};
}

Status ensureSourceRange(const Clip& clip) noexcept {
    auto source_end = rangeEnd(clip.source_range);
    auto available_end = rangeEnd(clip.available_range);
    if (!source_end || !available_end || clip.source_range.start.value < clip.available_range.start.value ||
        source_end->value > available_end->value) {
        return std::unexpected(makeError(ErrorCode::SourceOutOfBounds, clip.id, clip.track_id));
    }
    return {};
}

Result<EditResult> finalizeEdit(Project&& next,
                               std::vector<ClipId>&& changed,
                               std::optional<TimeRange> affected) noexcept {
    auto valid = validateProject(next);
    if (!valid) return std::unexpected(valid.error());
    return EditResult{std::move(next), std::move(changed), affected, 0};
}

Status shiftAfterInsert(Project& project,
                        TrackId trigger_track,
                        Flicks position,
                        Flicks duration,
                        RippleScope scope,
                        std::vector<ClipId>& changed) {
    return shiftFollowers(project, trigger_track, position, duration, scope, {}, changed);
}

std::uint64_t hashWord(std::uint64_t hash, std::uint64_t word) noexcept {
    for (unsigned shift = 0; shift < 64; shift += 8) {
        hash ^= (word >> shift) & 0xffU;
        hash *= kFNVPrime;
    }
    return hash;
}

constexpr std::array<std::uint8_t, 8> kProjectMagic{'T', 'L', 'C', 'O', 'R', 'E', '2', 0};
constexpr std::uint64_t kMaximumSerializedItems = 1'000'000;

void appendU8(std::vector<std::uint8_t>& bytes, std::uint8_t value) {
    bytes.push_back(value);
}

void appendU32(std::vector<std::uint8_t>& bytes, std::uint32_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) {
        appendU8(bytes, static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

void appendU64(std::vector<std::uint8_t>& bytes, std::uint64_t value) {
    for (unsigned shift = 0; shift < 64; shift += 8) {
        appendU8(bytes, static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

void appendI64(std::vector<std::uint8_t>& bytes, std::int64_t value) {
    appendU64(bytes, std::bit_cast<std::uint64_t>(value));
}

class ByteReader {
public:
    explicit ByteReader(std::span<const std::uint8_t> bytes) noexcept : bytes_(bytes) {}

    bool readU8(std::uint8_t& value) noexcept {
        if (remaining() < 1) return false;
        value = bytes_[position_++];
        return true;
    }

    bool readU32(std::uint32_t& value) noexcept {
        value = 0;
        for (unsigned shift = 0; shift < 32; shift += 8) {
            std::uint8_t byte{};
            if (!readU8(byte)) return false;
            value |= static_cast<std::uint32_t>(byte) << shift;
        }
        return true;
    }

    bool readU64(std::uint64_t& value) noexcept {
        value = 0;
        for (unsigned shift = 0; shift < 64; shift += 8) {
            std::uint8_t byte{};
            if (!readU8(byte)) return false;
            value |= static_cast<std::uint64_t>(byte) << shift;
        }
        return true;
    }

    bool readI64(std::int64_t& value) noexcept {
        std::uint64_t bits{};
        if (!readU64(bits)) return false;
        value = std::bit_cast<std::int64_t>(bits);
        return true;
    }

    [[nodiscard]] std::size_t remaining() const noexcept { return bytes_.size() - position_; }

    bool consumeMagic() noexcept {
        for (const auto expected : kProjectMagic) {
            std::uint8_t actual{};
            if (!readU8(actual) || actual != expected) return false;
        }
        return true;
    }

    bool readCount(std::uint64_t minimum_record_bytes, std::uint64_t& count) noexcept {
        if (!readU64(count) || count > kMaximumSerializedItems || minimum_record_bytes == 0) {
            return false;
        }
        const auto maximum_from_input = static_cast<std::uint64_t>(remaining() /
                                                                   static_cast<std::size_t>(minimum_record_bytes));
        return count <= maximum_from_input;
    }

private:
    std::span<const std::uint8_t> bytes_;
    std::size_t position_{};
};

}  // namespace

std::string_view errorName(ErrorCode code) noexcept {
    switch (code) {
        case ErrorCode::InvalidArgument: return "TL_E_INVALID_ARGUMENT";
        case ErrorCode::Overflow: return "TL_E_OVERFLOW";
        case ErrorCode::InvalidRange: return "TL_E_INVALID_RANGE";
        case ErrorCode::InvalidProject: return "TL_E_INVALID_PROJECT";
        case ErrorCode::DuplicateId: return "TL_E_DUPLICATE_ID";
        case ErrorCode::ClipNotFound: return "TL_E_CLIP_NOT_FOUND";
        case ErrorCode::TrackNotFound: return "TL_E_TRACK_NOT_FOUND";
        case ErrorCode::LockedTrack: return "TL_E_LOCKED_TRACK";
        case ErrorCode::LockedClip: return "TL_E_LOCKED_CLIP";
        case ErrorCode::LockedGroup: return "TL_E_LOCKED_GROUP";
        case ErrorCode::SourceOutOfBounds: return "TL_E_SOURCE_OUT_OF_BOUNDS";
        case ErrorCode::MinimumDuration: return "TL_E_MIN_DURATION";
        case ErrorCode::Overlap: return "TL_E_OVERLAP";
        case ErrorCode::NotAdjacent: return "TL_E_NOT_ADJACENT";
        case ErrorCode::InvalidEdit: return "TL_E_INVALID_EDIT";
        case ErrorCode::NoUndo: return "TL_E_NO_UNDO";
        case ErrorCode::NoRedo: return "TL_E_NO_REDO";
        case ErrorCode::AllocationFailure: return "TL_E_ALLOCATION_FAILURE";
        case ErrorCode::UnsupportedVersion: return "TL_E_UNSUPPORTED_VERSION";
        case ErrorCode::CorruptData: return "TL_E_CORRUPT_DATA";
        case ErrorCode::LimitExceeded: return "TL_E_LIMIT_EXCEEDED";
        case ErrorCode::HistoryConflict: return "TL_E_HISTORY_CONFLICT";
    }
    return "TL_E_UNKNOWN";
}

Result<FrameRate> makeFrameRate(std::int64_t numerator,
                                std::int64_t denominator) noexcept {
    if (numerator <= 0 || denominator <= 0) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument));
    }
    const auto divisor = std::gcd(numerator, denominator);
    return FrameRate{numerator / divisor, denominator / divisor};
}

Result<Flicks> frameToFlicks(std::int64_t frame, FrameRate rate) noexcept {
    auto normalized = makeFrameRate(rate.numerator, rate.denominator);
    if (!normalized) return std::unexpected(normalized.error());
    std::int64_t scale{};
    if (multiplyOverflow(kFlicksPerSecond, normalized->denominator, scale)) {
        return std::unexpected(makeError(ErrorCode::Overflow));
    }
    auto result = multiplyDivideRoundEven(frame, scale, normalized->numerator);
    if (!result) return std::unexpected(result.error());
    return Flicks{*result};
}

Result<std::int64_t> flicksToFrame(Flicks time, FrameRate rate) noexcept {
    auto normalized = makeFrameRate(rate.numerator, rate.denominator);
    if (!normalized) return std::unexpected(normalized.error());
    std::int64_t denominator{};
    if (multiplyOverflow(kFlicksPerSecond, normalized->denominator, denominator)) {
        return std::unexpected(makeError(ErrorCode::Overflow));
    }
    return multiplyDivideRoundEven(time.value, normalized->numerator, denominator);
}

Result<Flicks> checkedAdd(Flicks lhs, Flicks rhs) noexcept {
    std::int64_t value{};
    if (addOverflow(lhs.value, rhs.value, value)) {
        return std::unexpected(makeError(ErrorCode::Overflow));
    }
    return Flicks{value};
}

Result<Flicks> checkedSubtract(Flicks lhs, Flicks rhs) noexcept {
    if (rhs.value == std::numeric_limits<std::int64_t>::min()) {
        if (lhs.value >= 0) return std::unexpected(makeError(ErrorCode::Overflow));
        std::int64_t value{};
        if (addOverflow(lhs.value, std::numeric_limits<std::int64_t>::max(), value) ||
            addOverflow(value, 1, value)) {
            return std::unexpected(makeError(ErrorCode::Overflow));
        }
        return Flicks{value};
    }
    return checkedAdd(lhs, Flicks{-rhs.value});
}

Result<Flicks> rangeEnd(const TimeRange& range) noexcept {
    return timeRangeEnd(range);
}

Result<Flicks> clipTimelineEnd(const Clip& clip) noexcept {
    if (clip.source_range.duration.value <= 0) {
        return std::unexpected(makeError(ErrorCode::InvalidRange, clip.id, clip.track_id));
    }
    return checkedAdd(clip.timeline_start, clip.source_range.duration);
}

Result<Flicks> projectDuration(const Project& project) noexcept {
    Flicks maximum{0};
    for (const auto& track : project.tracks) {
        for (const auto& clip : track.clips) {
            auto end = clipTimelineEnd(clip);
            if (!end) return std::unexpected(end.error());
            maximum.value = std::max(maximum.value, end->value);
        }
    }
    return maximum;
}

Status validateProject(const Project& project) noexcept {
    auto rate = makeFrameRate(project.frame_rate.numerator, project.frame_rate.denominator);
    if (!rate || *rate != project.frame_rate) {
        return std::unexpected(makeError(ErrorCode::InvalidProject));
    }
    try {
        std::vector<std::uint64_t> track_ids;
        std::vector<std::pair<std::uint64_t, TrackId>> clip_ids;
        std::vector<std::uint64_t> marker_ids;
        track_ids.reserve(project.tracks.size());
        marker_ids.reserve(project.markers.size());
        std::size_t total_clips = 0;
        for (const auto& track : project.tracks) {
            if (track.clips.size() > std::numeric_limits<std::size_t>::max() - total_clips) {
                return std::unexpected(makeError(ErrorCode::Overflow, {}, track.id));
            }
            total_clips += track.clips.size();
        }
        clip_ids.reserve(total_clips);
        std::vector<std::pair<std::int64_t, std::int64_t>> timeline_ranges;

        for (const auto& track : project.tracks) {
            if (track.id.value == 0) return std::unexpected(makeError(ErrorCode::InvalidProject));
            track_ids.push_back(track.id.value);
            timeline_ranges.clear();
            if (!track.allows_overlap) timeline_ranges.reserve(track.clips.size());
            for (const auto& clip : track.clips) {
                if (clip.id.value == 0 || clip.track_id != track.id ||
                    clip.timeline_start.value < 0 || clip.source_range.duration.value <= 0 ||
                    clip.available_range.duration.value <= 0) {
                    return std::unexpected(makeError(ErrorCode::InvalidProject, clip.id, track.id));
                }
                if (auto source_status = ensureSourceRange(clip); !source_status) {
                    return std::unexpected(source_status.error());
                }
                auto end = clipTimelineEnd(clip);
                if (!end || end->value < clip.timeline_start.value) {
                    return std::unexpected(makeError(ErrorCode::InvalidRange, clip.id, track.id));
                }
                clip_ids.emplace_back(clip.id.value, track.id);
                if (!track.allows_overlap) {
                    timeline_ranges.emplace_back(clip.timeline_start.value, end->value);
                }
            }
            if (!track.allows_overlap) {
                std::sort(timeline_ranges.begin(), timeline_ranges.end());
                for (std::size_t index = 1; index < timeline_ranges.size(); ++index) {
                    if (timeline_ranges[index].first < timeline_ranges[index - 1].second) {
                        return std::unexpected(makeError(ErrorCode::Overlap,
                                                          ClipId{}, track.id));
                    }
                }
            }
        }

        std::sort(track_ids.begin(), track_ids.end());
        if (std::adjacent_find(track_ids.begin(), track_ids.end()) != track_ids.end()) {
            return std::unexpected(makeError(ErrorCode::DuplicateId));
        }
        std::sort(clip_ids.begin(), clip_ids.end());
        const auto duplicate_clip = std::adjacent_find(
            clip_ids.begin(), clip_ids.end(), [](const auto& lhs, const auto& rhs) {
                return lhs.first == rhs.first;
            });
        if (duplicate_clip != clip_ids.end()) {
            return std::unexpected(makeError(ErrorCode::DuplicateId, ClipId{duplicate_clip->first},
                                              duplicate_clip->second));
        }

        for (const auto& marker : project.markers) {
            if (marker.id == 0 || marker.position.value < 0) {
                return std::unexpected(makeError(ErrorCode::InvalidProject));
            }
            marker_ids.push_back(marker.id);
        }
        std::sort(marker_ids.begin(), marker_ids.end());
        if (std::adjacent_find(marker_ids.begin(), marker_ids.end()) != marker_ids.end()) {
            return std::unexpected(makeError(ErrorCode::DuplicateId));
        }
        return {};
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidProject));
    }
}

std::uint64_t projectHash(const Project& project) noexcept {
    std::uint64_t hash = kFNVOffsetBasis;
    hash = hashWord(hash, project.id);
    hash = hashWord(hash, static_cast<std::uint64_t>(project.frame_rate.numerator));
    hash = hashWord(hash, static_cast<std::uint64_t>(project.frame_rate.denominator));
    hash = hashWord(hash, project.tracks.size());
    for (const auto& track : project.tracks) {
        hash = hashWord(hash, track.id.value);
        hash = hashWord(hash, track.locked ? 1U : 0U);
        hash = hashWord(hash, track.allows_overlap ? 1U : 0U);
        hash = hashWord(hash, track.clips.size());
        for (const auto& clip : track.clips) {
            hash = hashWord(hash, clip.id.value);
            hash = hashWord(hash, clip.track_id.value);
            hash = hashWord(hash, static_cast<std::uint64_t>(clip.source_range.start.value));
            hash = hashWord(hash, static_cast<std::uint64_t>(clip.source_range.duration.value));
            hash = hashWord(hash, static_cast<std::uint64_t>(clip.timeline_start.value));
            hash = hashWord(hash, static_cast<std::uint64_t>(clip.available_range.start.value));
            hash = hashWord(hash, static_cast<std::uint64_t>(clip.available_range.duration.value));
            hash = hashWord(hash, clip.locked ? 1U : 0U);
            hash = hashWord(hash, clip.group_id);
            hash = hashWord(hash, clip.group_locked ? 1U : 0U);
            hash = hashWord(hash, clip.enabled ? 1U : 0U);
        }
    }
    hash = hashWord(hash, project.markers.size());
    for (const auto& marker : project.markers) {
        hash = hashWord(hash, marker.id);
        hash = hashWord(hash, static_cast<std::uint64_t>(marker.position.value));
    }
    return hash;
}

Result<std::vector<std::uint8_t>> serializeProject(const Project& project) noexcept {
    if (project.tracks.size() > kMaximumSerializedItems ||
        project.markers.size() > kMaximumSerializedItems) {
        return std::unexpected(makeError(ErrorCode::LimitExceeded));
    }
    for (const auto& track : project.tracks) {
        if (track.clips.size() > kMaximumSerializedItems) {
            return std::unexpected(makeError(ErrorCode::LimitExceeded, {}, track.id));
        }
    }
    auto valid = validateProject(project);
    if (!valid) return std::unexpected(valid.error());
    try {
        std::vector<std::uint8_t> bytes;
        for (const auto byte : kProjectMagic) appendU8(bytes, byte);
        appendU32(bytes, kProjectSchemaVersion);
        appendU64(bytes, project.id);
        appendI64(bytes, project.frame_rate.numerator);
        appendI64(bytes, project.frame_rate.denominator);
        appendU64(bytes, static_cast<std::uint64_t>(project.tracks.size()));
        for (const auto& track : project.tracks) {
            appendU64(bytes, track.id.value);
            const auto flags = static_cast<std::uint8_t>((track.locked ? 1U : 0U) |
                                                         (track.allows_overlap ? 2U : 0U));
            appendU8(bytes, flags);
            appendU64(bytes, static_cast<std::uint64_t>(track.clips.size()));
            for (const auto& clip : track.clips) {
                appendU64(bytes, clip.id.value);
                appendU64(bytes, clip.track_id.value);
                appendI64(bytes, clip.source_range.start.value);
                appendI64(bytes, clip.source_range.duration.value);
                appendI64(bytes, clip.timeline_start.value);
                appendI64(bytes, clip.available_range.start.value);
                appendI64(bytes, clip.available_range.duration.value);
                appendU64(bytes, clip.group_id);
                const auto clip_flags = static_cast<std::uint8_t>((clip.locked ? 1U : 0U) |
                                                                  (clip.group_locked ? 2U : 0U) |
                                                                  (clip.enabled ? 4U : 0U));
                appendU8(bytes, clip_flags);
            }
        }
        appendU64(bytes, static_cast<std::uint64_t>(project.markers.size()));
        for (const auto& marker : project.markers) {
            appendU64(bytes, marker.id);
            appendI64(bytes, marker.position.value);
        }
        return bytes;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidProject));
    }
}

Result<Project> deserializeProject(std::span<const std::uint8_t> bytes) noexcept {
    try {
        ByteReader reader(bytes);
        if (!reader.consumeMagic()) return std::unexpected(makeError(ErrorCode::CorruptData));
        std::uint32_t version{};
        if (!reader.readU32(version)) return std::unexpected(makeError(ErrorCode::CorruptData));
        if (version != kProjectSchemaVersion) {
            return std::unexpected(makeError(ErrorCode::UnsupportedVersion));
        }

        Project project;
        std::int64_t numerator{};
        std::int64_t denominator{};
        std::uint64_t track_count{};
        if (!reader.readU64(project.id) || !reader.readI64(numerator) ||
            !reader.readI64(denominator) || !reader.readCount(17, track_count)) {
            return std::unexpected(makeError(ErrorCode::CorruptData));
        }
        project.frame_rate = FrameRate{numerator, denominator};
        project.tracks.reserve(static_cast<std::size_t>(track_count));
        for (std::uint64_t track_index = 0; track_index < track_count; ++track_index) {
            std::uint64_t track_id{};
            std::uint64_t clip_count{};
            std::uint8_t flags{};
            if (!reader.readU64(track_id) || !reader.readU8(flags) || (flags & 0xfcU) != 0 ||
                !reader.readCount(65, clip_count)) {
                return std::unexpected(makeError(ErrorCode::CorruptData));
            }
            Track track{TrackId{track_id}, (flags & 1U) != 0, (flags & 2U) != 0, {}};
            track.clips.reserve(static_cast<std::size_t>(clip_count));
            for (std::uint64_t clip_index = 0; clip_index < clip_count; ++clip_index) {
                std::uint64_t clip_id{};
                std::uint64_t clip_track_id{};
                std::int64_t source_start{};
                std::int64_t source_duration{};
                std::int64_t timeline_start{};
                std::int64_t available_start{};
                std::int64_t available_duration{};
                std::uint64_t group_id{};
                std::uint8_t clip_flags{};
                if (!reader.readU64(clip_id) || !reader.readU64(clip_track_id) ||
                    !reader.readI64(source_start) || !reader.readI64(source_duration) ||
                    !reader.readI64(timeline_start) || !reader.readI64(available_start) ||
                    !reader.readI64(available_duration) || !reader.readU64(group_id) ||
                    !reader.readU8(clip_flags) || (clip_flags & 0xf8U) != 0) {
                    return std::unexpected(makeError(ErrorCode::CorruptData));
                }
                track.clips.push_back(Clip{ClipId{clip_id}, TrackId{clip_track_id},
                                           TimeRange{Flicks{source_start}, Flicks{source_duration}},
                                           Flicks{timeline_start},
                                           TimeRange{Flicks{available_start}, Flicks{available_duration}},
                                           (clip_flags & 1U) != 0, group_id,
                                           (clip_flags & 2U) != 0, (clip_flags & 4U) != 0});
            }
            project.tracks.push_back(std::move(track));
        }

        std::uint64_t marker_count{};
        if (!reader.readCount(16, marker_count)) {
            return std::unexpected(makeError(ErrorCode::CorruptData));
        }
        project.markers.reserve(static_cast<std::size_t>(marker_count));
        for (std::uint64_t marker_index = 0; marker_index < marker_count; ++marker_index) {
            std::uint64_t marker_id{};
            std::int64_t position{};
            if (!reader.readU64(marker_id) || !reader.readI64(position)) {
                return std::unexpected(makeError(ErrorCode::CorruptData));
            }
            project.markers.push_back(Marker{marker_id, Flicks{position}});
        }
        if (reader.remaining() != 0) return std::unexpected(makeError(ErrorCode::CorruptData));
        if (!validateProject(project)) return std::unexpected(makeError(ErrorCode::CorruptData));
        return project;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::CorruptData));
    }
}

Result<TrimResult> computeTrim(const Clip& clip, const TrimRequest& request) noexcept {
    if (clip.locked) return std::unexpected(makeError(ErrorCode::LockedClip, clip.id, clip.track_id));
    if (clip.group_locked) return std::unexpected(makeError(ErrorCode::LockedGroup, clip.id, clip.track_id));
    if ((request.edge != TrimEdge::Start && request.edge != TrimEdge::End) ||
        request.minimum_duration.value <= 0) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument, clip.id, clip.track_id));
    }
    if (clip.source_range.duration.value <= 0 || clip.available_range.duration.value <= 0 ||
        clip.timeline_start.value < 0 || request.new_timeline_edge.value < 0) {
        return std::unexpected(makeError(ErrorCode::InvalidRange, clip.id, clip.track_id));
    }
    if (auto source_status = ensureSourceRange(clip); !source_status) {
        return std::unexpected(source_status.error());
    }
    auto source_end = rangeEnd(clip.source_range);
    auto available_end = rangeEnd(clip.available_range);
    auto timeline_end = clipTimelineEnd(clip);
    if (!source_end || !available_end || !timeline_end) {
        return std::unexpected(makeError(ErrorCode::Overflow, clip.id, clip.track_id));
    }
    Clip updated = clip;
    if (request.edge == TrimEdge::Start) {
        auto delta = checkedSubtract(request.new_timeline_edge, clip.timeline_start);
        if (!delta) return std::unexpected(delta.error());
        auto source_start = checkedAdd(clip.source_range.start, *delta);
        if (!source_start) return std::unexpected(source_start.error());
        auto duration = checkedSubtract(*source_end, *source_start);
        if (!duration) return std::unexpected(duration.error());
        if (duration->value < request.minimum_duration.value) {
            return std::unexpected(makeError(ErrorCode::MinimumDuration, clip.id, clip.track_id));
        }
        if (source_start->value < clip.available_range.start.value ||
            source_start->value >= available_end->value) {
            return std::unexpected(makeError(ErrorCode::SourceOutOfBounds, clip.id, clip.track_id));
        }
        updated.source_range = TimeRange{*source_start, *duration};
        updated.timeline_start = request.new_timeline_edge;
    } else {
        auto delta = checkedSubtract(request.new_timeline_edge, *timeline_end);
        if (!delta) return std::unexpected(delta.error());
        auto new_source_end = checkedAdd(*source_end, *delta);
        if (!new_source_end) return std::unexpected(new_source_end.error());
        auto duration = checkedSubtract(*new_source_end, clip.source_range.start);
        if (!duration) return std::unexpected(duration.error());
        if (duration->value < request.minimum_duration.value) {
            return std::unexpected(makeError(ErrorCode::MinimumDuration, clip.id, clip.track_id));
        }
        if (new_source_end->value > available_end->value ||
            new_source_end->value <= clip.source_range.start.value) {
            return std::unexpected(makeError(ErrorCode::SourceOutOfBounds, clip.id, clip.track_id));
        }
        updated.source_range.duration = *duration;
    }
    const auto duration_delta = checkedSubtract(updated.source_range.duration, clip.source_range.duration);
    const auto start_delta = checkedSubtract(updated.timeline_start, clip.timeline_start);
    if (!duration_delta || !start_delta) {
        return std::unexpected(makeError(ErrorCode::Overflow, clip.id, clip.track_id));
    }
    return TrimResult{updated, *duration_delta, *start_delta};
}

Result<EditResult> applyEdit(const Project& project, const EditCommand& command) noexcept {
    if (command.ripple != RippleScope::None && command.ripple != RippleScope::SameTrack &&
        command.ripple != RippleScope::AllTracks) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument));
    }
    const bool supports_ripple = command.kind == EditKind::TrimStart ||
                                 command.kind == EditKind::TrimEnd ||
                                 command.kind == EditKind::Insert ||
                                 command.kind == EditKind::Delete;
    if (command.ripple != RippleScope::None && !supports_ripple) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id));
    }
    if ((command.kind == EditKind::SetGroupLock) != command.group_locked.has_value()) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id));
    }
    switch (command.kind) {
        case EditKind::TrimStart:
        case EditKind::TrimEnd:
        case EditKind::Roll:
        case EditKind::Slide:
            if (command.minimum_duration.value <= 0) {
                return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id));
            }
            break;
        case EditKind::Move:
        case EditKind::Insert:
        case EditKind::Delete:
        case EditKind::Split:
        case EditKind::Slip:
        case EditKind::SetGroupLock:
            break;
        default:
            return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id));
    }
    auto valid_input = validateProject(project);
    if (!valid_input) return std::unexpected(valid_input.error());
    try {
        Project next = project;
        std::vector<ClipId> changed;
        std::optional<TimeRange> affected;
        const auto [original_track, original_clip] = findClip(project, command.clip_id);

        switch (command.kind) {
            case EditKind::TrimStart:
            case EditKind::TrimEnd: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (auto unlocked = checkUnlocked(project, *original_track, *original_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                const auto edge = command.kind == EditKind::TrimStart ? TrimEdge::Start : TrimEdge::End;
                auto trimmed = computeTrim(*original_clip,
                                           TrimRequest{edge, command.target, command.minimum_duration});
                if (!trimmed) return std::unexpected(trimmed.error());
                auto [next_track, next_clip] = findClip(next, command.clip_id);
                if (next_track == nullptr || next_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                const auto old_end = clipTimelineEnd(*original_clip);
                if (!old_end) return std::unexpected(old_end.error());
                *next_clip = trimmed->clip;
                Flicks shift{0};
                Flicks boundary = *old_end;
                if (command.ripple != RippleScope::None) {
                    // Ripple trim-in keeps the clip body anchored at its old left edge. The same
                    // computeTrim result supplies source bounds; only the ripple policy anchors it.
                    if (edge == TrimEdge::Start) next_clip->timeline_start = original_clip->timeline_start;
                    auto new_end = clipTimelineEnd(*next_clip);
                    if (!new_end) return std::unexpected(new_end.error());
                    auto end_delta = checkedSubtract(*new_end, *old_end);
                    if (!end_delta) return std::unexpected(end_delta.error());
                    shift = *end_delta;
                }
                markChanged(changed, command.clip_id);
                if (command.ripple != RippleScope::None) {
                    auto shifted = shiftFollowers(next, original_track->id, boundary, shift,
                                                  command.ripple, command.clip_id, changed);
                    if (!shifted) return std::unexpected(shifted.error());
                }
                auto new_end = clipTimelineEnd(*next_clip);
                if (!new_end) return std::unexpected(new_end.error());
                affected = TimeRange{std::min(original_clip->timeline_start, next_clip->timeline_start),
                                     Flicks{std::max(old_end->value, new_end->value) -
                                            std::min(original_clip->timeline_start.value,
                                                     next_clip->timeline_start.value)}};
                break;
            }
            case EditKind::Move: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (auto unlocked = checkUnlocked(project, *original_track, *original_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                Track* destination = findTrack(next, command.target_track_id);
                if (destination == nullptr) {
                    return std::unexpected(makeError(ErrorCode::TrackNotFound, command.clip_id,
                                                      command.target_track_id));
                }
                if (destination->locked) {
                    return std::unexpected(makeError(ErrorCode::LockedTrack, command.clip_id,
                                                      command.target_track_id));
                }
                if (command.target.value < 0) {
                    return std::unexpected(makeError(ErrorCode::InvalidRange, command.clip_id,
                                                      command.target_track_id));
                }
                const auto move_ids = command.clip_ids.empty()
                                          ? std::vector<ClipId>{command.clip_id}
                                          : command.clip_ids;
                std::vector<ClipId> unique_move_ids = move_ids;
                std::sort(unique_move_ids.begin(), unique_move_ids.end());
                if (std::adjacent_find(unique_move_ids.begin(), unique_move_ids.end()) !=
                        unique_move_ids.end() ||
                    !std::binary_search(unique_move_ids.begin(), unique_move_ids.end(), command.clip_id)) {
                    return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id,
                                                      command.target_track_id));
                }
                const auto primary_delta = checkedSubtract(command.target, original_clip->timeline_start);
                if (!primary_delta) return std::unexpected(primary_delta.error());
                struct MoveEntry {
                    TrackId source_track_id;
                    TrackId destination_track_id;
                    Clip moved_clip;
                    Flicks old_start;
                    Flicks old_end;
                    Flicks new_end;
                };
                std::vector<MoveEntry> entries;
                entries.reserve(move_ids.size());
                for (const auto id : move_ids) {
                    const auto [source_track, source_clip] = findClip(next, id);
                    if (source_clip == nullptr || source_track == nullptr) {
                        return std::unexpected(makeError(ErrorCode::ClipNotFound, id,
                                                          command.target_track_id));
                    }
                    if (auto unlocked = checkUnlocked(project, *source_track, *source_clip); !unlocked) {
                        return std::unexpected(unlocked.error());
                    }
                    auto new_start = checkedAdd(source_clip->timeline_start, *primary_delta);
                    if (!new_start || new_start->value < 0) {
                        return std::unexpected(makeError(ErrorCode::InvalidRange, id,
                                                          command.target_track_id));
                    }
                    Clip shifted = *source_clip;
                    shifted.timeline_start = *new_start;
                    const TrackId destination_id = id == command.clip_id ? command.target_track_id
                                                                          : source_track->id;
                    if (id == command.clip_id) shifted.track_id = destination_id;
                    const Track* destination_check = findTrack(next, destination_id);
                    if (destination_check == nullptr) {
                        return std::unexpected(makeError(ErrorCode::TrackNotFound, id, destination_id));
                    }
                    if (destination_check->locked) {
                        return std::unexpected(makeError(ErrorCode::LockedTrack, id, destination_id));
                    }
                    auto old_end = clipTimelineEnd(*source_clip);
                    auto new_end = clipTimelineEnd(shifted);
                    if (!old_end) return std::unexpected(old_end.error());
                    if (!new_end) return std::unexpected(new_end.error());
                    entries.push_back(MoveEntry{source_track->id, destination_id, std::move(shifted),
                                                source_clip->timeline_start, *old_end, *new_end});
                }
                if (primary_delta->value == 0 && destination->id == original_track->id) {
                    return EditResult{project, {}, std::nullopt, projectHash(project)};
                }
                for (auto& track : next.tracks) {
                    std::erase_if(track.clips, [&unique_move_ids](const Clip& clip) {
                        return std::binary_search(unique_move_ids.begin(), unique_move_ids.end(), clip.id);
                    });
                }
                for (const auto& entry : entries) {
                    Track* target = findTrack(next, entry.destination_track_id);
                    if (target == nullptr) {
                        return std::unexpected(makeError(ErrorCode::TrackNotFound,
                                                          entry.moved_clip.id,
                                                          entry.destination_track_id));
                    }
                    target->clips.push_back(entry.moved_clip);
                    markChanged(changed, entry.moved_clip.id);
                    const auto start = std::min(entry.old_start.value, entry.moved_clip.timeline_start.value);
                    const auto end = std::max(entry.old_end.value, entry.new_end.value);
                    if (!affected.has_value()) {
                        affected = TimeRange{Flicks{start}, Flicks{end - start}};
                    } else {
                        const auto combined_start = std::min(affected->start.value, start);
                        const auto affected_end = checkedAdd(affected->start, affected->duration);
                        if (!affected_end) return std::unexpected(affected_end.error());
                        const auto combined_end = std::max(affected_end->value, end);
                        affected = TimeRange{Flicks{combined_start}, Flicks{combined_end - combined_start}};
                    }
                }
                break;
            }
            case EditKind::Insert: {
                if (!command.inserted_clip.has_value()) {
                    return std::unexpected(makeError(ErrorCode::InvalidArgument));
                }
                Clip inserted = *command.inserted_clip;
                Track* destination = findTrack(next, inserted.track_id);
                if (destination == nullptr) {
                    return std::unexpected(makeError(ErrorCode::TrackNotFound, inserted.id,
                                                      inserted.track_id));
                }
                if (destination->locked) {
                    return std::unexpected(makeError(ErrorCode::LockedTrack, inserted.id,
                                                      inserted.track_id));
                }
                if (inserted.locked || groupIsLocked(next, inserted)) {
                    return std::unexpected(makeError(inserted.locked ? ErrorCode::LockedClip
                                                                    : ErrorCode::LockedGroup,
                                                      inserted.id, inserted.track_id));
                }
                for (const auto& track : next.tracks) {
                    for (const auto& clip : track.clips) {
                        if (clip.id == inserted.id) {
                            return std::unexpected(makeError(ErrorCode::DuplicateId, inserted.id,
                                                              inserted.track_id));
                        }
                    }
                }
                if (command.ripple != RippleScope::None) {
                    auto shifted = shiftAfterInsert(next, inserted.track_id, inserted.timeline_start,
                                                    inserted.source_range.duration, command.ripple, changed);
                    if (!shifted) return std::unexpected(shifted.error());
                }
                destination = findTrack(next, inserted.track_id);
                if (destination == nullptr) return std::unexpected(makeError(ErrorCode::TrackNotFound));
                destination->clips.push_back(inserted);
                markChanged(changed, inserted.id);
                auto inserted_end = clipTimelineEnd(inserted);
                if (!inserted_end) return std::unexpected(inserted_end.error());
                affected = TimeRange{inserted.timeline_start, inserted.source_range.duration};
                break;
            }
            case EditKind::Delete: {
                std::vector<ClipId> ids = command.clip_ids;
                if (ids.empty()) ids.push_back(command.clip_id);
                if (ids.empty()) return std::unexpected(makeError(ErrorCode::InvalidArgument));
                struct RemovedInterval { TrackId track; Flicks start; Flicks end; ClipId id; };
                std::vector<RemovedInterval> removed;
                for (std::size_t index = 0; index < ids.size(); ++index) {
                    if (std::find(ids.begin(), ids.begin() + static_cast<std::ptrdiff_t>(index),
                                  ids[index]) != ids.begin() + static_cast<std::ptrdiff_t>(index)) {
                        return std::unexpected(makeError(ErrorCode::InvalidArgument, ids[index]));
                    }
                    const auto id = ids[index];
                    const auto [track, clip] = findClip(next, id);
                    if (track == nullptr || clip == nullptr) {
                        return std::unexpected(makeError(ErrorCode::ClipNotFound, id));
                    }
                    if (auto unlocked = checkUnlocked(project, *track, *clip); !unlocked) {
                        return std::unexpected(unlocked.error());
                    }
                    auto end = clipTimelineEnd(*clip);
                    if (!end) return std::unexpected(end.error());
                    removed.push_back({track->id, clip->timeline_start, *end, clip->id});
                }
                for (auto& track : next.tracks) {
                    const auto before = track.clips.size();
                    std::erase_if(track.clips, [&ids](const Clip& clip) {
                        return std::find(ids.begin(), ids.end(), clip.id) != ids.end();
                    });
                    if (before != track.clips.size()) {
                        for (const auto id : ids) markChanged(changed, id);
                    }
                }
                if (command.ripple != RippleScope::None) {
                    std::sort(removed.begin(), removed.end(), [](const RemovedInterval& lhs,
                                                                 const RemovedInterval& rhs) {
                        return lhs.start.value < rhs.start.value ||
                               (lhs.start.value == rhs.start.value && lhs.end.value < rhs.end.value);
                    });
                    for (auto& track : next.tracks) {
                        if (command.ripple == RippleScope::SameTrack &&
                            std::none_of(removed.begin(), removed.end(), [&track](const RemovedInterval& r) {
                                return r.track == track.id;
                            })) {
                            continue;
                        }
                        for (auto& survivor : track.clips) {
                            bool have_union = false;
                            std::int64_t union_start{};
                            std::int64_t union_end{};
                            std::int64_t removed_before{};
                            auto add_union = [&]() noexcept {
                                if (!have_union) return false;
                                const auto length = union_end - union_start;
                                return addOverflow(removed_before, length, removed_before);
                            };
                            for (const auto& interval : removed) {
                                const bool applies = command.ripple == RippleScope::AllTracks ||
                                                     interval.track == track.id;
                                if (!applies || interval.end.value > survivor.timeline_start.value) continue;
                                if (!have_union) {
                                    union_start = interval.start.value;
                                    union_end = interval.end.value;
                                    have_union = true;
                                } else if (interval.start.value <= union_end) {
                                    union_end = std::max(union_end, interval.end.value);
                                } else {
                                    if (add_union()) {
                                        return std::unexpected(makeError(ErrorCode::Overflow, survivor.id,
                                                                          track.id));
                                    }
                                    union_start = interval.start.value;
                                    union_end = interval.end.value;
                                }
                            }
                            if (add_union()) {
                                return std::unexpected(makeError(ErrorCode::Overflow, survivor.id,
                                                                  track.id));
                            }
                            if (removed_before == 0) continue;
                            if (track.locked) {
                                return std::unexpected(makeError(ErrorCode::LockedTrack, survivor.id,
                                                                  track.id));
                            }
                            if (survivor.locked) {
                                return std::unexpected(makeError(ErrorCode::LockedClip, survivor.id,
                                                                  track.id));
                            }
                            if (groupIsLocked(next, survivor)) {
                                return std::unexpected(makeError(ErrorCode::LockedGroup, survivor.id,
                                                                  track.id));
                            }
                            std::int64_t new_start{};
                            if (addOverflow(survivor.timeline_start.value, -removed_before, new_start) ||
                                new_start < 0) {
                                return std::unexpected(makeError(ErrorCode::InvalidRange, survivor.id,
                                                                  track.id));
                            }
                            survivor.timeline_start = Flicks{new_start};
                            markChanged(changed, survivor.id);
                        }
                    }
                }
                if (!removed.empty()) {
                    auto first = std::min_element(removed.begin(), removed.end(),
                                                  [](const auto& a, const auto& b) {
                                                      return a.start.value < b.start.value;
                                                  });
                    auto last = std::max_element(removed.begin(), removed.end(),
                                                 [](const auto& a, const auto& b) {
                                                     return a.end.value < b.end.value;
                                                 });
                    affected = TimeRange{first->start, Flicks{last->end.value - first->start.value}};
                }
                break;
            }
            case EditKind::Split: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (auto unlocked = checkUnlocked(project, *original_track, *original_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                if (command.other_clip_id.value == 0) {
                    return std::unexpected(makeError(ErrorCode::InvalidArgument, command.clip_id,
                                                      original_track->id));
                }
                for (const auto& track : next.tracks) {
                    for (const auto& clip : track.clips) {
                        if (clip.id == command.other_clip_id) {
                            return std::unexpected(makeError(ErrorCode::DuplicateId,
                                                              command.other_clip_id, track.id));
                        }
                    }
                }
                auto old_end = clipTimelineEnd(*original_clip);
                if (!old_end) return std::unexpected(old_end.error());
                if (command.target.value <= original_clip->timeline_start.value ||
                    command.target.value >= old_end->value) {
                    return std::unexpected(makeError(ErrorCode::InvalidEdit, command.clip_id,
                                                      original_track->id));
                }
                const auto offset = checkedSubtract(command.target, original_clip->timeline_start);
                if (!offset) return std::unexpected(offset.error());
                const auto right_source_start = checkedAdd(original_clip->source_range.start, *offset);
                if (!right_source_start) return std::unexpected(right_source_start.error());
                auto [track, left] = findClip(next, command.clip_id);
                if (track == nullptr || left == nullptr) return std::unexpected(makeError(ErrorCode::ClipNotFound));
                Clip right = *left;
                const auto left_duration = offset->value;
                const auto right_duration = original_clip->source_range.duration.value - left_duration;
                left->source_range.duration = Flicks{left_duration};
                right.id = command.other_clip_id;
                right.source_range = TimeRange{*right_source_start, Flicks{right_duration}};
                right.timeline_start = command.target;
                track->clips.push_back(right);
                markChanged(changed, command.clip_id);
                markChanged(changed, command.other_clip_id);
                affected = TimeRange{original_clip->timeline_start, original_clip->source_range.duration};
                break;
            }
            case EditKind::Slip: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (auto unlocked = checkUnlocked(project, *original_track, *original_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                auto available_end = rangeEnd(original_clip->available_range);
                if (!available_end) return std::unexpected(available_end.error());
                const auto max_source_start = checkedSubtract(*available_end, original_clip->source_range.duration);
                if (!max_source_start) return std::unexpected(max_source_start.error());
                auto requested_start = checkedAdd(original_clip->source_range.start, command.delta);
                if (!requested_start) return std::unexpected(requested_start.error());
                const Flicks new_start{std::clamp(requested_start->value,
                                                   original_clip->available_range.start.value,
                                                   max_source_start->value)};
                auto [track, clip] = findClip(next, command.clip_id);
                (void)track;
                if (clip == nullptr) return std::unexpected(makeError(ErrorCode::ClipNotFound));
                clip->source_range.start = new_start;
                markChanged(changed, command.clip_id);
                affected = TimeRange{clip->timeline_start, clip->source_range.duration};
                break;
            }
            case EditKind::Roll: {
                const auto [left_track, left_clip] = findClip(project, command.clip_id);
                const auto [right_track, right_clip] = findClip(project, command.other_clip_id);
                if (left_track == nullptr || left_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (right_track == nullptr || right_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.other_clip_id));
                }
                if (left_track->id != right_track->id) {
                    return std::unexpected(makeError(ErrorCode::NotAdjacent, command.clip_id,
                                                      left_track->id));
                }
                if (auto unlocked = checkUnlocked(project, *left_track, *left_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                if (auto unlocked = checkUnlocked(project, *right_track, *right_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                auto left_end = clipTimelineEnd(*left_clip);
                auto right_end = clipTimelineEnd(*right_clip);
                if (!left_end || !right_end || left_end->value != right_clip->timeline_start.value) {
                    return std::unexpected(makeError(ErrorCode::NotAdjacent, command.clip_id,
                                                      left_track->id));
                }
                const Flicks delta = command.delta;
                auto new_cut = checkedAdd(*left_end, delta);
                if (!new_cut) return std::unexpected(new_cut.error());
                auto left_source_end = rangeEnd(left_clip->source_range);
                if (!left_source_end) return std::unexpected(left_source_end.error());
                auto new_left_source_end = checkedAdd(*left_source_end, delta);
                auto new_right_source_start = checkedAdd(right_clip->source_range.start, delta);
                if (!new_left_source_end || !new_right_source_start) {
                    return std::unexpected(makeError(ErrorCode::Overflow));
                }
                auto available_right_end = rangeEnd(right_clip->available_range);
                auto available_left_end = rangeEnd(left_clip->available_range);
                if (!available_left_end || !available_right_end ||
                    new_left_source_end->value > available_left_end->value ||
                    new_left_source_end->value <= left_clip->source_range.start.value ||
                    new_right_source_start->value < right_clip->available_range.start.value ||
                    new_right_source_start->value >= available_right_end->value) {
                    return std::unexpected(makeError(ErrorCode::SourceOutOfBounds, command.clip_id,
                                                      left_track->id));
                }
                auto [next_left_track, next_left] = findClip(next, command.clip_id);
                auto [next_right_track, next_right] = findClip(next, command.other_clip_id);
                (void)next_left_track;
                (void)next_right_track;
                if (next_left == nullptr || next_right == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound));
                }
                auto new_left_duration = checkedSubtract(*new_left_source_end,
                                                         next_left->source_range.start);
                auto new_right_duration = checkedSubtract(right_clip->source_range.duration, delta);
                if (!new_left_duration || !new_right_duration) {
                    return std::unexpected(makeError(ErrorCode::Overflow, command.clip_id,
                                                      left_track->id));
                }
                next_left->source_range.duration = *new_left_duration;
                next_right->source_range.start = *new_right_source_start;
                next_right->source_range.duration = *new_right_duration;
                next_right->timeline_start = *new_cut;
                if (next_left->source_range.duration.value < command.minimum_duration.value ||
                    next_right->source_range.duration.value < command.minimum_duration.value) {
                    return std::unexpected(makeError(ErrorCode::MinimumDuration, command.clip_id,
                                                      left_track->id));
                }
                markChanged(changed, command.clip_id);
                markChanged(changed, command.other_clip_id);
                affected = TimeRange{left_clip->timeline_start,
                                     Flicks{right_end->value - left_clip->timeline_start.value}};
                break;
            }
            case EditKind::Slide: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                if (auto unlocked = checkUnlocked(project, *original_track, *original_clip); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                auto target_end = clipTimelineEnd(*original_clip);
                if (!target_end) return std::unexpected(target_end.error());
                const Clip* left_clip = nullptr;
                const Clip* right_clip = nullptr;
                for (const auto& candidate : original_track->clips) {
                    if (candidate.id == original_clip->id) continue;
                    auto candidate_end = clipTimelineEnd(candidate);
                    if (!candidate_end) return std::unexpected(candidate_end.error());
                    if (candidate_end->value == original_clip->timeline_start.value) {
                        if (left_clip != nullptr) {
                            return std::unexpected(makeError(ErrorCode::NotAdjacent, command.clip_id,
                                                              original_track->id));
                        }
                        left_clip = &candidate;
                    }
                    if (candidate.timeline_start.value == target_end->value) {
                        if (right_clip != nullptr) {
                            return std::unexpected(makeError(ErrorCode::NotAdjacent, command.clip_id,
                                                              original_track->id));
                        }
                        right_clip = &candidate;
                    }
                }
                if (left_clip == nullptr || right_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::NotAdjacent, command.clip_id,
                                                      original_track->id));
                }
                const Clip& left = *left_clip;
                const Clip& right = *right_clip;
                if (auto unlocked = checkUnlocked(project, *original_track, left); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                if (auto unlocked = checkUnlocked(project, *original_track, right); !unlocked) {
                    return std::unexpected(unlocked.error());
                }
                auto left_source_end = rangeEnd(left.source_range);
                if (!left_source_end) return std::unexpected(left_source_end.error());
                auto new_left_end = checkedAdd(*left_source_end, command.delta);
                auto new_target_start = checkedAdd(original_clip->timeline_start, command.delta);
                auto new_right_source_start = checkedAdd(right.source_range.start, command.delta);
                auto right_available_end = rangeEnd(right.available_range);
                auto left_available_end = rangeEnd(left.available_range);
                if (!new_left_end || !new_target_start || !new_right_source_start ||
                    !right_available_end || !left_available_end) {
                    return std::unexpected(makeError(ErrorCode::Overflow));
                }
                const auto new_left_duration = checkedSubtract(*new_left_end, left.source_range.start);
                const auto new_right_duration = checkedSubtract(right.source_range.duration,
                                                                command.delta);
                if (!new_left_duration || !new_right_duration) {
                    return std::unexpected(makeError(ErrorCode::Overflow, command.clip_id,
                                                      original_track->id));
                }
                if (new_left_duration->value < command.minimum_duration.value ||
                    new_right_duration->value < command.minimum_duration.value) {
                    return std::unexpected(makeError(ErrorCode::MinimumDuration, command.clip_id,
                                                      original_track->id));
                }
                if (new_left_end->value > left_available_end->value ||
                    new_right_source_start->value < right.available_range.start.value ||
                    new_right_source_start->value >= right_available_end->value) {
                    return std::unexpected(makeError(ErrorCode::SourceOutOfBounds, command.clip_id,
                                                      original_track->id));
                }
                Track* track = findTrack(next, original_track->id);
                if (track == nullptr) return std::unexpected(makeError(ErrorCode::TrackNotFound));
                auto next_left = std::find_if(track->clips.begin(), track->clips.end(),
                                              [&left](const Clip& c) { return c.id == left.id; });
                auto next_middle = std::find_if(track->clips.begin(), track->clips.end(),
                                                [id = command.clip_id](const Clip& c) { return c.id == id; });
                auto next_right = std::find_if(track->clips.begin(), track->clips.end(),
                                               [&right](const Clip& c) { return c.id == right.id; });
                if (next_left == track->clips.end() || next_middle == track->clips.end() ||
                    next_right == track->clips.end()) {
                    return std::unexpected(makeError(ErrorCode::InvalidProject));
                }
                auto new_right_start = checkedAdd(*new_target_start, original_clip->source_range.duration);
                if (!new_right_start) return std::unexpected(new_right_start.error());
                next_left->source_range.duration = *new_left_duration;
                next_middle->timeline_start = *new_target_start;
                next_right->timeline_start = *new_right_start;
                next_right->source_range.start = *new_right_source_start;
                next_right->source_range.duration = *new_right_duration;
                markChanged(changed, left.id);
                markChanged(changed, command.clip_id);
                markChanged(changed, right.id);
                auto right_end = clipTimelineEnd(right);
                if (!right_end) return std::unexpected(right_end.error());
                affected = TimeRange{left.timeline_start,
                                     Flicks{right_end->value - left.timeline_start.value}};
                break;
            }
            case EditKind::SetGroupLock: {
                if (original_track == nullptr || original_clip == nullptr) {
                    return std::unexpected(makeError(ErrorCode::ClipNotFound, command.clip_id));
                }
                // Lock metadata remains editable so a user can always unlock a protected group.
                // A zero group ID intentionally means this command controls only the anchor clip.
                const std::uint64_t group_id = original_clip->group_id;
                for (auto& track : next.tracks) {
                    for (auto& clip : track.clips) {
                        if (clip.id != command.clip_id &&
                            (group_id == 0U || clip.group_id != group_id)) {
                            continue;
                        }
                        if (clip.group_locked != *command.group_locked) {
                            clip.group_locked = *command.group_locked;
                            markChanged(changed, clip.id);
                        }
                    }
                }
                break;
            }
        }
        auto finalized = finalizeEdit(std::move(next), std::move(changed), affected);
        if (!finalized) return std::unexpected(finalized.error());
        finalized->project_hash = projectHash(finalized->project);
        return finalized;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Result<EditResult> applyEdits(const Project& project,
                              std::span<const EditCommand> commands) noexcept {
    if (commands.empty()) return std::unexpected(makeError(ErrorCode::InvalidArgument));
    try {
        Project working = project;
        std::vector<ClipId> changed;
        std::optional<TimeRange> affected;
        for (const auto& command : commands) {
            auto result = applyEdit(working, command);
            if (!result) return std::unexpected(result.error());
            working = std::move(result->project);
            for (const auto id : result->changed_clip_ids) markChanged(changed, id);
            if (result->affected_range.has_value()) {
                if (!affected.has_value()) {
                    affected = result->affected_range;
                } else {
                    auto old_end = rangeEnd(*affected);
                    auto next_end = rangeEnd(*result->affected_range);
                    if (!old_end || !next_end) {
                        return std::unexpected(makeError(ErrorCode::Overflow));
                    }
                    const Flicks start{std::min(affected->start.value,
                                                result->affected_range->start.value)};
                    const Flicks end{std::max(old_end->value, next_end->value)};
                    auto duration = checkedSubtract(end, start);
                    if (!duration) return std::unexpected(duration.error());
                    affected = TimeRange{start, *duration};
                }
            }
        }
        auto valid = validateProject(working);
        if (!valid) return std::unexpected(valid.error());
        EditResult result{std::move(working), std::move(changed), affected, 0};
        result.project_hash = projectHash(result.project);
        return result;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Result<SnapResult> snap(const Project& project,
                        Flicks input,
                        Flicks playhead,
                        const SnapConfig& config,
                        ClipId exclude_clip) noexcept {
    if (config.threshold.value < 0 || input.value < 0 || playhead.value < 0 ||
        (config.snap_to_grid && config.grid_interval.value <= 0)) {
        return std::unexpected(makeError(ErrorCode::InvalidArgument));
    }
    if (auto valid = validateProject(project); !valid) {
        return std::unexpected(valid.error());
    }
    SnapResult best{input, false, SnapKind::TimelineStart, 0, Flicks{0}};
    std::uint8_t best_priority = std::numeric_limits<std::uint8_t>::max();
    auto consider = [&](Flicks candidate, SnapKind kind, std::uint64_t id,
                        std::uint8_t priority) noexcept {
        auto delta = checkedSubtract(candidate, input);
        if (!delta) return;
        const auto distance = unsignedMagnitude(delta->value);
        if (distance > static_cast<std::uint64_t>(config.threshold.value)) return;
        const auto best_distance = unsignedMagnitude(best.distance.value);
        if (!best.snapped || distance < best_distance ||
            (distance == best_distance && priority < best_priority) ||
            (distance == best_distance && priority == best_priority && candidate.value < best.position.value)) {
            best = SnapResult{candidate, true, kind, id,
                              Flicks{static_cast<std::int64_t>(distance)}};
            best_priority = priority;
        }
    };
    if (config.snap_to_start) consider(Flicks{0}, SnapKind::TimelineStart, 0, 4);
    if (config.snap_to_playhead) consider(playhead, SnapKind::Playhead, 0, 0);
    if (config.snap_to_clip_edges) {
        for (const auto& track : project.tracks) {
            for (const auto& clip : track.clips) {
                if (clip.id == exclude_clip) continue;
                auto end = clipTimelineEnd(clip);
                if (!end) return std::unexpected(end.error());
                consider(clip.timeline_start, SnapKind::ClipEdge, clip.id.value, 2);
                consider(*end, SnapKind::ClipEdge, clip.id.value, 2);
            }
        }
    }
    if (config.snap_to_markers) {
        for (const auto& marker : project.markers) {
            consider(marker.position, SnapKind::Marker, marker.id, 1);
        }
    }
    if (config.snap_to_grid) {
        const auto interval = config.grid_interval.value;
        const auto lower = (input.value / interval) * interval;
        consider(Flicks{lower}, SnapKind::Grid, 0, 3);
        if (lower <= std::numeric_limits<std::int64_t>::max() - interval) {
            consider(Flicks{lower + interval}, SnapKind::Grid, 0, 3);
        }
    }
    return best;
}

Result<std::vector<ClipId>> visibleClipIds(const Project& project,
                                           TimeRange visible_range,
                                           Flicks overscan) noexcept {
    if (visible_range.duration.value <= 0 || overscan.value < 0) {
        return std::unexpected(makeError(ErrorCode::InvalidRange));
    }
    try {
        if (visible_range.start.value < 0) {
            return std::unexpected(makeError(ErrorCode::InvalidRange));
        }
        if (auto valid = validateProject(project); !valid) {
            return std::unexpected(valid.error());
        }
        auto visible_end = rangeEnd(visible_range);
        if (!visible_end) return std::unexpected(visible_end.error());
        auto overscanned_start = checkedSubtract(visible_range.start, overscan);
        if (!overscanned_start) return std::unexpected(overscanned_start.error());
        Flicks start{std::max<std::int64_t>(0, overscanned_start->value)};
        auto end = checkedAdd(*visible_end, overscan);
        if (!end) return std::unexpected(end.error());
        std::vector<ClipId> visible;
        for (const auto& track : project.tracks) {
            for (const auto& clip : track.clips) {
                auto clip_end = clipTimelineEnd(clip);
                if (!clip_end) return std::unexpected(clip_end.error());
                if (clip.timeline_start.value < end->value && clip_end->value > start.value) {
                    visible.push_back(clip.id);
                }
            }
        }
        return visible;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidProject));
    }
}

TimelineHistory::TimelineHistory(std::size_t maximum_depth) noexcept
    : maximum_depth_(std::max<std::size_t>(1, maximum_depth)) {}

Result<TimelineHistory::HistoryEntry> TimelineHistory::makeHistoryEntry(
    const Project& before, const Project& after) noexcept {
    struct LocatedClip {
        const Clip* clip{};
        TrackId track{};
        std::size_t index{};
    };
    try {
        std::map<std::uint64_t, LocatedClip> before_clips;
        std::map<std::uint64_t, LocatedClip> after_clips;
        const auto collect = [](const Project& project,
                                std::map<std::uint64_t, LocatedClip>& destination) {
            for (const auto& track : project.tracks) {
                for (std::size_t index = 0; index < track.clips.size(); ++index) {
                    const auto& clip = track.clips[index];
                    if (!destination.emplace(clip.id.value, LocatedClip{&clip, track.id, index}).second) {
                        return false;
                    }
                }
            }
            return true;
        };
        if (!collect(before, before_clips) || !collect(after, after_clips)) {
            return std::unexpected(makeError(ErrorCode::DuplicateId));
        }
        if (before_clips.size() > std::numeric_limits<std::size_t>::max() - after_clips.size()) {
            return std::unexpected(makeError(ErrorCode::Overflow));
        }

        std::vector<std::uint64_t> clip_ids;
        clip_ids.reserve(before_clips.size() + after_clips.size());
        for (const auto& [id, location] : before_clips) {
            static_cast<void>(location);
            clip_ids.push_back(id);
        }
        for (const auto& [id, location] : after_clips) {
            static_cast<void>(location);
            clip_ids.push_back(id);
        }
        std::sort(clip_ids.begin(), clip_ids.end());
        clip_ids.erase(std::unique(clip_ids.begin(), clip_ids.end()), clip_ids.end());

        HistoryEntry entry{projectHash(before), projectHash(after), {}};
        entry.clip_deltas.reserve(clip_ids.size());
        for (const auto id : clip_ids) {
            const auto before_it = before_clips.find(id);
            const auto after_it = after_clips.find(id);
            const LocatedClip* before_location = before_it == before_clips.end()
                                                     ? nullptr
                                                     : &before_it->second;
            const LocatedClip* after_location = after_it == after_clips.end()
                                                    ? nullptr
                                                    : &after_it->second;
            if (before_location != nullptr && after_location != nullptr &&
                *before_location->clip == *after_location->clip &&
                before_location->track == after_location->track &&
                before_location->index == after_location->index) {
                continue;
            }

            ClipDelta delta;
            delta.id = ClipId{id};
            if (before_location != nullptr) {
                delta.before = *before_location->clip;
                delta.before_track = before_location->track;
                delta.before_index = before_location->index;
            }
            if (after_location != nullptr) {
                delta.after = *after_location->clip;
                delta.after_track = after_location->track;
                delta.after_index = after_location->index;
            }
            entry.clip_deltas.push_back(std::move(delta));
        }
        if (entry.clip_deltas.empty()) {
            return std::unexpected(makeError(ErrorCode::InvalidEdit));
        }
        return entry;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Status TimelineHistory::applyHistoryEntry(Project& current,
                                          const HistoryEntry& entry,
                                          bool forward) noexcept {
    const auto expected_hash = forward ? entry.before_hash : entry.after_hash;
    const auto target_hash = forward ? entry.after_hash : entry.before_hash;
    if (projectHash(current) != expected_hash) {
        return std::unexpected(makeError(ErrorCode::HistoryConflict));
    }
    try {
        Project next = current;
        std::vector<std::uint64_t> changed_ids;
        changed_ids.reserve(entry.clip_deltas.size());
        for (const auto& delta : entry.clip_deltas) changed_ids.push_back(delta.id.value);
        std::sort(changed_ids.begin(), changed_ids.end());
        changed_ids.erase(std::unique(changed_ids.begin(), changed_ids.end()), changed_ids.end());

        for (auto& track : next.tracks) {
            const auto new_end = std::remove_if(track.clips.begin(), track.clips.end(),
                                                [&changed_ids](const Clip& clip) {
                                                    return std::binary_search(changed_ids.begin(),
                                                                              changed_ids.end(),
                                                                              clip.id.value);
                                                });
            track.clips.erase(new_end, track.clips.end());
        }

        std::vector<const ClipDelta*> insertions;
        insertions.reserve(entry.clip_deltas.size());
        for (const auto& delta : entry.clip_deltas) {
            const auto& target = forward ? delta.after : delta.before;
            if (target.has_value()) insertions.push_back(&delta);
        }
        const auto track_order = [&next](TrackId id) {
            for (std::size_t index = 0; index < next.tracks.size(); ++index) {
                if (next.tracks[index].id == id) return index;
            }
            return std::numeric_limits<std::size_t>::max();
        };
        std::sort(insertions.begin(), insertions.end(), [&](const ClipDelta* lhs,
                                                            const ClipDelta* rhs) {
            const auto lhs_track = forward ? lhs->after_track : lhs->before_track;
            const auto rhs_track = forward ? rhs->after_track : rhs->before_track;
            const auto lhs_order = track_order(lhs_track);
            const auto rhs_order = track_order(rhs_track);
            if (lhs_order != rhs_order) return lhs_order < rhs_order;
            const auto lhs_index = forward ? lhs->after_index : lhs->before_index;
            const auto rhs_index = forward ? rhs->after_index : rhs->before_index;
            if (lhs_index != rhs_index) return lhs_index < rhs_index;
            return lhs->id.value < rhs->id.value;
        });

        for (const auto* delta : insertions) {
            const auto& target = forward ? delta->after : delta->before;
            const auto target_track_id = forward ? delta->after_track : delta->before_track;
            const auto target_index = forward ? delta->after_index : delta->before_index;
            auto* target_track = findTrack(next, target_track_id);
            if (target_track == nullptr || target_index > target_track->clips.size() ||
                target->track_id != target_track_id) {
                return std::unexpected(makeError(ErrorCode::HistoryConflict, delta->id,
                                                 target_track_id));
            }
            const auto position = target_track->clips.begin() +
                                  static_cast<std::vector<Clip>::difference_type>(target_index);
            target_track->clips.insert(position, *target);
        }

        if (auto valid = validateProject(next); !valid) return valid;
        if (projectHash(next) != target_hash) {
            return std::unexpected(makeError(ErrorCode::HistoryConflict));
        }
        current = std::move(next);
        return {};
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Result<EditResult> TimelineHistory::apply(Project& current,
                                          const EditCommand& command) noexcept {
    return applyBatch(current, std::span<const EditCommand>{&command, 1});
}

Result<EditResult> TimelineHistory::applyBatch(
    Project& current, std::span<const EditCommand> commands) noexcept {
    auto result = applyEdits(current, commands);
    if (!result) return std::unexpected(result.error());
    if (result->project_hash == projectHash(current)) return result;
    auto entry = makeHistoryEntry(current, result->project);
    if (!entry) return std::unexpected(entry.error());
    try {
        if (undo_.size() == undo_.max_size()) {
            return std::unexpected(makeError(ErrorCode::LimitExceeded));
        }
        Project committed = result->project;
        undo_.push_back(std::move(*entry));
        if (undo_.size() > maximum_depth_) undo_.erase(undo_.begin());
        current = std::move(committed);
        redo_.clear();
        result->project_hash = projectHash(current);
        return result;
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Status TimelineHistory::undo(Project& current) noexcept {
    if (undo_.empty()) return std::unexpected(makeError(ErrorCode::NoUndo));
    try {
        if (redo_.size() == redo_.max_size()) {
            return std::unexpected(makeError(ErrorCode::LimitExceeded));
        }
        HistoryEntry redo_entry = undo_.back();
        redo_.reserve(redo_.size() + 1U);
        auto restored = applyHistoryEntry(current, undo_.back(), false);
        if (!restored) return restored;
        redo_.push_back(std::move(redo_entry));
        undo_.pop_back();
        return {};
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

Status TimelineHistory::redo(Project& current) noexcept {
    if (redo_.empty()) return std::unexpected(makeError(ErrorCode::NoRedo));
    try {
        if (undo_.size() == undo_.max_size()) {
            return std::unexpected(makeError(ErrorCode::LimitExceeded));
        }
        HistoryEntry undo_entry = redo_.back();
        undo_.reserve(undo_.size() + 1U);
        auto applied = applyHistoryEntry(current, redo_.back(), true);
        if (!applied) return applied;
        undo_.push_back(std::move(undo_entry));
        if (undo_.size() > maximum_depth_) undo_.erase(undo_.begin());
        redo_.pop_back();
        return {};
    } catch (const std::bad_alloc&) {
        return std::unexpected(makeError(ErrorCode::AllocationFailure));
    } catch (...) {
        return std::unexpected(makeError(ErrorCode::InvalidEdit));
    }
}

bool TimelineHistory::canUndo() const noexcept { return !undo_.empty(); }
bool TimelineHistory::canRedo() const noexcept { return !redo_.empty(); }
std::size_t TimelineHistory::undoDepth() const noexcept { return undo_.size(); }
std::size_t TimelineHistory::redoDepth() const noexcept { return redo_.size(); }
void TimelineHistory::clear() noexcept { undo_.clear(); redo_.clear(); }

}  // namespace timeline_core_v2
