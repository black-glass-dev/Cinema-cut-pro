# Timeline Core v2 Error Codes

| Code | Meaning | Expected handling |
|---|---|---|
| `TL_E_INVALID_ARGUMENT` | Invalid rate, threshold, duration, or required command field. | Do not mutate; show/record stable validation error. |
| `TL_E_OVERFLOW` | Checked time arithmetic cannot represent the result. | Reject; preserve project/history hashes. |
| `TL_E_INVALID_RANGE` | Negative timeline position or non-positive/invalid range. | Reject. |
| `TL_E_INVALID_PROJECT` | Project/track/clip invariants fail before/after operation. | Reject and preserve the input snapshot. |
| `TL_E_DUPLICATE_ID` | Duplicate track, clip, or marker id. | Reject. |
| `TL_E_CLIP_NOT_FOUND` | Command references a missing clip. | Reject. |
| `TL_E_TRACK_NOT_FOUND` | Command references a missing track. | Reject. |
| `TL_E_LOCKED_TRACK` | Operation would mutate a locked track. | Reject atomically. |
| `TL_E_LOCKED_CLIP` | Operation would mutate a locked clip. | Reject atomically. |
| `TL_E_LOCKED_GROUP` | Operation would mutate a locked group. | Reject atomically. |
| `TL_E_SOURCE_OUT_OF_BOUNDS` | Source range leaves media available range. | Reject or use documented slip clamp only. |
| `TL_E_MIN_DURATION` | Trim/roll/slide violates minimum duration. | Reject; never silently clamp. |
| `TL_E_NOT_ADJACENT` | Roll/slide preconditions are not met. | Reject. |
| `TL_E_INVALID_EDIT` | Command cannot be applied under current model. | Reject. |
| `TL_E_NO_UNDO` / `TL_E_NO_REDO` | Requested history direction is empty. | No state change. |
| `TL_E_ALLOCATION_FAILURE` | Host allocation failed while building an immutable candidate or decoding/encoding. | No state change where recoverable; report failure. |
| `TL_E_UNSUPPORTED_VERSION` | Serialized project uses a schema version not supported by this core. | Do not edit or rewrite the source bytes. |
| `TL_E_CORRUPT_DATA` | Serialized project is truncated, malformed, invalid, or has trailing bytes. | Reject without replacing persisted state. |
| `TL_E_LIMIT_EXCEEDED` | Serialization input exceeds the core's supported collection-count limit. | Reject; do not partially serialize. |
| `TL_E_HISTORY_CONFLICT` | Undo/redo project hash does not match the journal's expected state. | Reject without mutating project or history. |
