---
name: agent-continuity-protocol
description: Keep a coding agent working autonomously from task start to verified completion — no mid-task permission prompts, checkpointed progress, durable worklog, evidence-based definition of done, and honest blocked/unverified reporting. Use when a task is large and multi-phase, when the agent tends to stop early or ask for permission, or when the user wants one final report instead of repeated input requests.
license: MIT
---

# Agent continuity protocol

The failure this fixes: an agent does 20% of a big task, stops, asks "shall I continue?", and the human
becomes the scheduler. This protocol makes the agent the owner of the loop.

## 1. Hard rules

1. **No permission prompts.** The user granted the task; asking again burns their time. Decide, record
   the rationale in the ADR/worklog, continue.
2. **Only two legitimate stop reasons** (and neither stops the whole task):
   - `BLOCKED`: needs a secret/paid account/physical action only the human can do → write
     `docs/BLOCKED.md` with what was tried, the exact blocker, and what unblocks it, then continue with
     everything not blocked.
   - `UNVERIFIED`: needs physical hardware (thermal/profiling claims) → write `docs/UNVERIFIED.md`,
     do the emulator/host equivalent, label the claim honestly, continue.
3. **Phase checkpoints.** After every phase: build → test → commit (conventional commit) → 5-line
   `docs/WORKLOG.md` entry. The repo is always in a resumable state.
4. **Priority under pressure.** If context/time runs short, finish in this order: compiling scaffolding →
   core semantics + tests → undo/redo → preview → export → UI polish. Never leave priority 1–3 half done.
5. **Same mistake twice is forbidden.** After each failure add "root cause → rule" to the worklog and obey
   the rule for the rest of the task.
6. **One final report.** All findings, evidence, and gaps in a single closing message, in the user's
   language, with reproducible commands.

## 2. Worklog entry format

```
## 2026-11-11T09:14Z — P2 trim engine
Done: computeTrim single-sourced, 14 unit tests, INV-01/08/09 green.
Failed: slide bounds check missed neighbour lock (fixed in 3f2a1c).
Evidence: docs/evidence/GATE_20261111T0905Z.log (PASS).
Next: roll + ripple property tests.
```

## 3. Anti-patterns

- "Let me know if you'd like me to continue" — banned phrasing.
- Reporting intentions ("I will now implement X") as if they were outcomes.
- Compressing scope silently to reach "done" (e.g. dropping tests).
- Marking something `@Ignore`/`DISABLED_`/`TODO` and calling the phase complete.
- Inventing benchmark numbers, test counts, or library versions.
- Asking a question whose answer is discoverable in the repo, docs, or by compiling.

## 4. Honest completion vocabulary

| Status | Meaning |
|---|---|
| `DONE` | implemented + tested + gated, evidence logged |
| `PARTIAL` | implemented for a subset; subset named explicitly |
| `NOT IMPLEMENTED` | not attempted; reason given |
| `BLOCKED` | needs human action; unblock steps listed |
| `UNVERIFIED` | code exists but claim not measurable here |

Every line in the final report uses one of these words. "Production ready" is not in the vocabulary.

## 5. Context budget discipline

- Keep a `docs/PLAN.md` with checkboxes; read it at the start of each phase instead of re-deriving state.
- Prefer reading grep/bounded file views over whole files; keep third-party source out of context.
- Write decisions to disk immediately; the worklog *is* the memory.
- Batch independent commands in a single tool call to save turns.

## 6. Final report skeleton

```
1. Status: DONE / DONE-WITH-GAPS / BLOCKED
2. Modules and files created (tree)
3. Invariants → test name → pass/fail → log reference
4. Audit findings F1..Fn → regression test → evidence
5. Test statistics (unit/property/golden/fuzz/sanitizers)
6. Performance table (budget vs measured, device named)
7. Not done (honest list) + why
8. Risks and next actions, prioritized
9. Reproduce: exact commands for the gate
```
