# RM-UI-DS-R1.0K.11Q.1 — External browser and final freeze admission

2026-11-28 | baseline 868377f838f3388a407809a343c572cd40093c05 | design/ui-design-system-r1

Visual baseline APPROVED; technical Freeze HOLD. No runtime, palette, business, API or CI changes. Added an external qualification harness and manual acceptance record only.

Actual work in the current environment:
- Verified 16/16 existing source Git blob hashes against K11Q evidence. Previous 210/210 fixture groups are historical K11Q evidence, not rerun here.
- Added qualify_names.py, ran Chromium 144.0.7559.96 in fixture mode: 15/15 groups PASS, five views x RU/KK/EN on 1440x850 viewport. Representative Playwright accessibility snapshot heuristic detected no unnamed controls of the enumerated roles in the tested view states. This is not a complete accessible-name or screen-reader audit.
- Inspected static HTML Cyrillic strings and attributes against locale dictionary; intentional language self-names exempt. Dictionary 181 unique nonempty rows, matching placeholder sets across locales. Technical component names/brand names remain invariant. Structural checks are not linguistic acceptance; no qualified speaker signoff claimed.
- Reattempted Chromium HTTP K11Q probe on unchanged source: exit 1, net::ERR_BLOCKED_BY_ADMINISTRATOR. No bypass. System Chromium exists; Playwright managed Chromium, Firefox and WebKit binaries are absent. External runner preflight executed: HOLD_PREFLIGHT, exit 1. Full runner external browser matrix not executed here.
- Added fail-closed qualify_external.py and K11Q1-MANIFEST.txt: explicit HTTP, pinned Playwright 1.57.0, three managed browser engines, 225 planned Chromium groups and 47 each Firefox/WebKit (319 total planned, not executed). Counts/exit/mode checked; source copy hashes checked; logs and JSON report captured. Manual freeze always HOLD in script. Runtime unchanged.
- Manual acceptance matrix in K11Q1-MANUAL.md covers actual Safari, physical devices, real zoom, VoiceOver, accessibility and linguistic acceptance.

Current actual new checked test blob: qualify_names.py 7b82db40f6bdf54e61962a486f07ecab6b633d59.
Runner preflight blob: qualify_external.py eec806eb12a4dcd4a856e15baf7fde28b57910d2.
Manifest: a570028a5e20cbb281fe3f367b54855beed660aa.

Required next evidence: external real HTTP runs and manual matrix, actual browser/OS versions, raw logs and reviewer results. No Freeze waiver or production admission. 
