# RM-UI-DS-R1.0K.2 — Application Shell and extended SVG icons

2026-11-28 | design/ui-design-system-r1 only | candidate / partial browser qualification

Implemented a single navigation DOM with 10 example sections. Desktop sidebar >=1024px; wrapping tablet navigation 768–1023px; inline collapsible mobile navigation <768px. Header, workspace selector, breadcrumb, tabs, native dropdown, search/status filters, reset, pagination and empty result. Demo records are synthetic, shared across demo workspaces. No real navigation routes, authorization, tenant data or APIs. No runtime dependency or CI change.

SVG: 13 additions, 56 total, all previous IDs retained. The more glyph originally had zero geometric bounding-box height and blocked the regression check. Replaced dot segments with closed circles; the subsequent catalog regression passed.

## Actual tests

Chromium 144.0.7559.96, Playwright, in-memory fixture. Direct loopback HTTP, intercepted HTTPS navigation and file navigation were blocked by ERR_BLOCKED_BY_ADMINISTRATOR. Source CSS, JS and sprite inlined; external use references replaced with same-document references. Therefore external HTTP sprite behavior is NOT qualified.

- Shell: 15/15 viewport cases PASS (320,375,480,600,767,768,820,1023,1024,1280,1440,1920 at height 850; touch 667x320 and 1024x768; 320x640 at 200% root font size, not actual browser zoom). Additional desktop/mobile resize and focus scenario PASS.
- Catalog: 15/15 PASS after correcting more glyph, 56 unique 24x24 SVG symbols with catalog bounding boxes. 
- Shell assertions: no document horizontal overflow, one navigation list, disclosure/escape/focus, active section and breadcrumb, tab arrow/home navigation, pagination boundary, filter/reset/empty result, demo workspace selector, visible SVG geometry, no JS exceptions.
- Visual screenshots 375 and 1440 reviewed; corrected header visual/DOM order and table scroll hint, reran shell 16/16. The final more glyph is unused by the shell; catalog test exercised final glyph.
- Test source: qualify_shell.py and qualify.py. Python/Playwright are optional qualification dependencies, not runtime. Launch catalog over local HTTP as documented in QUALIFICATION.md.

Remaining: real same-origin external sprite rendering/currentColor, Safari/Firefox, physical devices, full keyboard/accessibility review, real browser zoom, localization and production API/tenant admission. The mobile disclosure is intentionally inline rather than modal; tablet uses wrapped text navigation. Candidate layout and glyph style pending owner review.
