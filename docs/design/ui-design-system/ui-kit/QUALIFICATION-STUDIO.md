# RM Business Studio — R1.0K.3–K.7 qualification

2026-11-28 | design/ui-design-system-r1 | prototype / partial qualification

## Scope
Five connected prototype directions. No backend, production API, real authentication, persistent mutation, file transfer, analytics calculations or runtime framework. Form submission and bulk confirmation only produce demo feedback. File selection exposes local metadata only. Chart coordinates use a fixed scale for small nonnegative demo values; generalized domain charts are deferred. Template views are local sections, not actual routes, and not yet merged into the K.2 application shell. The mock adapter simulates a list contract; workspace fencing does not establish real tenant isolation.

## Executed evidence
Chromium 144.0.7559.96 / Playwright, in-memory page fixture with exact CSS and JS inlined and SVG use rewritten to same-document references. 8/8 PASS: 320x640 touch, 375x812 touch, 768x850 touch, 1024x850, 1440x900, 1920x900, 667x320 touch landscape, 320x640 with 200% root font size (not browser zoom). Test: qualify_studio.py. JS syntax check passed.

Assertions: viewport overflow, chart data table and missing-point gap, zero point count, list pagination and sort, select all current page and bulk confirmation, simulated error/retry, drawer to detail, empty search/reset, reversed date range rejection, decimal string normalization, rapid demo workspace changes and stale-response guard, form reset, no JS errors. Mobile screenshot inspected. Tests do not cover all native input permutations, file selection, full keyboard traversal or accessibility. Existing K.1/K.2 tests not rerun; shared runtime asset blobs unchanged.

Verified Git blob equality against tested local files:
- studio.html: d6856bbacfae66db2265f5c5e6eab4d8321e781f
- studio.css: ff27034181e4b24f17dac9b667254b941f60b1b6
- studio.js: 2a40e62385033384c184943f71fc6770b587cf21
- qualify_studio.py: 726fc6380ac2d156d8f5136c71fb27b055236459
- tokens.css: 59d4b95f91c382d7b25e8e0806cac951a3631cc4
- components.css: 40701e4a102ffa6fe256aea6bedc945ab075c34e
- icons.svg: 302d2ec13ffd770f160b167a8c5098aa2737ef24

Run from ui-kit with python3 -m http.server 8000 --bind 127.0.0.1 and open http://127.0.0.1:8000/studio.html. Optional test requires Python Playwright and Chromium; use CHROMIUM_PATH to override default /usr/bin/chromium. HTTP external SVG, Safari, Firefox, real devices, actual browser zoom and accessibility remain open.

Next: qualify and consolidate these prototypes with Application Shell, expand only reusable controls that are needed, and map verified backend API contracts before any real integration. No Production Admission.
