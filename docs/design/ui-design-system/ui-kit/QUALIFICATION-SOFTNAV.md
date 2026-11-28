# RM-UI-DS-R1.0K.10D — Soft Slate navigation

2026-11-28 | baseline c67e828269753a5d7d14a962f249ec4a1066b53a | design/ui-design-system-r1.

Replace shared navigation #142238 with #344C68. Hover #405A76, active #4A6685, text #F0F5FB, muted #D0DCEA, focus #C9DDFF, control border #B6C8DF. Brand mark inherits lighter tokens. Dark workspace background and dark button foreground unchanged; four workspace themes and semantic statuses preserved. OYU geometry/opacity, orbit geometry/opacity and runtime JS unchanged. Shades selected as candidate for owner review.

## Qualification

Chromium, Playwright in-memory fixture. qualify_guardian.py 32/32 PASS exit 0, four themes x two densities x four viewport/text contexts. Added actual sidebar background, nav-muted against gradient endpoint, control border against hover, active text, active white and shield icon contrast. Total 20 semantic pairs plus two explicit active/brand pairs. Examples: main nav text/surface 8.05:1, muted/hover 5.13:1, white/active 5.95:1, control border/hover 4.19:1. qualify_orbit.py 20/20 PASS exit 0, including print/forced colors and endpoint contrast. Taupe desktop screenshot visually inspected. Other suites not rerun. Real HTTP assets, other browsers, physical devices, full accessibility/localization remain open. No Design Freeze or production admission.

Tested changed blobs:
- tokens.css 7901cd16a13d097c0f9fb926691291b8fc286543
- layout.css 3e0d3969409a92a1968abd04f23b11174a4b0b8f
- qualify_guardian.py 61ced87d221cbe7225eeb0cf09565dbf52763992

No backend, CI or runtime dependency changes.
