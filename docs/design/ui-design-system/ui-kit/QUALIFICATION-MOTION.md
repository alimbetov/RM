# RM-UI-DS-R1.0K.9A-M — Functional Motion

2026-11-28 | design/ui-design-system-r1 | candidate / partial browser qualification

## Scope
CSS-only micro-interactions; no new components, SVG identities, runtime dependencies or business logic. Four durations in tokens.css: 100ms control color transitions, 160ms dialog/menu entry, 200ms drawer entry, 650ms per loading turn (three iterations maximum, 1.95s). Loading text remains present after rotation ends until actual completion. No artificial runtime delay. Reduced-motion disables animations and transitions, retains static loading indicator. No animated financial values, chart data or table rows. Native dialog closing and focus restoration remain independent of animation; no delayed dismissal. Transforms are 6px vertical dialog, 8px horizontal drawer, 4px vertical mobile nav. Backdrop fades in only. No global page or decorative animations.

## Evidence
Chromium 144.0.7559.96 / Playwright, in-memory fixture with inlined source and same-document SVG references.
- Existing qualify_unified.py printed all 15 viewport PASS results plus resize/focus PASS, but container command reached its timeout around teardown; exit status was not independently confirmed. All 16 scenario assertions reported PASS.
- New qualify_motion.py completed with exit code 0: 2/2 PASS for no-preference and reduce at 375x812 touch.
- Dedicated assertions: computed animation/transition names/durations, three-iteration loading cap, static reduced-motion indicator, loading text and aria-busy, disappearance of indicator on completion, Escape and dialog/menu focus, no KPI/chart animation, no document horizontal overflow, no JS exceptions. Test-only adapter delay allows pending state observation; no production delay added. Initial test locator was too broad for multiple metric nodes; corrected to evaluate all matching elements and reran successfully.
- Exact Git blob equality verified against local tested CSS and motion test:
  - tokens.css c6ef95e8234559a3adb4bcae6e58a5283588b4e7
  - components.css 8caf8092886b69d78ae927a764f1cbab3c212f52
  - layout.css 829c286cca12af9dd4302da3e380e6a581c3ee53
  - qualify_motion.py a5fcade2c56f1dfe5ac07df30eec0aef4ecb4b32

Run python3 qualify_motion.py with Python Playwright and Chromium; CHROMIUM_PATH overrides binary path. Actual HTTP sprite loading, Safari/Firefox, physical devices, real browser zoom, frame-rate measurement and full accessibility remain open. No claim of performance or WCAG certification. No Design Freeze or Production Admission.
