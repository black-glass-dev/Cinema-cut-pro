#include "timeline_core_v2/timeline_core.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string_view>
#include <utility>
#include <vector>

using namespace timeline_core_v2;

namespace {
std::uint64_t assertions = 0;
std::uint64_t failures = 0;

#define CHECK_TRUE(...)                                                                    \
    do {                                                                                   \
        ++assertions;                                                                      \
        if (!(__VA_ARGS__)) {                                                              \
            ++failures;                                                                    \
            std::cerr << "FAIL " << __FILE__ << ':' << __LINE__ << ": " #__VA_ARGS__ "\n"; \
        }                                                                                  \
    } while (false)

Flicks f(std::int64_t frame, FrameRate rate = {24, 1}) {
    const auto result = frameToFlicks(frame, rate);
    if (!result) std::abort();
    return *result;
}

Clip makeClip(std::uint64_t id,
              std::uint64_t track,
              std::int64_t timeline_start,
              std::int64_t source_in,
              std::int64_t duration,
              std::int64_t available_start = 0,
              std::int64_t available_duration = 2'000) {
    const FrameRate rate{24, 1};
    return Clip{ClipId{id}, TrackId{track},
                TimeRange{f(source_in, rate), f(duration, rate)},
                f(timeline_start, rate),
                TimeRange{f(available_start, rate), f(available_duration, rate)},
                false, 0, false, true};
}

Project makeProject() {
    const FrameRate rate{24, 1};
    Track track{TrackId{1}, false, false,
                {makeClip(11, 1, 0, 0, 100),
                 makeClip(12, 1, 100, 200, 100),
                 makeClip(13, 1, 200, 400, 100)}};
    return Project{1, rate, {track}, {Marker{901, f(150, rate)}}};
}

std::pair<const Track*, const Clip*> findClipById(const Project& project, ClipId id) {
    for (const auto& track : project.tracks) {
        const auto clip = std::find_if(track.clips.begin(), track.clips.end(),
                                       [id](const Clip& candidate) { return candidate.id == id; });
        if (clip != track.clips.end()) return {&track, &*clip};
    }
    return {nullptr, nullptr};
}

std::uint64_t nextRandom(std::uint64_t& state) {
    state ^= state >> 12U;
    state ^= state << 25U;
    state ^= state >> 27U;
    return state * 2'685'821'657'736'338'717ULL;
}

void testStableErrorCodeNumbersAndNames() {
    static_assert(static_cast<std::uint32_t>(ErrorCode::InvalidArgument) == 1U);
    static_assert(static_cast<std::uint32_t>(ErrorCode::AllocationFailure) == 18U);
    static_assert(static_cast<std::uint32_t>(ErrorCode::UnsupportedVersion) == 19U);
    static_assert(static_cast<std::uint32_t>(ErrorCode::CorruptData) == 20U);
    static_assert(static_cast<std::uint32_t>(ErrorCode::LimitExceeded) == 21U);
    static_assert(static_cast<std::uint32_t>(ErrorCode::HistoryConflict) == 22U);
    CHECK_TRUE(errorName(ErrorCode::InvalidArgument) == "TL_E_INVALID_ARGUMENT");
    CHECK_TRUE(errorName(ErrorCode::UnsupportedVersion) == "TL_E_UNSUPPORTED_VERSION");
    CHECK_TRUE(errorName(ErrorCode::CorruptData) == "TL_E_CORRUPT_DATA");
    CHECK_TRUE(errorName(ErrorCode::LimitExceeded) == "TL_E_LIMIT_EXCEEDED");
    CHECK_TRUE(errorName(ErrorCode::HistoryConflict) == "TL_E_HISTORY_CONFLICT");
}

void testFrameRateRoundTrips() {
    constexpr std::int64_t frames[] = {0, 1, 2, 24, 1'000, 1'000'000};
    constexpr FrameRate rates[] = {{24, 1}, {25, 1}, {30, 1}, {24000, 1001},
                                   {30000, 1001}, {60000, 1001}, {120, 1}};
    for (const auto rate : rates) {
        for (const auto frame : frames) {
            auto timestamp = frameToFlicks(frame, rate);
            CHECK_TRUE(timestamp.has_value());
            if (!timestamp) continue;
            auto roundtrip = flicksToFrame(*timestamp, rate);
            CHECK_TRUE(roundtrip.has_value());
            if (roundtrip) CHECK_TRUE(*roundtrip == frame);
        }
    }
    CHECK_TRUE(!makeFrameRate(0, 1));
    CHECK_TRUE(!makeFrameRate(24, 0));
}

void testFrameArithmeticRejectsOverflowAndRoundsTiesToEven() {
    constexpr FrameRate half_flick_rate{1'411'200'000, 1};
    CHECK_TRUE(frameToFlicks(1, half_flick_rate) == Result<Flicks>{Flicks{0}});
    CHECK_TRUE(frameToFlicks(3, half_flick_rate) == Result<Flicks>{Flicks{2}});
    CHECK_TRUE(frameToFlicks(-3, half_flick_rate) == Result<Flicks>{Flicks{-2}});
    const auto add_overflow = checkedAdd(Flicks{std::numeric_limits<std::int64_t>::max()}, Flicks{1});
    CHECK_TRUE(!add_overflow && add_overflow.error().code == ErrorCode::Overflow);
    const auto subtract_overflow = checkedSubtract(Flicks{0},
                                                   Flicks{std::numeric_limits<std::int64_t>::min()});
    CHECK_TRUE(!subtract_overflow && subtract_overflow.error().code == ErrorCode::Overflow);
    CHECK_TRUE(checkedSubtract(Flicks{-1},
                               Flicks{std::numeric_limits<std::int64_t>::min()}) ==
               Result<Flicks>{Flicks{std::numeric_limits<std::int64_t>::max()}});
}

void testF1_TrimOrientationAndGhostCommitShareComputeTrim() {
    const Project project = makeProject();
    const Clip clip = project.tracks[0].clips[0];
    const auto trim_start = computeTrim(clip, TrimRequest{TrimEdge::Start, f(10), f(1)});
    CHECK_TRUE(trim_start.has_value());
    if (!trim_start) return;
    CHECK_TRUE(trim_start->clip.source_range.start == f(10));
    CHECK_TRUE(trim_start->clip.source_range.duration == f(90));
    CHECK_TRUE(trim_start->clip.timeline_start == f(10));
    CHECK_TRUE(clipTimelineEnd(trim_start->clip) == clipTimelineEnd(clip));

    EditCommand command;
    command.kind = EditKind::TrimStart;
    command.clip_id = clip.id;
    command.target = f(10);
    command.minimum_duration = f(1);
    const auto committed = applyEdit(project, command);
    CHECK_TRUE(committed.has_value());
    if (committed) {
        const auto [track, committed_clip] = findClipById(committed->project, clip.id);
        CHECK_TRUE(track != nullptr);
        CHECK_TRUE(committed_clip != nullptr && committed_clip->source_range == trim_start->clip.source_range);
        CHECK_TRUE(committed_clip != nullptr && committed_clip->timeline_start == trim_start->clip.timeline_start);
    }

    const auto trim_end = computeTrim(clip, TrimRequest{TrimEdge::End, f(110), f(1)});
    CHECK_TRUE(trim_end.has_value());
    if (trim_end) {
        CHECK_TRUE(trim_end->clip.source_range.start == clip.source_range.start);
        CHECK_TRUE(trim_end->clip.source_range.duration == f(110));
        CHECK_TRUE(clipTimelineEnd(trim_end->clip) == Result<Flicks>{f(110)});
    }

    const auto reject_short = computeTrim(clip, TrimRequest{TrimEdge::Start, f(100), f(2)});
    CHECK_TRUE(!reject_short);
    CHECK_TRUE(reject_short.error().code == ErrorCode::MinimumDuration);
}

void testINV04_RejectedMoveIsAtomicAndLockedClipCannotMove() {
    Project project = makeProject();
    const auto before = projectHash(project);
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(50);
    const auto rejected = applyEdit(project, move);
    CHECK_TRUE(!rejected);
    CHECK_TRUE(rejected.error().code == ErrorCode::Overlap);
    CHECK_TRUE(projectHash(project) == before);

    TimelineHistory history;
    Project history_project = project;
    const auto rejected_history = history.apply(history_project, move);
    CHECK_TRUE(!rejected_history);
    CHECK_TRUE(projectHash(history_project) == before);
    CHECK_TRUE(!history.canUndo());
    CHECK_TRUE(!history.canRedo());

    project.tracks[0].clips[0].locked = true;
    const auto locked_hash = projectHash(project);
    move.target = f(400);
    const auto locked_move = applyEdit(project, move);
    CHECK_TRUE(!locked_move);
    CHECK_TRUE(locked_move.error().code == ErrorCode::LockedClip);
    CHECK_TRUE(projectHash(project) == locked_hash);
}

void testINV04_MultiClipMoveIsAtomicAndPreservesRelativeOffsets() {
    Project project = makeProject();
    project.tracks.push_back(Track{TrackId{2}, false, false, {}});
    EditCommand move_group;
    move_group.kind = EditKind::Move;
    move_group.clip_id = ClipId{11};
    move_group.target_track_id = TrackId{2};
    move_group.target = f(300);
    move_group.clip_ids = {ClipId{11}, ClipId{13}};

    const auto moved = applyEdit(project, move_group);
    CHECK_TRUE(moved.has_value());
    if (moved) {
        const auto [primary_track, primary] = findClipById(moved->project, ClipId{11});
        const auto [secondary_track, secondary] = findClipById(moved->project, ClipId{13});
        CHECK_TRUE(primary_track != nullptr && primary_track->id == TrackId{2});
        CHECK_TRUE(primary != nullptr && primary->timeline_start == f(300));
        CHECK_TRUE(secondary_track != nullptr && secondary_track->id == TrackId{1});
        CHECK_TRUE(secondary != nullptr && secondary->timeline_start == f(500));
        CHECK_TRUE(validateProject(moved->project).has_value());
    }

    project.tracks[0].clips[2].locked = true;
    const auto before = projectHash(project);
    move_group.target = f(400);
    const auto rejected = applyEdit(project, move_group);
    CHECK_TRUE(!rejected && rejected.error().code == ErrorCode::LockedClip);
    CHECK_TRUE(projectHash(project) == before);
}

void testSlipPreservesTimelineAndClampsToAvailableSourceRange() {
    const Project project = makeProject();
    EditCommand slip;
    slip.kind = EditKind::Slip;
    slip.clip_id = ClipId{12};
    slip.delta = f(25);
    const auto forward = applyEdit(project, slip);
    CHECK_TRUE(forward.has_value());
    if (forward) {
        const auto [track, clip] = findClipById(forward->project, ClipId{12});
        CHECK_TRUE(track != nullptr);
        CHECK_TRUE(clip != nullptr && clip->source_range.start == f(225));
        CHECK_TRUE(clip != nullptr && clip->source_range.duration == f(100));
        CHECK_TRUE(clip != nullptr && clip->timeline_start == f(100));
    }

    slip.delta = f(5'000);
    const auto right_clamped = applyEdit(project, slip);
    CHECK_TRUE(right_clamped.has_value());
    if (right_clamped) {
        const auto [track, clip] = findClipById(right_clamped->project, ClipId{12});
        CHECK_TRUE(track != nullptr);
        CHECK_TRUE(clip != nullptr && clip->source_range.start == f(1'900));
        CHECK_TRUE(clip != nullptr && clip->source_range.duration == f(100));
        CHECK_TRUE(clip != nullptr && clip->timeline_start == f(100));
    }

    slip.delta = f(-5'000);
    const auto left_clamped = applyEdit(project, slip);
    CHECK_TRUE(left_clamped.has_value());
    if (left_clamped) {
        const auto [track, clip] = findClipById(left_clamped->project, ClipId{12});
        CHECK_TRUE(track != nullptr);
        CHECK_TRUE(clip != nullptr && clip->source_range.start == f(0));
        CHECK_TRUE(clip != nullptr && clip->timeline_start == f(100));
    }
}

void testINV04_BatchedEditsAreAtomicAndHistoryHasSingleEntry() {
    const Project project = makeProject();
    const auto before_hash = projectHash(project);
    EditCommand move_tail;
    move_tail.kind = EditKind::Move;
    move_tail.clip_id = ClipId{13};
    move_tail.target_track_id = TrackId{1};
    move_tail.target = f(250);
    EditCommand rejected_move;
    rejected_move.kind = EditKind::Move;
    rejected_move.clip_id = ClipId{11};
    rejected_move.target_track_id = TrackId{1};
    rejected_move.target = f(50);
    const std::array rejected_batch{move_tail, rejected_move};
    const auto rejected = applyEdits(project, rejected_batch);
    CHECK_TRUE(!rejected && rejected.error().code == ErrorCode::Overlap);
    CHECK_TRUE(projectHash(project) == before_hash);

    TimelineHistory history;
    Project history_project = project;
    const auto history_rejected = history.applyBatch(history_project, rejected_batch);
    CHECK_TRUE(!history_rejected && history_rejected.error().code == ErrorCode::Overlap);
    CHECK_TRUE(projectHash(history_project) == before_hash);
    CHECK_TRUE(!history.canUndo() && !history.canRedo());

    move_tail.target = f(300);
    rejected_move.target = f(400);
    const std::array accepted_batch{move_tail, rejected_move};
    const auto applied = history.applyBatch(history_project, accepted_batch);
    CHECK_TRUE(applied.has_value());
    if (!applied) return;
    const auto after_hash = projectHash(history_project);
    CHECK_TRUE(after_hash == applied->project_hash);
    CHECK_TRUE(history.undoDepth() == 1U);
    CHECK_TRUE(history.undo(history_project).has_value());
    CHECK_TRUE(projectHash(history_project) == before_hash);
    CHECK_TRUE(history.redo(history_project).has_value());
    CHECK_TRUE(projectHash(history_project) == after_hash);
}

void testRippleTrimsShiftFollowingClipsAndPreserveInvariants() {
    const Project project = makeProject();
    EditCommand trim_in;
    trim_in.kind = EditKind::TrimStart;
    trim_in.clip_id = ClipId{12};
    trim_in.target = f(110);
    trim_in.ripple = RippleScope::SameTrack;
    const auto start_result = applyEdit(project, trim_in);
    CHECK_TRUE(start_result.has_value());
    if (start_result) {
        const auto [selected_track, selected] = findClipById(start_result->project, ClipId{12});
        const auto [follower_track, follower] = findClipById(start_result->project, ClipId{13});
        CHECK_TRUE(selected_track != nullptr && selected != nullptr);
        CHECK_TRUE(selected != nullptr && selected->timeline_start == f(100));
        CHECK_TRUE(selected != nullptr && selected->source_range.start == f(210));
        CHECK_TRUE(follower_track != nullptr && follower != nullptr);
        CHECK_TRUE(follower != nullptr && follower->timeline_start == f(190));
        CHECK_TRUE(validateProject(start_result->project).has_value());
    }

    EditCommand trim_out;
    trim_out.kind = EditKind::TrimEnd;
    trim_out.clip_id = ClipId{12};
    trim_out.target = f(210);
    trim_out.ripple = RippleScope::SameTrack;
    const auto end_result = applyEdit(project, trim_out);
    CHECK_TRUE(end_result.has_value());
    if (end_result) {
        const auto [follower_track, follower] = findClipById(end_result->project, ClipId{13});
        CHECK_TRUE(follower_track != nullptr && follower != nullptr);
        CHECK_TRUE(follower != nullptr && follower->timeline_start == f(210));
        CHECK_TRUE(validateProject(end_result->project).has_value());
    }
}

void testSplitDeleteRollSlideAndInsert() {
    const Project project = makeProject();
    EditCommand split;
    split.kind = EditKind::Split;
    split.clip_id = ClipId{11};
    split.other_clip_id = ClipId{99};
    split.target = f(40);
    const auto split_result = applyEdit(project, split);
    CHECK_TRUE(split_result.has_value());
    if (split_result) {
        const auto [left_track, left] = findClipById(split_result->project, ClipId{11});
        const auto [right_track, right] = findClipById(split_result->project, ClipId{99});
        CHECK_TRUE(left_track != nullptr && left != nullptr);
        CHECK_TRUE(right_track != nullptr && right != nullptr);
        CHECK_TRUE(left != nullptr && left->source_range.duration == f(40));
        CHECK_TRUE(right != nullptr && right->timeline_start == f(40));
        CHECK_TRUE(validateProject(split_result->project).has_value());
    }

    EditCommand delete_clip;
    delete_clip.kind = EditKind::Delete;
    delete_clip.clip_id = ClipId{12};
    delete_clip.ripple = RippleScope::SameTrack;
    const auto delete_result = applyEdit(project, delete_clip);
    CHECK_TRUE(delete_result.has_value());
    if (delete_result) {
        const auto& clips = delete_result->project.tracks[0].clips;
        CHECK_TRUE(clips.size() == 2U);
        const auto [follower_track, follower] = findClipById(delete_result->project, ClipId{13});
        CHECK_TRUE(follower_track != nullptr && follower != nullptr);
        CHECK_TRUE(follower != nullptr && follower->timeline_start == f(100));
    }

    EditCommand roll;
    roll.kind = EditKind::Roll;
    roll.clip_id = ClipId{11};
    roll.other_clip_id = ClipId{12};
    roll.delta = f(10);
    const auto roll_result = applyEdit(project, roll);
    CHECK_TRUE(roll_result.has_value());
    if (roll_result) {
        const auto [left_track, left] = findClipById(roll_result->project, ClipId{11});
        const auto [right_track, right] = findClipById(roll_result->project, ClipId{12});
        CHECK_TRUE(left_track != nullptr && right_track != nullptr);
        CHECK_TRUE(left != nullptr && left->source_range.duration == f(110));
        CHECK_TRUE(right != nullptr && right->timeline_start == f(110));
        CHECK_TRUE(right != nullptr && right->source_range.start == f(210));
        CHECK_TRUE(projectDuration(roll_result->project) == projectDuration(project));
        CHECK_TRUE(validateProject(roll_result->project).has_value());
    }

    EditCommand slide;
    slide.kind = EditKind::Slide;
    slide.clip_id = ClipId{12};
    slide.delta = f(10);
    const auto slide_result = applyEdit(project, slide);
    CHECK_TRUE(slide_result.has_value());
    if (slide_result) {
        const auto [left_track, left] = findClipById(slide_result->project, ClipId{11});
        const auto [middle_track, middle] = findClipById(slide_result->project, ClipId{12});
        const auto [right_track, right] = findClipById(slide_result->project, ClipId{13});
        CHECK_TRUE(left_track != nullptr && middle_track != nullptr && right_track != nullptr);
        CHECK_TRUE(left != nullptr && left->source_range.duration == f(110));
        CHECK_TRUE(middle != nullptr && middle->timeline_start == f(110));
        CHECK_TRUE(right != nullptr && right->timeline_start == f(210));
        CHECK_TRUE(projectDuration(slide_result->project) == projectDuration(project));
        CHECK_TRUE(validateProject(slide_result->project).has_value());
    }

    const Clip inserted = makeClip(55, 1, 100, 600, 30);
    EditCommand insert;
    insert.kind = EditKind::Insert;
    insert.inserted_clip = inserted;
    insert.ripple = RippleScope::SameTrack;
    const auto insert_result = applyEdit(project, insert);
    CHECK_TRUE(insert_result.has_value());
    if (insert_result) {
        const auto& clips = insert_result->project.tracks[0].clips;
        CHECK_TRUE(clips.size() == 4U);
        const auto [shifted_track, shifted] = findClipById(insert_result->project, ClipId{12});
        CHECK_TRUE(shifted_track != nullptr && shifted != nullptr);
        CHECK_TRUE(shifted != nullptr && shifted->timeline_start == f(130));
        CHECK_TRUE(validateProject(insert_result->project).has_value());
    }
}

void testHistoryUndoRedoAndCompaction() {
    Project project = makeProject();
    const auto before = projectHash(project);
    TimelineHistory history(2);
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(300);
    const auto applied = history.apply(project, move);
    CHECK_TRUE(applied.has_value());
    CHECK_TRUE(history.canUndo());
    const auto after = projectHash(project);
    CHECK_TRUE(after != before);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == before);
    CHECK_TRUE(history.redo(project).has_value());
    CHECK_TRUE(projectHash(project) == after);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == before);

    move.target = f(300);
    CHECK_TRUE(history.apply(project, move).has_value());
    move.target = f(400);
    CHECK_TRUE(history.apply(project, move).has_value());
    move.target = f(500);
    CHECK_TRUE(history.apply(project, move).has_value());
    CHECK_TRUE(history.undoDepth() == 2U);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(!history.canUndo());
    CHECK_TRUE(history.canRedo());
}

void testINV06_07_DeltaHistoryRoundTripsStructuralEditsAndClipOrder() {
    const auto exercise = [](Project project, const EditCommand& command) {
        TimelineHistory history;
        const auto before_hash = projectHash(project);
        const auto applied = history.apply(project, command);
        CHECK_TRUE(applied.has_value());
        if (!applied) return;
        const auto after_hash = projectHash(project);
        CHECK_TRUE(after_hash == applied->project_hash);
        CHECK_TRUE(history.undo(project).has_value());
        CHECK_TRUE(projectHash(project) == before_hash);
        CHECK_TRUE(history.redo(project).has_value());
        CHECK_TRUE(projectHash(project) == after_hash);
    };

    EditCommand deletion;
    deletion.kind = EditKind::Delete;
    deletion.clip_id = ClipId{12};
    deletion.ripple = RippleScope::SameTrack;
    exercise(makeProject(), deletion);

    EditCommand split;
    split.kind = EditKind::Split;
    split.clip_id = ClipId{11};
    split.other_clip_id = ClipId{99};
    split.target = f(40);
    exercise(makeProject(), split);

    EditCommand insertion;
    insertion.kind = EditKind::Insert;
    insertion.inserted_clip = makeClip(55, 1, 100, 600, 30);
    insertion.ripple = RippleScope::SameTrack;
    exercise(makeProject(), insertion);

    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(400);
    exercise(makeProject(), move);
}

void testINV06_UndoRejectsExternalMutationWithoutChangingHistoryOrProject() {
    Project project = makeProject();
    TimelineHistory history;
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{13};
    move.target_track_id = TrackId{1};
    move.target = f(400);
    CHECK_TRUE(history.apply(project, move).has_value());
    auto& clips = project.tracks[0].clips;
    const auto changed = std::find_if(clips.begin(), clips.end(), [](const Clip& clip) {
        return clip.id == ClipId{13};
    });
    CHECK_TRUE(changed != clips.end());
    if (changed == clips.end()) return;
    changed->timeline_start = f(450);
    const auto external_hash = projectHash(project);

    const auto undo = history.undo(project);
    CHECK_TRUE(!undo && undo.error().code == ErrorCode::HistoryConflict);
    CHECK_TRUE(projectHash(project) == external_hash);
    CHECK_TRUE(history.undoDepth() == 1U && !history.canRedo());
}

void testINV04_NoOpDoesNotCreateOrClearHistory() {
    Project project = makeProject();
    TimelineHistory history;
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(0);
    const auto before = projectHash(project);
    const auto no_op = history.apply(project, move);
    CHECK_TRUE(no_op.has_value());
    CHECK_TRUE(projectHash(project) == before);
    CHECK_TRUE(!history.canUndo() && !history.canRedo());

    move.target = f(300);
    CHECK_TRUE(history.apply(project, move).has_value());
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(history.canRedo());
    move.target = f(0);
    const auto second_no_op = history.apply(project, move);
    CHECK_TRUE(second_no_op.has_value());
    CHECK_TRUE(projectHash(project) == before);
    CHECK_TRUE(history.canRedo());
}

void testSnapAndVisibleClipQuery() {
    const Project project = makeProject();
    SnapConfig config;
    config.threshold = f(3);
    const auto playhead_snap = snap(project, f(149), f(150), config);
    CHECK_TRUE(playhead_snap.has_value());
    CHECK_TRUE(playhead_snap->snapped && playhead_snap->kind == SnapKind::Playhead);
    const auto edge_snap = snap(project, f(198), f(500), config);
    CHECK_TRUE(edge_snap.has_value());
    CHECK_TRUE(edge_snap->snapped && edge_snap->kind == SnapKind::ClipEdge);

    const auto visible = visibleClipIds(project, TimeRange{f(95), f(15)}, f(0));
    CHECK_TRUE(visible.has_value());
    if (visible) {
        CHECK_TRUE(std::find(visible->begin(), visible->end(), ClipId{11}) != visible->end());
        CHECK_TRUE(std::find(visible->begin(), visible->end(), ClipId{12}) != visible->end());
        CHECK_TRUE(std::find(visible->begin(), visible->end(), ClipId{13}) == visible->end());
    }
}

void testINV01_DurationIsDerivedFromSourceRange() {
    Clip clip = makeClip(11, 1, 20, 40, 80);
    clip.source_range.duration = f(75);
    CHECK_TRUE(clip.source_range.duration == f(75));
    CHECK_TRUE(clipTimelineEnd(clip) == Result<Flicks>{f(95)});
}

void testINV02_ClipEndAndProjectDurationUseIntegerFlicks() {
    Project project = makeProject();
    CHECK_TRUE(clipTimelineEnd(project.tracks[0].clips[0]) == Result<Flicks>{f(100)});
    CHECK_TRUE(projectDuration(project) == Result<Flicks>{f(300)});
    project.tracks[0].clips[2].timeline_start = f(500);
    CHECK_TRUE(projectDuration(project) == Result<Flicks>{f(600)});
}

void testINV03_OverlapsRejectUnlessTrackAllowsThem() {
    Project project = makeProject();
    project.tracks[0].clips[1].timeline_start = f(50);
    const auto overlap = validateProject(project);
    CHECK_TRUE(!overlap && overlap.error().code == ErrorCode::Overlap);
    project.tracks[0].allows_overlap = true;
    CHECK_TRUE(validateProject(project).has_value());
}

void testINV05_TrackClipGroupAndRippleLocksRejectAtomically() {
    Project project = makeProject();
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(400);

    project.tracks[0].locked = true;
    auto before = projectHash(project);
    auto locked_track = applyEdit(project, move);
    CHECK_TRUE(!locked_track && locked_track.error().code == ErrorCode::LockedTrack);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks[0].clips[0].group_id = 42;
    project.tracks[0].clips[0].group_locked = true;
    before = projectHash(project);
    const auto locked_group = applyEdit(project, move);
    CHECK_TRUE(!locked_group && locked_group.error().code == ErrorCode::LockedGroup);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks[0].clips[0].group_id = 42;
    project.tracks[0].clips[1].group_id = 42;
    project.tracks[0].clips[1].group_locked = true;
    before = projectHash(project);
    const auto group_member_locked = applyEdit(project, move);
    CHECK_TRUE(!group_member_locked && group_member_locked.error().code == ErrorCode::LockedGroup);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks[0].clips[1].group_id = 77;
    project.tracks[0].clips[2].group_id = 77;
    project.tracks[0].clips[2].group_locked = true;
    EditCommand ripple_group_trim;
    ripple_group_trim.kind = EditKind::TrimEnd;
    ripple_group_trim.clip_id = ClipId{11};
    ripple_group_trim.target = f(110);
    ripple_group_trim.ripple = RippleScope::SameTrack;
    before = projectHash(project);
    const auto locked_group_follower = applyEdit(project, ripple_group_trim);
    CHECK_TRUE(!locked_group_follower && locked_group_follower.error().code == ErrorCode::LockedGroup);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks[0].clips[2].group_id = 88;
    project.tracks[0].clips[2].group_locked = true;
    Clip grouped_insert = makeClip(14, 1, 300, 500, 10);
    grouped_insert.group_id = 88;
    EditCommand insert_grouped;
    insert_grouped.kind = EditKind::Insert;
    insert_grouped.inserted_clip = grouped_insert;
    before = projectHash(project);
    const auto locked_group_insert = applyEdit(project, insert_grouped);
    CHECK_TRUE(!locked_group_insert && locked_group_insert.error().code == ErrorCode::LockedGroup);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks[0].clips[2].locked = true;
    EditCommand ripple_trim;
    ripple_trim.kind = EditKind::TrimEnd;
    ripple_trim.clip_id = ClipId{12};
    ripple_trim.target = f(210);
    ripple_trim.ripple = RippleScope::SameTrack;
    before = projectHash(project);
    const auto locked_follower = applyEdit(project, ripple_trim);
    CHECK_TRUE(!locked_follower && locked_follower.error().code == ErrorCode::LockedClip);
    CHECK_TRUE(projectHash(project) == before);

    project = makeProject();
    project.tracks.push_back(Track{TrackId{2}, true, false, {}});
    move.target_track_id = TrackId{2};
    before = projectHash(project);
    const auto locked_destination = applyEdit(project, move);
    CHECK_TRUE(!locked_destination && locked_destination.error().code == ErrorCode::LockedTrack);
    CHECK_TRUE(projectHash(project) == before);
}

void testINV05_GroupLockChangesAreAtomicAndNativeHistoryManaged() {
    Project project = makeProject();
    project.tracks[0].locked = true;
    project.tracks[0].clips[0].group_id = 42;
    project.tracks[0].clips[0].group_locked = true;
    project.tracks[0].clips[0].locked = true;
    project.tracks[0].clips[2].group_id = 42;
    project.tracks[0].clips[2].group_locked = true;
    const auto original_hash = projectHash(project);

    EditCommand unlock_group;
    unlock_group.kind = EditKind::SetGroupLock;
    unlock_group.clip_id = ClipId{11};
    unlock_group.group_locked = false;
    const auto preview = applyEdit(project, unlock_group);
    CHECK_TRUE(preview.has_value());
    CHECK_TRUE(projectHash(project) == original_hash);
    if (preview) {
        const auto [anchor_track, anchor] = findClipById(preview->project, ClipId{11});
        const auto [member_track, member] = findClipById(preview->project, ClipId{13});
        const auto [other_track, other] = findClipById(preview->project, ClipId{12});
        CHECK_TRUE(anchor_track != nullptr && anchor != nullptr && !anchor->group_locked);
        CHECK_TRUE(member_track != nullptr && member != nullptr && !member->group_locked);
        CHECK_TRUE(other_track != nullptr && other != nullptr && !other->group_locked);
        CHECK_TRUE(preview->changed_clip_ids.size() == 2U);
    }

    TimelineHistory history;
    const auto committed = history.apply(project, unlock_group);
    CHECK_TRUE(committed.has_value());
    CHECK_TRUE(history.canUndo());
    CHECK_TRUE(projectHash(project) != original_hash);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == original_hash);
    CHECK_TRUE(history.redo(project).has_value());
    CHECK_TRUE(projectHash(project) != original_hash);

    EditCommand lock_group = unlock_group;
    lock_group.group_locked = true;
    CHECK_TRUE(history.apply(project, lock_group).has_value());
    const auto [locked_anchor_track, locked_anchor] = findClipById(project, ClipId{11});
    const auto [locked_member_track, locked_member] = findClipById(project, ClipId{13});
    CHECK_TRUE(locked_anchor_track != nullptr && locked_anchor != nullptr && locked_anchor->group_locked);
    CHECK_TRUE(locked_member_track != nullptr && locked_member != nullptr && locked_member->group_locked);

    EditCommand invalid_lock = unlock_group;
    invalid_lock.group_locked.reset();
    const auto missing_value = applyEdit(project, invalid_lock);
    CHECK_TRUE(!missing_value && missing_value.error().code == ErrorCode::InvalidArgument);

    Project ungrouped = makeProject();
    EditCommand lock_clip;
    lock_clip.kind = EditKind::SetGroupLock;
    lock_clip.clip_id = ClipId{11};
    lock_clip.group_locked = true;
    const auto single = applyEdit(ungrouped, lock_clip);
    CHECK_TRUE(single.has_value());
    if (single) {
        const auto [track, clip] = findClipById(single->project, ClipId{11});
        const auto [other_track, other] = findClipById(single->project, ClipId{12});
        CHECK_TRUE(track != nullptr && clip != nullptr && clip->group_locked);
        CHECK_TRUE(other_track != nullptr && other != nullptr && !other->group_locked);
    }
}

void testINV06_UndoRestoresExactProjectHash() {
    Project project = makeProject();
    const auto original_hash = projectHash(project);
    TimelineHistory history;
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(400);
    const auto result = history.apply(project, move);
    CHECK_TRUE(result.has_value());
    CHECK_TRUE(projectHash(project) != original_hash);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == original_hash);
}

void testINV07_RedoAndBoundedHistoryRestoreHashes() {
    Project project = makeProject();
    TimelineHistory history(2);
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    const auto original_hash = projectHash(project);
    move.target = f(300);
    CHECK_TRUE(history.apply(project, move).has_value());
    const auto first_hash = projectHash(project);
    move.target = f(400);
    CHECK_TRUE(history.apply(project, move).has_value());
    const auto second_hash = projectHash(project);
    move.target = f(500);
    CHECK_TRUE(history.apply(project, move).has_value());
    const auto third_hash = projectHash(project);
    CHECK_TRUE(history.undoDepth() == 2U);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == second_hash);
    CHECK_TRUE(history.undo(project).has_value());
    CHECK_TRUE(projectHash(project) == first_hash);
    CHECK_TRUE(!history.canUndo());
    CHECK_TRUE(history.redo(project).has_value());
    CHECK_TRUE(projectHash(project) == second_hash);
    CHECK_TRUE(history.redo(project).has_value());
    CHECK_TRUE(projectHash(project) == third_hash);
    CHECK_TRUE(first_hash != original_hash);
}

void testINV08_TrimSourceBoundsRespectAvailableRange() {
    Project project = makeProject();
    project.tracks[0].clips.resize(1);
    project.markers.clear();
    auto& clip = project.tracks[0].clips[0];
    clip.available_range = TimeRange{f(0), f(110)};
    const auto before = projectHash(project);
    EditCommand trim;
    trim.kind = EditKind::TrimEnd;
    trim.clip_id = clip.id;
    trim.target = f(110);
    const auto boundary = applyEdit(project, trim);
    CHECK_TRUE(boundary.has_value());
    if (boundary) {
        const auto [track, updated] = findClipById(boundary->project, clip.id);
        CHECK_TRUE(track != nullptr && updated != nullptr);
        CHECK_TRUE(updated != nullptr && updated->source_range.duration == f(110));
    }
    trim.target = f(111);
    const auto outside = applyEdit(project, trim);
    CHECK_TRUE(!outside && outside.error().code == ErrorCode::SourceOutOfBounds);
    CHECK_TRUE(projectHash(project) == before);
}

void testINV09_MinimumDurationRejectsRatherThanClamping() {
    const Project project = makeProject();
    const auto before_hash = projectHash(project);
    EditCommand trim;
    trim.kind = EditKind::TrimStart;
    trim.clip_id = ClipId{11};
    trim.target = f(99);
    trim.minimum_duration = f(2);
    const auto result = applyEdit(project, trim);
    CHECK_TRUE(!result && result.error().code == ErrorCode::MinimumDuration);
    CHECK_TRUE(projectHash(project) == before_hash);
}

struct SnapCandidate {
    Flicks position;
    SnapKind kind;
    std::uint64_t id;
    std::uint8_t priority;
};

SnapResult oracleSnap(const Project& project,
                      Flicks input,
                      Flicks playhead,
                      const SnapConfig& config,
                      ClipId exclude_clip) {
    SnapResult best{input, false, SnapKind::TimelineStart, 0, Flicks{0}};
    std::uint8_t best_priority = std::numeric_limits<std::uint8_t>::max();
    const auto consider = [&](SnapCandidate candidate, SnapResult& current,
                              std::uint8_t& current_priority) {
        const auto difference = candidate.position.value - input.value;
        const auto distance = static_cast<std::uint64_t>(difference < 0 ? -difference : difference);
        if (distance > static_cast<std::uint64_t>(config.threshold.value)) return;
        const auto current_distance = static_cast<std::uint64_t>(
            current.distance.value < 0 ? -current.distance.value : current.distance.value);
        if (!current.snapped || distance < current_distance ||
            (distance == current_distance && candidate.priority < current_priority) ||
            (distance == current_distance && candidate.priority == current_priority &&
             candidate.position.value < current.position.value)) {
            current = SnapResult{candidate.position, true, candidate.kind, candidate.id,
                                 Flicks{static_cast<std::int64_t>(distance)}};
            current_priority = candidate.priority;
        }
    };

    if (config.snap_to_start) {
        consider({Flicks{0}, SnapKind::TimelineStart, 0, 4}, best, best_priority);
    }
    if (config.snap_to_playhead) {
        consider({playhead, SnapKind::Playhead, 0, 0}, best, best_priority);
    }
    if (config.snap_to_clip_edges) {
        for (const auto& track : project.tracks) {
            for (const auto& clip : track.clips) {
                if (clip.id == exclude_clip) continue;
                const auto end = clipTimelineEnd(clip);
                if (!end) continue;
                consider({clip.timeline_start, SnapKind::ClipEdge, clip.id.value, 2}, best,
                         best_priority);
                consider({*end, SnapKind::ClipEdge, clip.id.value, 2}, best, best_priority);
            }
        }
    }
    if (config.snap_to_markers) {
        for (const auto& marker : project.markers) {
            consider({marker.position, SnapKind::Marker, marker.id, 1}, best, best_priority);
        }
    }
    if (config.snap_to_grid && config.grid_interval.value > 0) {
        const auto interval = config.grid_interval.value;
        const auto lower = (input.value / interval) * interval;
        consider({Flicks{lower}, SnapKind::Grid, 0, 3}, best, best_priority);
        if (lower <= std::numeric_limits<std::int64_t>::max() - interval) {
            consider({Flicks{lower + interval}, SnapKind::Grid, 0, 3}, best, best_priority);
        }
    }
    return best;
}

void testINV10_SnapMatchesIndependentOracleForTenThousandCases() {
    Project project = makeProject();
    project.markers.push_back(Marker{902, f(350)});
    project.markers.push_back(Marker{903, f(775)});
    std::uint64_t state = 0x9e3779b97f4a7c15ULL;
    for (int index = 0; index < 10'000; ++index) {
        const Flicks input = f(static_cast<std::int64_t>(nextRandom(state) % 2'000U));
        const Flicks playhead = f(static_cast<std::int64_t>(nextRandom(state) % 2'000U));
        SnapConfig config;
        config.threshold = f(static_cast<std::int64_t>(nextRandom(state) % 40U));
        config.snap_to_start = (nextRandom(state) & 1U) != 0U;
        config.snap_to_playhead = (nextRandom(state) & 1U) != 0U;
        config.snap_to_clip_edges = (nextRandom(state) & 1U) != 0U;
        config.snap_to_markers = (nextRandom(state) & 1U) != 0U;
        config.snap_to_grid = (nextRandom(state) & 1U) != 0U;
        config.grid_interval = f(1 + static_cast<std::int64_t>(nextRandom(state) % 70U));
        const ClipId exclude = (nextRandom(state) & 1U) != 0U ? ClipId{11} : ClipId{};
        const auto actual = snap(project, input, playhead, config, exclude);
        CHECK_TRUE(actual.has_value());
        if (actual) CHECK_TRUE(*actual == oracleSnap(project, input, playhead, config, exclude));
    }
}

void testINV11_PureEditsNeverMutateTheirInput() {
    const Project project = makeProject();
    const auto before = projectHash(project);
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(500);
    const auto result = applyEdit(project, move);
    CHECK_TRUE(result.has_value());
    CHECK_TRUE(projectHash(project) == before);
    if (result) CHECK_TRUE(projectHash(result->project) != before);
}

std::vector<std::uint8_t> hexBytes(std::string_view hex) {
    const auto nibble = [](char value) -> std::uint8_t {
        if (value >= '0' && value <= '9') return static_cast<std::uint8_t>(value - '0');
        if (value >= 'a' && value <= 'f') return static_cast<std::uint8_t>(value - 'a' + 10);
        if (value >= 'A' && value <= 'F') return static_cast<std::uint8_t>(value - 'A' + 10);
        std::abort();
    };
    if (hex.size() % 2U != 0U) std::abort();
    std::vector<std::uint8_t> bytes;
    bytes.reserve(hex.size() / 2U);
    for (std::size_t index = 0; index < hex.size(); index += 2U) {
        bytes.push_back(static_cast<std::uint8_t>((nibble(hex[index]) << 4U) |
                                                  nibble(hex[index + 1U])));
    }
    return bytes;
}

Project makeGoldenProject() {
    Clip clip{ClipId{2}, TrackId{1}, TimeRange{Flicks{0}, Flicks{1}}, Flicks{0},
              TimeRange{Flicks{0}, Flicks{1}}, false, 0, false, true};
    Track track{TrackId{1}, false, false, {clip}};
    return Project{1, FrameRate{24, 1}, {track}, {Marker{9, Flicks{1}}}};
}

void testINV12_VersionedSerializationHasGoldenAndRoundTripCoverage() {
    const Project project = makeGoldenProject();
    const auto encoded = serializeProject(project);
    CHECK_TRUE(encoded.has_value());
    if (!encoded) return;
    const auto golden = hexBytes(
        "544c434f5245320001000000010000000000000018000000000000000100000000000000010000000000000001000000000000000001000000000000000200000000000000010000000000000000000000000000000100000000000000000000000000000000000000000000000100000000000000000000000000000004010000000000000009000000000000000100000000000000");
    CHECK_TRUE(*encoded == golden);
    const auto decoded = deserializeProject(*encoded);
    CHECK_TRUE(decoded.has_value());
    if (decoded) {
        CHECK_TRUE(projectHash(*decoded) == projectHash(project));
        const auto encoded_again = serializeProject(*decoded);
        CHECK_TRUE(encoded_again.has_value() && *encoded_again == *encoded);
    }

    auto unsupported = *encoded;
    unsupported[8] = static_cast<std::uint8_t>(kProjectSchemaVersion + 1U);
    const auto unsupported_result = deserializeProject(unsupported);
    CHECK_TRUE(!unsupported_result &&
               unsupported_result.error().code == ErrorCode::UnsupportedVersion);

    auto truncated = *encoded;
    truncated.pop_back();
    const auto truncated_result = deserializeProject(truncated);
    CHECK_TRUE(!truncated_result && truncated_result.error().code == ErrorCode::CorruptData);

    auto bad_track_flags = *encoded;
    bad_track_flags[52] = 4U;
    const auto bad_flags_result = deserializeProject(bad_track_flags);
    CHECK_TRUE(!bad_flags_result && bad_flags_result.error().code == ErrorCode::CorruptData);

    auto trailing = *encoded;
    trailing.push_back(0U);
    const auto trailing_result = deserializeProject(trailing);
    CHECK_TRUE(!trailing_result && trailing_result.error().code == ErrorCode::CorruptData);
}

void testINV12_DeterministicMutationRobustnessForSerializedProjects() {
    const auto encoded = serializeProject(makeProject());
    CHECK_TRUE(encoded.has_value());
    if (!encoded) return;
    std::uint64_t state = 0x243f6a8885a308d3ULL;
    for (int iteration = 0; iteration < 10'000; ++iteration) {
        auto mutation = *encoded;
        const auto index = static_cast<std::size_t>(nextRandom(state) % mutation.size());
        mutation[index] ^= static_cast<std::uint8_t>(1U << (nextRandom(state) % 8U));
        const auto decoded = deserializeProject(mutation);
        if (decoded) {
            CHECK_TRUE(validateProject(*decoded).has_value());
            const auto serialized_again = serializeProject(*decoded);
            CHECK_TRUE(serialized_again.has_value());
            if (serialized_again) {
                const auto round_trip = deserializeProject(*serialized_again);
                CHECK_TRUE(round_trip.has_value());
                if (round_trip) {
                    CHECK_TRUE(projectHash(*decoded) == projectHash(*round_trip));
                    const auto canonical_again = serializeProject(*round_trip);
                    CHECK_TRUE(canonical_again.has_value() && *canonical_again == *serialized_again);
                }
            }
        }
    }
}

void testINV11_OneClipTenThousandRandomUndoRedoCycles() {
    Project project{42, {24, 1},
                    {Track{TrackId{1}, false, false, {makeClip(7, 1, 0, 0, 100)}}}, {}};
    TimelineHistory history(8);
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{7};
    move.target_track_id = TrackId{1};
    std::uint64_t state = 0xd1b54a32d192ed03ULL;
    for (int iteration = 0; iteration < 10'000; ++iteration) {
        const auto before = projectHash(project);
        move.target = f(static_cast<std::int64_t>(nextRandom(state) % 1'000'000U));
        const auto applied = history.apply(project, move);
        CHECK_TRUE(applied.has_value());
        if (!applied) continue;
        const auto after = projectHash(project);
        CHECK_TRUE(after == applied->project_hash);
        CHECK_TRUE(history.undo(project).has_value());
        CHECK_TRUE(projectHash(project) == before);
        CHECK_TRUE(history.redo(project).has_value());
        CHECK_TRUE(projectHash(project) == after);
    }
}

void testINV06_07_TenThousandRandomizedMultiTrackHistoryOperations() {
    constexpr std::size_t track_count = 4;
    constexpr std::size_t operation_count = 10'000;
    constexpr std::size_t maximum_history = 512;
    Project project{43, {24, 1}, {}, {}};
    for (std::size_t index = 0; index < track_count; ++index) {
        const auto track_id = static_cast<std::uint64_t>(index + 1U);
        const auto clip_id = static_cast<std::uint64_t>(index + 101U);
        project.tracks.push_back(Track{
            TrackId{track_id}, false, false,
            {makeClip(clip_id, track_id, static_cast<std::int64_t>(index * 100U), 0, 100)}});
    }

    TimelineHistory history(maximum_history);
    std::vector<std::uint64_t> state_hashes;
    state_hashes.reserve(operation_count + 1U);
    state_hashes.push_back(projectHash(project));
    std::uint64_t random_state = 0x7f4a7c159e3779b9ULL;

    for (std::size_t iteration = 0; iteration < operation_count; ++iteration) {
        const auto selected_track = static_cast<std::size_t>(nextRandom(random_state) % track_count);
        const auto track_id = static_cast<std::uint64_t>(selected_track + 1U);
        const auto clip_id = ClipId{static_cast<std::uint64_t>(selected_track + 101U)};
        const auto [track, clip] = findClipById(project, clip_id);
        CHECK_TRUE(track != nullptr && clip != nullptr);
        if (track == nullptr || clip == nullptr) continue;

        const auto current_frame = flicksToFrame(clip->timeline_start, project.frame_rate);
        CHECK_TRUE(current_frame.has_value());
        if (!current_frame) continue;
        const auto target_frame = *current_frame +
            static_cast<std::int64_t>(1U + nextRandom(random_state) % 500U);
        EditCommand move;
        move.kind = EditKind::Move;
        move.clip_id = clip_id;
        move.target_track_id = TrackId{track_id};
        move.target = f(target_frame, project.frame_rate);

        const auto prior_hash = projectHash(project);
        const auto applied = history.apply(project, move);
        CHECK_TRUE(applied.has_value());
        if (!applied) continue;
        const auto after_hash = projectHash(project);
        CHECK_TRUE(applied->project_hash == after_hash);
        CHECK_TRUE(prior_hash != after_hash);
        state_hashes.push_back(after_hash);

        const auto completed = state_hashes.size() - 1U;
        if (completed % 257U == 0U) {
            const auto undo_count = std::min<std::size_t>(73U, history.undoDepth());
            for (std::size_t offset = 0; offset < undo_count; ++offset) {
                CHECK_TRUE(history.undo(project).has_value());
                CHECK_TRUE(projectHash(project) == state_hashes[completed - offset - 1U]);
            }
            for (std::size_t offset = 0; offset < undo_count; ++offset) {
                CHECK_TRUE(history.redo(project).has_value());
                CHECK_TRUE(projectHash(project) == state_hashes[completed - undo_count + offset + 1U]);
            }
        }
    }

    CHECK_TRUE(state_hashes.size() == operation_count + 1U);
    const auto retained_depth = history.undoDepth();
    CHECK_TRUE(retained_depth == maximum_history);
    const auto final_index = state_hashes.size() - 1U;
    for (std::size_t offset = 0; offset < retained_depth; ++offset) {
        CHECK_TRUE(history.undo(project).has_value());
        CHECK_TRUE(projectHash(project) == state_hashes[final_index - offset - 1U]);
    }
    for (std::size_t offset = 0; offset < retained_depth; ++offset) {
        CHECK_TRUE(history.redo(project).has_value());
        CHECK_TRUE(projectHash(project) == state_hashes[final_index - retained_depth + offset + 1U]);
    }
    CHECK_TRUE(projectHash(project) == state_hashes.back());
}

void testUnsupportedRippleFlagIsRejectedInsteadOfIgnored() {
    const Project project = makeProject();
    const auto before = projectHash(project);
    constexpr std::array unsupported_kinds{
        EditKind::Move, EditKind::Split, EditKind::Slip, EditKind::Roll, EditKind::Slide,
        EditKind::SetGroupLock};
    for (const auto kind : unsupported_kinds) {
        EditCommand command;
        command.kind = kind;
        command.clip_id = ClipId{11};
        command.other_clip_id = ClipId{12};
        command.target_track_id = TrackId{1};
        command.target = f(400);
        command.delta = f(1);
        command.minimum_duration = f(1);
        if (kind == EditKind::SetGroupLock) command.group_locked = true;
        command.ripple = RippleScope::SameTrack;
        const auto result = applyEdit(project, command);
        CHECK_TRUE(!result && result.error().code == ErrorCode::InvalidArgument);
        CHECK_TRUE(projectHash(project) == before);
    }
}

void testDeleteRippleUsesUnionAndRejectsDuplicateSelection() {
    Project project{9, {24, 1},
                    {Track{TrackId{1}, false, false,
                           {makeClip(11, 1, 0, 0, 10), makeClip(12, 1, 10, 10, 10),
                            makeClip(13, 1, 100, 20, 10)}},
                     Track{TrackId{2}, false, false,
                           {makeClip(21, 2, 0, 0, 10), makeClip(22, 2, 100, 10, 10)}}},
                    {}};
    EditCommand deletion;
    deletion.kind = EditKind::Delete;
    deletion.clip_ids = {ClipId{11}, ClipId{21}};
    deletion.ripple = RippleScope::AllTracks;
    const auto result = applyEdit(project, deletion);
    CHECK_TRUE(result.has_value());
    if (result) {
        const auto [track12, clip12] = findClipById(result->project, ClipId{12});
        const auto [track13, clip13] = findClipById(result->project, ClipId{13});
        const auto [track22, clip22] = findClipById(result->project, ClipId{22});
        CHECK_TRUE(track12 != nullptr && clip12 != nullptr && clip12->timeline_start == f(0));
        CHECK_TRUE(track13 != nullptr && clip13 != nullptr && clip13->timeline_start == f(90));
        CHECK_TRUE(track22 != nullptr && clip22 != nullptr && clip22->timeline_start == f(90));
        CHECK_TRUE(validateProject(result->project).has_value());
    }

    deletion.clip_ids = {ClipId{11}, ClipId{11}};
    const auto before = projectHash(project);
    const auto duplicate = applyEdit(project, deletion);
    CHECK_TRUE(!duplicate && duplicate.error().code == ErrorCode::InvalidArgument);
    CHECK_TRUE(projectHash(project) == before);
}

void testSlideUsesTimelineAdjacencyNotStorageOrder() {
    Project project = makeProject();
    EditCommand move;
    move.kind = EditKind::Move;
    move.clip_id = ClipId{11};
    move.target_track_id = TrackId{1};
    move.target = f(300);
    const auto moved = applyEdit(project, move);
    CHECK_TRUE(moved.has_value());
    if (!moved) return;
    move.target = f(0);
    const auto reordered = applyEdit(moved->project, move);
    CHECK_TRUE(reordered.has_value());
    if (!reordered) return;
    CHECK_TRUE(reordered->project.tracks[0].clips[0].id == ClipId{12});

    EditCommand slide;
    slide.kind = EditKind::Slide;
    slide.clip_id = ClipId{12};
    slide.delta = f(10);
    const auto result = applyEdit(reordered->project, slide);
    CHECK_TRUE(result.has_value());
    if (result) {
        const auto [left_track, left] = findClipById(result->project, ClipId{11});
        const auto [right_track, right] = findClipById(result->project, ClipId{13});
        CHECK_TRUE(left_track != nullptr && left != nullptr);
        CHECK_TRUE(left != nullptr && left->source_range.duration == f(110));
        CHECK_TRUE(right_track != nullptr && right != nullptr);
        CHECK_TRUE(right != nullptr && right->timeline_start == f(210));
    }
}

}  // namespace

int main() {
    testStableErrorCodeNumbersAndNames();
    testFrameRateRoundTrips();
    testFrameArithmeticRejectsOverflowAndRoundsTiesToEven();
    testF1_TrimOrientationAndGhostCommitShareComputeTrim();
    testINV04_RejectedMoveIsAtomicAndLockedClipCannotMove();
    testINV04_MultiClipMoveIsAtomicAndPreservesRelativeOffsets();
    testSlipPreservesTimelineAndClampsToAvailableSourceRange();
    testINV04_BatchedEditsAreAtomicAndHistoryHasSingleEntry();
    testRippleTrimsShiftFollowingClipsAndPreserveInvariants();
    testSplitDeleteRollSlideAndInsert();
    testHistoryUndoRedoAndCompaction();
    testINV06_07_DeltaHistoryRoundTripsStructuralEditsAndClipOrder();
    testINV06_UndoRejectsExternalMutationWithoutChangingHistoryOrProject();
    testINV04_NoOpDoesNotCreateOrClearHistory();
    testSnapAndVisibleClipQuery();
    testINV01_DurationIsDerivedFromSourceRange();
    testINV02_ClipEndAndProjectDurationUseIntegerFlicks();
    testINV03_OverlapsRejectUnlessTrackAllowsThem();
    testINV05_TrackClipGroupAndRippleLocksRejectAtomically();
    testINV05_GroupLockChangesAreAtomicAndNativeHistoryManaged();
    testINV06_UndoRestoresExactProjectHash();
    testINV07_RedoAndBoundedHistoryRestoreHashes();
    testINV08_TrimSourceBoundsRespectAvailableRange();
    testINV09_MinimumDurationRejectsRatherThanClamping();
    testINV10_SnapMatchesIndependentOracleForTenThousandCases();
    testINV11_PureEditsNeverMutateTheirInput();
    testINV12_VersionedSerializationHasGoldenAndRoundTripCoverage();
    testINV12_DeterministicMutationRobustnessForSerializedProjects();
    testINV11_OneClipTenThousandRandomUndoRedoCycles();
    testINV06_07_TenThousandRandomizedMultiTrackHistoryOperations();
    testUnsupportedRippleFlagIsRejectedInsteadOfIgnored();
    testDeleteRippleUsesUnionAndRejectsDuplicateSelection();
    testSlideUsesTimelineAdjacencyNotStorageOrder();

    if (failures != 0) {
        std::cerr << "Timeline Core v2 tests failed: " << failures << " / " << assertions
                  << " assertions\n";
        return EXIT_FAILURE;
    }
    std::cout << "Timeline Core v2 tests passed: " << assertions
              << " assertions; 10,000 snap-oracle cases, 10,000 randomized multi-track history operations, "
                 "and 10,000 single-clip undo/redo cycles.\n";
    return EXIT_SUCCESS;
}
