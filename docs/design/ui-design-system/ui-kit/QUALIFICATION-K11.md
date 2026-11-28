# RM-UI-DS-R1.0K.11A–F — Comprehensive UI Qualification

Date 2026-11-28 | branch design/ui-design-system-r1 | parent a12f3d07b59bd355ef4c333fed8775397d040568.

## Decision

Visual baseline remains approved. Technical Design Freeze HOLD. Production Admission HOLD. No backend, API, CI, icon, theme token or commercial visual asset changes. Synthetic data only. K11 is one package, not six independent release gates.

## Changes and evidence

- K11A: existing registry row checkbox wrapped in native label; hit area >=24 CSS px desktop and >=48 narrow/coarse, compact visual glyph unchanged. Corrected obsolete shell references in current charter.
- K11B: Guardian tests exercise the hit area by clicking outside the glyph, verify state and minimum sizes, and retain existing contrast, keyboard, focus and dialog assertions. Not a full WCAG or screen-reader certification.
- K11C: full existing 12-width Chromium regression, short landscape, coarse pointer and 200% root font size. OYU/Orbital expanded to four themes, print checks retained/added. Actual browser zoom and Firefox/WebKit/Safari/physical devices remain open.
- K11D: new RU/KK/EN long heading, breadcrumb and nav label fixtures at 320/1440 across four themes, exact large decimal amount 123456789012345.67 KZT, rejection of negative and excess precision for the existing nonnegative input contract, chart zero vs missing. These fixtures are not full localization or production currency qualification. An initial test assertion using row innerText separators was corrected to compare individual cell text; final run passed.
- K11E: shared test helper supports fixture (default, inline same local source) and real loopback HTTP (original files, verifies seven HTTP asset responses and actual SVG glyph). HTTP mode attempts on five suites all failed at navigation with net::ERR_BLOCKED_BY_ADMINISTRATOR in the current environment. No security-policy bypass or HTTP success claimed. Then ran fixture mode on final source.
- K11F: no Freeze declaration; remaining admission gates below.

## Actual test execution

Chromium 144.0.7559.96; Playwright 1.57.0. Fixture mode, all exit 0:

| Suite | PASS |
|---|---:|
| qualify_unified.py | 16 |
| qualify_motion.py | 2 |
| qualify_guardian.py | 32 |
| qualify_orbit.py | 40 |
| qualify_oyu.py | 56 |
| qualify_boundaries.py | 32 |
| Total | 178 |

Counts are executed scenario/check-group markers, not unique requirements or all combinations. Boundary suite includes 24 locale expansion and 8 exact-value groups. Unified and Motion retain original theme scope. No new runtime dependency. HTTP status BLOCKED, not tested successfully.

## Exact source manifest (Git blob SHA-1)

| Source | Blob |
|---|---|
| index.html | 7a01fe8ea99e183ad20ec5b5d516491d1d4a5905 |
| components.css | dd1a3717b3eb59e36679b422b9bd838beeccd863 |
| tokens.css | 2180bda8a2975ce4fb605fa2fc2f27e3ed8f3664 |
| icons.svg | 302d2ec13ffd770f160b167a8c5098aa2737ef24 |
| catalog.js | ca957fd1f56ebdbff5f991af8c3fcdba9c2952f3 |
| layout.css | 09c65f3568029933eb842c2f59c77bec8dcab736 |
| unified.js | 4e92a10b8fe13bdd5d90ed01924881fe3354a21d |
| qualify_guardian.py | bebe0b22cab16305c48bc7e0c6fcdc23b94becb8 |
| qualify_http.py | 24a652006b1b7631698fadd7182cb12c78591cb8 |
| qualify_motion.py | 6a9cd214fdc3ecd0c5d6ab5cd73a4e60454bd23f |
| qualify_orbit.py | a3d2bd66f1992eb56ea4010b77a7397409edcb8e |
| qualify_oyu.py | 62b81012066bcf45a0e1d713bca302a5b059c444 |
| qualify_unified.py | ebbe874165f3e0717e48538988170de9b4214bd7 |
| qualify_boundaries.py | 83a017237a21d6a23ac024ac674fc810e9ac9b9b |

## Reproduction

From ui-kit, with Python, Playwright and Chromium installed (CHROMIUM_PATH may select binary):

```sh
export RM_UI_TEST_MODE=fixture
for suite in unified motion guardian orbit oyu boundaries; do
  python "qualify_$suite.py" || exit 1
done
```

Repeat with RM_UI_TEST_MODE=http on a permitted local test machine. Test helper prints mode and HTTP mode fails closed; it does not fall back silently. Use a fresh directory, capture exit codes and file hashes. No source changes between runs.

## Remaining mandatory gates

Real HTTP resource loading, cross-browser runs (Firefox, WebKit and actual Safari as applicable), physical devices and real browser zoom, assistive technology and full accessibility checks, complete RU/KK/EN localization and locale-specific data presentation; record exact tested revision and owner visual acceptance. Real API/RBAC/tenant isolation, business journeys and production admission are separate subsequent integration qualification, not implied by mock workspace tests. Full Freeze requires evidence, not a waiver by counting fixture cases.
