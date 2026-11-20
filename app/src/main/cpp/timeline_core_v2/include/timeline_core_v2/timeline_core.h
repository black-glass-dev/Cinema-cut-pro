#pragma once

#include <compare>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace timeline_core_v2 {

inline constexpr std::int64_t kFlicksPerSecond = 705'600'000;
inline constexpr std::uint64_t kFNVOffsetBasis = 14'695'981'039'346'656'037ULL;
inline constexpr std::uint64_t kFNVPrime = 1'099'511'628'211ULL;

struct Flicks {
    std::int64_t value{};
    auto operator<=>(const Flicks&) const = default;
};

struct FrameRate {
    std::int64_t numerator{24};
    std::int64_t denominator{1};
    auto operator<=>(const FrameRate&) const = default;
};

enum class ErrorCode : std::uint32_t {
    InvalidArgument = 1,
    Overflow = 2,
    InvalidRange = 3,
    InvalidProject = 4,
    DuplicateId = 5,
    ClipNotFound = 6,
    TrackNotFound = 7,
    LockedTrack = 8,
    LockedClip = 9,
    LockedGroup = 10,
    SourceOutOfBounds = 11,
    MinimumDuration = 12,
    Overlap = 13,
    NotAdjacent = 14,
    InvalidEdit = 15,
    NoUndo = 16,
    NoRedo = 17,
    AllocationFailure = 18,
    UnsupportedVersion = 19,
    CorruptData = 20,
    LimitExceeded = 21,
    HistoryConflict = 22,
};

struct Error {
    ErrorCode code{ErrorCode::InvalidArgument};
    std::uint64_t clip_id{};
    std::uint64_t track_id{};
    auto operator<=>(const Error&) const = default;
};

template <class T>
using Result = std::expected<T, Error>;

using Status = Result<void>;

[[nodiscard]] std::string_view errorName(ErrorCode code) noexcept;
[[nodiscard]] Result<FrameRate> makeFrameRate(std::int64_t numerator,
                                             std::int64_t denominator) noexcept;
[[nodiscard]] Result<Flicks> frameToFlicks(std::int64_t frame,
                                           FrameRate rate) noexcept;
[[nodiscard]] Result<std::int64_t> flicksToFrame(Flicks time,
                                                 FrameRate rate) noexcept;
[[nodiscard]] Result<Flicks> checkedAdd(Flicks lhs, Flicks rhs) noexcept;
[[nodiscard]] Result<Flicks> checkedSubtract(Flicks lhs, Flicks rhs) noexcept;

struct TimeRange {
    Flicks start{};
    Flicks duration{};
    auto operator<=>(const TimeRange&) const = default;
};

[[nodiscard]] Result<Flicks> rangeEnd(const TimeRange& range) noexcept;

struct TrackId {
    std::uint64_t value{};
    auto operator<=>(const TrackId&) const = default;
};

struct ClipId {
    std::uint64_t value{};
    auto operator<=>(const ClipId&) const = default;
};

struct Marker {
    std::uint64_t id{};
    Flicks position{};
    auto operator<=>(const Marker&) const = default;
};

struct Clip {
    ClipId id{};
    TrackId track_id{};
    TimeRange source_range{};
    Flicks timeline_start{};
    TimeRange available_range{};
    bool locked{};
    std::uint64_t group_id{};
    bool group_locked{};
    bool enabled{true};
    auto operator<=>(const Clip&) const = default;
};

struct Track {
    TrackId id{};
    bool locked{};
    bool allows_overlap{};
    std::vector<Clip> clips;
};

struct Project {
    std::uint64_t id{};
    FrameRate frame_rate{};
    std::vector<Track> tracks;
    std::vector<Marker> markers;
};

inline constexpr std::uint32_t kProjectSchemaVersion = 1;
[[nodiscard]] Result<std::vector<std::uint8_t>> serializeProject(const Project& project) noexcept;
[[nodiscard]] Result<Project> deserializeProject(std::span<const std::uint8_t> bytes) noexcept;

[[nodiscard]] Result<Flicks> clipTimelineEnd(const Clip& clip) noexcept;
[[nodiscard]] Result<Flicks> projectDuration(const Project& project) noexcept;
[[nodiscard]] Status validateProject(const Project& project) noexcept;
[[nodiscard]] std::uint64_t projectHash(const Project& project) noexcept;

enum class TrimEdge : std::uint8_t { Start, End };

struct TrimRequest {
    TrimEdge edge{TrimEdge::Start};
    Flicks new_timeline_edge{};
    Flicks minimum_duration{1};
};

struct TrimResult {
    Clip clip;
    Flicks duration_delta{};
    Flicks timeline_start_delta{};
};

