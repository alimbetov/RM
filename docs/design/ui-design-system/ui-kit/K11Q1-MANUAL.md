# K11Q.1 — Manual acceptance record

Baseline UI source: 868377f838f3388a407809a343c572cd40093c05; verify source against K11Q1-MANIFEST.txt.
Record date, reviewer, OS/version, browser/version, device, viewport, zoom and evidence reference for every result. Blank fields mean NOT RUN; a failed criterion is HOLD. Never infer Safari from WebKit or VoiceOver from an accessibility snapshot.

| ID | Required check | Acceptance | Result / evidence |
|---|---|---|---|
| M01 | Safari on macOS, real local HTTP | All five views load, styles/scripts and external SVG appear, no console errors | NOT RUN |
| M02 | Chrome and Firefox on supported desktop OS, real local HTTP | Same checks; compare four themes and density | NOT RUN |
| M03 | Safari on iPhone/iPad and supported Android browser on physical devices | Navigation, touch, rotation, forms, horizontal table scrolling, no document overflow | NOT RUN |
| M04 | Actual browser zoom 200% and 400%, desktop browsers | Text readable, controls reachable, at 320 CSS px reflow without page-wide horizontal scrolling except legitimate local table region | NOT RUN |
| M05 | Safari + VoiceOver on macOS, with keyboard navigation | Landmarks, headings, meaningful control names, focus order, no trap; dialog close and focus return | NOT RUN |
| M06 | VoiceOver: dynamic states | Loading/empty/error/retry, selected rows, validation feedback and missing vs zero announced meaningfully | NOT RUN |
| M07 | VoiceOver: chart alternative | Table values and missing/zero distinct; decorative OYU/orbit not announced | NOT RUN |
| M08 | Accessibility audit, WCAG 2.2 AA target | Manual applicable criteria with explicit evidence, not merely an automated scan; identify any remaining defects | NOT RUN |
| M09 | Linguistic review RU, KK, EN | Review 181 dictionary entries and each view, financial terms, status/evidence wording, error messages, locale numbers; native OS controls scoped separately | NOT RUN |
| M10 | Owner acceptance | Approved visual identity retained; defect list closed and evidence recorded | NOT RUN |

## Automated execution (macOS or Linux)

From ui-kit, with the reviewed source revision checked out:
```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install playwright==1.57.0
python -m playwright install chromium firefox webkit
python qualify_external.py --out "$HOME/rm-k11q1-evidence"
```
Use a new output directory for each run. Archive result.json, manifest.txt and logs. The script uses a temporary copy and forces HTTP. It does not install software, alter security settings, or grant final Freeze. If browser installation is blocked, retain HOLD. For manual local testing, run `python -m http.server 8000 --bind 127.0.0.1` in ui-kit and open http://127.0.0.1:8000/index.html in the real desktop browser. Physical device testing requires a permitted, controlled network test host; loopback on a phone is not the Mac. No production data.

After all mandatory automated and manual checks pass, issue a separately reviewed admission record tied to the final tested source manifest. No UI/API production admission is implied.
