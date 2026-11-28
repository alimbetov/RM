# RM-UI-DS-R1.0K.10A — RM OYU Soft Jiek

2026-11-28 | Baseline 533dcbb879ac4184ab6320ed54cd592f89b8a48f | design/ui-design-system-r1

One inline SVG with three independently drawn rounded cubic Bézier motifs, inspired by traditional Kazakh border ornament. No commercial vector copied, no new functional icons, no external asset, no JS, no new dependency. Existing muted navy navigation color at 14% opacity, 156px maximum width, separate flow after sidebar note. Hidden below 1024px width or 720px height, in forced colors and print. No animation, no pointer interaction, no focus, aria-hidden. Working area remains undecorated. Exact placement, opacity and thresholds are implementation candidates subject to owner visual review.

Chromium 144.0.7559.96, Playwright in-memory source fixture, qualify_oyu.py: 28/28 PASS, exit 0. Seven viewport/text/forced-color contexts × two themes × two densities. Checked single strip, cubic-only path commands, no main-area ornament, accessibility attributes, visibility policy, opacity, no motion/pointer events, placement below note, bounds, document overflow and no JS exceptions. Light desktop screenshot inspected. Print hiding is specified by CSS but not separately exercised. Full preceding 34-scenario suite not rerun; prior evidence remains scoped to prior SHA. Real HTTP assets, other browsers, devices and full accessibility remain open; no Design Freeze.

Tested blobs:
- index.html e0f4ca85baeb0ed7fadce28db4b14f1a1b5d532e
- layout.css ee710200237884840aa261149d63181418d1393b
- qualify_oyu.py 3e785274d91496d6b0895d5b0447fa818a2af6fb

Run python qualify_oyu.py with Playwright and Chromium. No backend or CI changes.