// The only trim/source-edge calculation. Ghost/preview and committed edits use applyEdit,
// which invokes this function for the same request and returns the same immutable result.
[[nodiscard]] Result<TrimResult> computeTrim(const Clip& clip,
                                             const TrimRequest& request) noexcept;

enum class RippleScope : std::uint8_t { None, SameTrack, AllTracks };
enum class EditKind : std::uint8_t {
    TrimStart,
    TrimEnd,
    Move,
    Insert,
    Delete,
    Split,
    Slip,
    Slide,
    Roll,
    SetGroupLock,
};

struct EditCommand {
    EditKind kind{EditKind::Move};
    ClipId clip_id{};
    ClipId other_clip_id{};  // Right clip for Roll; new clip id for Split.
    TrackId target_track_id{};
    Flicks target{};         // Timeline edge/start/cut/insert position.
    Flicks delta{};          // Slip/Slide offset.
    Flicks minimum_duration{1};
    RippleScope ripple{RippleScope::None};
    std::vector<ClipId> clip_ids;  // Delete set, or same-delta move set; clip_id is used when empty.
    std::optional<Clip> inserted_clip;
    // Required only for SetGroupLock; changing lock metadata is itself a native-history edit.
    std::optional<bool> group_locked;
};

struct EditResult {
    Project project;
    std::vector<ClipId> changed_clip_ids;
    std::optional<TimeRange> affected_range;
    std::uint64_t project_hash{};
};

[[nodiscard]] Result<EditResult> applyEdit(const Project& project,
                                           const EditCommand& command) noexcept;
// A batch is atomic: each command is evaluated against a private working copy, and no partial
// project/history state is returned when any command rejects.
[[nodiscard]] Result<EditResult> applyEdits(const Project& project,
                                            std::span<const EditCommand> commands) noexcept;

struct SnapConfig {
    Flicks threshold{0};
    bool snap_to_start{true};
    bool snap_to_playhead{true};
    bool snap_to_clip_edges{true};
    bool snap_to_markers{true};
    bool snap_to_grid{};
    Flicks grid_interval{};
};

enum class SnapKind : std::uint8_t { Playhead, Marker, ClipEdge, Grid, TimelineStart };

struct SnapResult {
    Flicks position{};
    bool snapped{};
    SnapKind kind{SnapKind::TimelineStart};
    std::uint64_t target_id{};
    Flicks distance{};
    auto operator<=>(const SnapResult&) const = default;
};

[[nodiscard]] Result<SnapResult> snap(const Project& project,
                                      Flicks input,
                                      Flicks playhead,
                                      const SnapConfig& config,
                                      ClipId exclude_clip = {}) noexcept;

[[nodiscard]] Result<std::vector<ClipId>> visibleClipIds(const Project& project,
                                                         TimeRange visible_range,
                                                         Flicks overscan) noexcept;

// Thread-confined history. JNI/session owners must serialize access; the core itself is pure.
class TimelineHistory {
public:
    explicit TimelineHistory(std::size_t maximum_depth = 50) noexcept;

    [[nodiscard]] Result<EditResult> apply(Project& current,
                                           const EditCommand& command) noexcept;
    [[nodiscard]] Result<EditResult> applyBatch(Project& current,
                                                std::span<const EditCommand> commands) noexcept;
    [[nodiscard]] Status undo(Project& current) noexcept;
    [[nodiscard]] Status redo(Project& current) noexcept;
    [[nodiscard]] bool canUndo() const noexcept;
    [[nodiscard]] bool canRedo() const noexcept;
    [[nodiscard]] std::size_t undoDepth() const noexcept;
    [[nodiscard]] std::size_t redoDepth() const noexcept;
    void clear() noexcept;

private:
    struct ClipDelta {
        ClipId id{};
        std::optional<Clip> before;
        TrackId before_track{};
        std::size_t before_index{};
        std::optional<Clip> after;
        TrackId after_track{};
        std::size_t after_index{};
    };

    struct HistoryEntry {
        std::uint64_t before_hash{};
        std::uint64_t after_hash{};
        std::vector<ClipDelta> clip_deltas;
    };

    [[nodiscard]] static Result<HistoryEntry> makeHistoryEntry(const Project& before,
                                                               const Project& after) noexcept;
    [[nodiscard]] static Status applyHistoryEntry(Project& current,
                                                  const HistoryEntry& entry,
                                                  bool forward) noexcept;

    std::size_t maximum_depth_;
    std::vector<HistoryEntry> undo_;
    std::vector<HistoryEntry> redo_;
};

}  // namespace timeline_core_v2
