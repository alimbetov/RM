# RM-UI-DS-R1.0K.10C — Taupe and Sage

2026-11-28 | baseline 421d0d7cd6b6f0c508a36b2d4e26fa70dbc265d2 | design/ui-design-system-r1.

Two new light presentation variants only: Taupe and Sage. Seven semantic surface token overrides each, two new options in existing theme selector. Light default unchanged. Shared text/actions/statuses, Deep Navy navigation, orbital brand, OYU, density, accessibility behavior and existing JS preserved. Page-local preference, no persistence changes. Candidate palette pending visual acceptance.

## Qualification

Chromium 144.0.7559.96, Playwright in-memory fixture. Expanded qualify_guardian.py: 32/32 PASS, process exit 0. Four themes x two densities x four contexts (375 touch, 768 touch, 1440 fine pointer, 320 with 200% root text). Existing scenario assertions retained: disclosure, true zero/missing, no false protection status, registry, form control height, 56 icons, dialog surface/focus, workspace switch preference retention and disclosure reset, no document overflow or JS exceptions. Added actual body canvas and invariant navy/primary/status token checks. Existing 17 contrast pairs per theme passed. Desktop Taupe and Sage screenshots visually inspected. Mobile screenshots generated. Other suites (unified, motion, OYU, orbit) not rerun in this increment; prior evidence scoped to their source. Actual HTTP asset loading, other browsers, physical devices, full accessibility and locale qualification remain open. No Design Freeze or Production Admission.

Tested source blobs:
- tokens.css 9d6c191e9b38bad850b41516208ad464b800db0a
- index.html 7a01fe8ea99e183ad20ec5b5d516491d1d4a5905
- qualify_guardian.py b753a5962851e718c8373ec8d1754e662ef34eb5

Run python qualify_guardian.py with Playwright and Chromium. No new runtime dependencies, no backend or CI changes.
