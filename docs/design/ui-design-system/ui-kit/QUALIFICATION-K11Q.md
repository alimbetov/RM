# RM-UI-DS-R1.0K.11Q — Browser, Accessibility & Localization Admission

Date 2026-11-28 | design/ui-design-system-r1 | parent 557730ccbbc7c728d24476a9a2c340a985010a8c.

## Decision

Visual baseline remains APPROVED. Technical Design Freeze HOLD; Production Admission HOLD. No new decorative elements, theme/style changes, runtime dependencies, production API, backend, CI or business permissions changes.

## Actual implementation

- One page-local native locale select (RU/KK/EN), independent of theme, density and workspace. 181 UI-owned dictionary entries cover static labels, titles, accessible names, placeholders, dynamic status/error/feedback, demo record text and chart missing-data labels. Stable IDs, option values, synthetic data and user-entered values are not translated. Language switch preserves selected rows and form values and does not trigger a workspace change or request reload. Demo search recognizes all three localized names. Workspace switch clears the existing business state while retaining presentation preferences. RU is default; language preference is page-local, not persisted.
- Exact money display uses BigInt for the integer part, original fractional digits and locale formatting; no conversion of an authoritative monetary value to floating point. For unavailable locale data, explicit grouping/decimal fallback: RU/KK space/comma, EN comma/point. The environment's Chromium lacks kk-KZ number data and otherwise silently resolves en-US. The fallback and separator oracle address this defect. Currency remains KZT in the existing fixture; not a multi-currency or server calculation qualification.
- Explicit accessible name for page-select checkbox and stable values for catalog status options. Keyboard drawer entry, Escape, focus return and representative accessibility tree names tested. Existing compact/comfortable and touch target rules unchanged.
- Browser probe qualify_k11q.py accepts RM_UI_ENGINE=chromium|firefox|webkit, optional RM_UI_BROWSER_PATH, and the existing fail-closed HTTP mode. Eight actual asset response statuses checked by HTTP mode; fixture mode remains explicitly distinguishable.

## Actual test evidence

Chromium 144.0.7559.96 / Playwright 1.57.0, final source, fixture mode. All seven processes exit 0.

| Suite | Executed passing groups |
|---|---:|
| qualify_unified.py | 16 |
| qualify_motion.py | 2 |
| qualify_guardian.py | 32 |
| qualify_orbit.py | 40 |
| qualify_oyu.py | 56 |
| qualify_boundaries.py | 32 |
| qualify_k11q.py | 32 |
| Total | 210 |

K11Q: 24 locale/browser/accessibility groups (3 locales x 4 themes x 2 viewport contexts, 375 touch and 1440) and 8 language/workspace async groups. Exercises five views, selected-row preservation, translated status and dates' application validation message, actual decimal digits and grouping/decimal separators, zero versus missing, named registry controls in accessibility tree snapshot, keyboard drawer and dialog close, retry and tenant fixture state reset. Counts represent executed groups, not all possible combinations. Existing RU suites retain original scope. Full screen-reader/WCAG, real zoom and physical devices are not implied.

Independent HTTP server test using Python urllib: eight responses 200, bytes equal original files, appropriate MIME (HTML, CSS, JS, SVG), exit 0. This is **server delivery only**. Actual Chromium HTTP navigation attempted on final source, exit 1: net::ERR_BLOCKED_BY_ADMINISTRATOR. Firefox and WebKit probes exit 1: binaries not installed. No bypass, no browser HTTP or cross-browser PASS. Native browser date picker, file chooser and native validation UI depend on browser/OS language. Linguistic review of KK/EN by qualified speakers is pending; full product localization is outside the static kit. The probe does not substitute for accessibility audit or actual Safari.

## Exact source manifest (Git blob SHA-1)

| Source | Blob |
|---|---|
| index.html | 73c9dc1b139da2e081c52e873c6c3aa711e6b50a |
| tokens.css | 2180bda8a2975ce4fb605fa2fc2f27e3ed8f3664 |
| components.css | dd1a3717b3eb59e36679b422b9bd838beeccd863 |
| layout.css | 09c65f3568029933eb842c2f59c77bec8dcab736 |
| icons.svg | 302d2ec13ffd770f160b167a8c5098aa2737ef24 |
| i18n.js | 58102561382cd9525bc8bbe876257fa3214a95c3 |
| catalog.js | d2baa0066535d95b476a43adac02a9667ce420bf |
| unified.js | 0b207ae8c91e71d4e8cae22412304a14b1d9b82c |
| qualify_boundaries.py | f731d166a7d2364c182829a12c2788652fd0f830 |
| qualify_guardian.py | bebe0b22cab16305c48bc7e0c6fcdc23b94becb8 |
| qualify_http.py | 3a469c8ee5fbcc0f1ab52640c2b5057a3aed228e |
| qualify_k11q.py | 8d72937c5b137ddb6404ec05616c4b7c2f270a92 |
| qualify_motion.py | 6a9cd214fdc3ecd0c5d6ab5cd73a4e60454bd23f |
| qualify_orbit.py | a3d2bd66f1992eb56ea4010b77a7397409edcb8e |
| qualify_oyu.py | 62b81012066bcf45a0e1d713bca302a5b059c444 |
| qualify_unified.py | c01bb38981e8e5d06e88a5e2b2a03237cfc1b34f |

## Reproduction on permitted machine

From ui-kit, activate a Python virtual environment; install playwright==1.57.0 and the matching browser binaries using python -m playwright install chromium firefox webkit. On macOS/Linux select the Playwright Chromium binary explicitly:

```sh
export CHROMIUM_PATH="$(python -c 'from playwright.sync_api import sync_playwright; p=sync_playwright().start(); print(p.chromium.executable_path); p.stop()')"
export RM_UI_TEST_MODE=http
for suite in unified motion guardian orbit oyu boundaries k11q; do
  python "qualify_$suite.py" || exit 1
done
RM_UI_ENGINE=firefox python qualify_k11q.py
RM_UI_ENGINE=webkit python qualify_k11q.py
```

Capture exact SHA, engine/version, stdout, exit codes. Actual Safari is a separate manual check, not equivalent to Playwright WebKit. Serve using python -m http.server 8000 --bind 127.0.0.1; review actual Safari/Chrome/Firefox on macOS, supported devices, 200%/400% browser zoom/reflow, keyboard and VoiceOver/NVDA (where applicable), live region announcements, focus order, chart accessible alternative, manual WCAG checklist and linguistic acceptance. Fix defects before declaring technical Freeze. No production admission follows solely from UI qualification.
