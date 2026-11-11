# Timeline Core v2 — skill pack

Install these folders into your agent's skills directory:

- Claude Code / Codex / Cursor / Gemini CLI: `.claude/skills/` or `.agents/skills/`
- Claude.ai: upload each folder (or the whole pack as a zip) under **Customize → Skills**

## A. Project-specific skills (written for this task)

| Skill | Why it exists | File |
|---|---|---|
| `timeline-core-invariants` | Single-source trim/roll/slip/slide/ripple math, flick timebase, validate-then-commit, INV-01..14 test matrix | `timeline-core-invariants/SKILL.md` |
| `ndk-cpp26-toolchain` | NDK r30 / Clang 22 / libc++ 22 reality, `-std=c++2c` gating, CMake presets, sanitizers, 16 KB pages | `ndk-cpp26-toolchain/SKILL.md` |
| `media-pipeline-ffmpeg-vulkan` | FFmpeg 9 LGPL build, MediaCodec HW decode, Vulkan zero-copy compositor, colour management, export | `media-pipeline-ffmpeg-vulkan/SKILL.md` |
| `deterministic-test-harness` | Property/golden/fuzz/bench suites + one gate script that writes evidence logs | `deterministic-test-harness/SKILL.md` |
| `agent-continuity-protocol` | No mid-task permission prompts, phase checkpoints, honest DONE/PARTIAL/BLOCKED/UNVERIFIED vocabulary | `agent-continuity-protocol/SKILL.md` |
| `kotlin-jni-boundary` | RegisterNatives/fbjni, exception translation, handle lifecycle, virtualized Compose timeline | `kotlin-jni-boundary/SKILL.md` |

In a conflict between a skill and the main prompt, the prompt wins; skills are the "how".

## B. Upstream skills worth installing (existence verified 2026-11-11)

| Repo | License | Skills relevant to this project |
|---|---|---|
| [anthropics/skills](https://github.com/anthropics/skills) | Apache-2.0 (spec + examples) | `skill-creator`, `webapp-testing`, plus the SKILL.md spec/reference |
| [android/skills](https://github.com/android/skills) | Apache-2.0 | `jetpack-compose/jetpack-compose-performance`, `build-system/agp/agp-9-upgrade`, `performance/anr-audit-and-fix`, `testing/testing-setup`, `profilers/android-profiler`, `system/edge-to-edge` |
| [jh0per/AndroidSkills](https://github.com/jh0per/AndroidSkills) | MIT | `native-interop` (JNI + 16 KB alignment), `media-video-audio` (Media3/Transformer), `image-filters-gpu` (AGSL/Vulkan), `compose-performance-audit`, `architecture-guard-tests`, `verification-discipline`, `gradle-quality-gate` |
| [Kotlin/kotlin-agent-skills](https://github.com/Kotlin/kotlin-agent-skills) | see repo | `kotlin-tooling-agp9-migration`, `kotlin-tooling-native-build-performance` |
| [obra/superpowers](https://github.com/obra/superpowers) | MIT | `test-driven-development`, `verification-before-completion`, `systematic-debugging`, `writing-plans`, `executing-plans` |
| [addyosmani/agent-skills](https://github.com/addyosmani/agent-skills) | MIT | `performance-optimization`, `documentation-and-adrs`, `constraint-driven-development`, `spec-driven-development`, `code-review-and-quality` |
| [VoltAgent/awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills) | catalogue | discovery of further vendor skills |

Install examples:

```bash
# Claude Code / Codex plugin marketplaces
/plugin marketplace add android/skills
/plugin install android-skills@android-skills

# community Android pack (symlink so updates flow through)
git clone https://github.com/jh0per/AndroidSkills ~/AndroidSkills
mkdir -p .agents/skills && ln -s ~/AndroidSkills/skills/* .agents/skills/

# or install our project skills next to them
cp -r skill-pack/* .agents/skills/
```

## C. SKILL.md format rules (from the open spec)

- File must be `SKILL.md` at the folder root; `name` = folder name, lowercase letters/digits/hyphens,
  ≤ 64 chars; `description` ≤ 1024 chars and must say **what** it does and **when** to use it.
- Optional: `license`, `compatibility`, `metadata`, `allowed-tools`. Body ideally < 500 lines, with
  `references/`, `scripts/`, `assets/` for depth (progressive disclosure).

## D. Sources checked while writing this pack

- Agent Skills specification — https://agentskills.io/specification
- Android skills docs — https://developer.android.com/tools/agents/android-skills
- Android NDK changelog r30 (Clang/LLVM 22) — https://github.com/android/ndk/wiki/Changelog-r30
- Clang C++ status (`-std=c++2c`, partial) — https://clang.llvm.org/cxx_status.html
- libc++ C++26 status — https://libcxx.llvm.org/Status/Cxx26.html
- FFmpegKit retirement + FFmpeg 9.0 release notes
- Google Play 16 KB page-size requirement (deadline moved to 2027-02-01)
- JNI tips (RegisterNatives) — https://developer.android.com/ndk/guides/jni-tips
