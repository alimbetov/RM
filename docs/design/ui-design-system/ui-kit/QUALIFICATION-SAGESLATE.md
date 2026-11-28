# RM-UI-DS-R1.0K.10E — Sage Slate

2026-11-28 | baseline 58685ff2baa860bb2f34c4019247d1bbb71ac949 | design/ui-design-system-r1.

Owner approved shared navigation base #536B68. Hover #4A625E, active #59716E, main text #F5F8F5, muted #E5ECE7, focus #C9DDFF, control border #C2D6CC. Supporting shades chosen by implementation for contrast. Darker hover gradient endpoint, modestly lighter active surface with existing inset focus-colored marker. The original preview's lighter active candidate #68807C is not used. All four workspace palettes and primary/status colors unchanged. Existing OYU and Orbital geometry, opacity and runtime unchanged.

## Qualification

Actual local Chromium/Playwright in-memory test fixture: qualify_guardian.py 32/32, qualify_orbit.py 20/20, qualify_oyu.py 28/28; all exit 0. Guardian covers all four themes × two densities × four viewport/text contexts, semantic contrast pairs, actual sidebar background, registry, form, dialog, disclosures, zero/missing, workspace switching and document overflow. Orbital and OYU suites retain their original Light/Dark coverage and decorative/accessibility assertions. Selected contrast ratios: main nav text/base 5.35:1, muted/base 4.76:1, muted/hover 5.46:1, white/active 5.23:1, main text/active 4.89:1, shield icon/active 3.19:1. Screenshots generated for four themes at 1440 and 375 CSS px. No claim of complete cross-browser/WCAG qualification. Unified and motion suites not rerun; real HTTP asset loading, other browsers/devices and full localization/accessibility remain open. Design Freeze pending.

Tested changed blobs:
- tokens.css 2180bda8a2975ce4fb605fa2fc2f27e3ed8f3664
- qualify_guardian.py 5ac31eda684747fe1e6df317679d932627158dbb

No new dependencies, backend, JS, HTML or CI changes.
