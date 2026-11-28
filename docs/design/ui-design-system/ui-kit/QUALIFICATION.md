# RM-UI-DS-R1.0K.1 — Responsive and native SVG qualification

2026-11-28 | design/ui-design-system-r1 only | candidate / partial browser qualification.

## Evidence actually executed

Chromium 144.0.7559.96, local Playwright, in-memory fixture (not served page). Direct navigation to loopback HTTP server was blocked by environment with ERR_BLOCKED_BY_ADMINISTRATOR. The successful fixture inlined the exact CSS, JS and sprite source and changed external use references to same-document references. Thus layout and glyph geometry were exercised, but external SVG loading and HTTP behavior were not. Source assets matched the staged Git tree by blob SHA:

| Asset | Git blob SHA |
|---|---|
| tokens.css | 59d4b95f91c382d7b25e8e0806cac951a3631cc4 |
| components.css | 40701e4a102ffa6fe256aea6bedc945ab075c34e |
| index.html | 99288cd06bbfd277d53536999ecc65950eab683c |
| catalog.js | 92356b29e5e66b974757c54af995568920200c16 |
| icons.svg | 4fc437227e0131baa1d29181d282cb710c38effc |

| Scenarios | Result |
|---|---|
| 320, 375, 480, 600, 767, 768, 820, 1023, 1024, 1280, 1440, 1920 CSS px, height 850 | 12/12 PASS |
| 667x320 and 1024x768 with touch emulation | 2/2 PASS |
| 320x640 with root font size 200% (not browser zoom) | 1/1 PASS |
| Total | 15/15 PASS |

In each case: document width <= viewport; 43 catalog icons with nonempty SVG bounding boxes, no JS page errors, disabled button/input, table scroll reaches last column, dialog opens via Enter, fits viewport, Escape closes and restores focus. Touch/narrow checks sampled icon button width and name input height >=48px. XML structural checks: 43 unique symbol IDs, 24x24 viewBox, SVG/symbol/path and allowlisted attributes only; catalog and HTML references resolve to known IDs. Screenshots of icon catalog and 375px metric cards reviewed locally. No claim of all targets, full keyboard traversal, localization or screen-reader certification.

Sample contrast ratios computed from token values: white/primary 7.36, text/white 15.32, muted/white 7.58, success 6.33, warning 6.21, danger 6.45, info 6.38, control-border/white 4.76, focus/white 6.11. This is not a complete WCAG audit.

## Launch reference

From ui-kit directory run `python3 -m http.server 8000 --bind 127.0.0.1` then visit http://127.0.0.1:8000/. Same-origin HTTP is required for external SVG use; file:// can block external symbols. No production runtime package or build step. The optional reproduce script qualify.py is test-only, expects Python Playwright and /usr/bin/chromium, and recreates the in-memory fixture. On macOS adapt the Chromium executable path for local installation.

## Remaining admission

Real same-origin HTTP external sprite render and currentColor inheritance; keyboard traversal and 200% actual browser zoom; Safari/Firefox and physical touch devices; full visual and contrast review, 16/20/24/32px glyph legibility, long localized strings and accessible names. No claim of WCAG conformance or production readiness. Do not add CI dependencies or modify base_dev/main. Next step: close these checks, then add missing navigation and filters only when required. All palette and glyph style decisions remain candidate defaults pending owner visual review.
