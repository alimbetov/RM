"""Representative accessible-name snapshot probe, not a screen-reader audit."""
import os
import re
from playwright.sync_api import sync_playwright
from qualify_http import open_page

UNNAMED = re.compile(r'^\s*- (button|checkbox|combobox|textbox|spinbutton|switch|radio|link|slider|searchbox|tab)(?:\s*\[|$|:)')
with sync_playwright() as p:
    engine = os.getenv('RM_UI_ENGINE', 'chromium')
    if engine not in ('chromium', 'firefox', 'webkit'):
        raise ValueError('Unsupported engine')
    kind = getattr(p, engine)
    path = os.getenv('RM_UI_BROWSER_PATH') or (os.getenv('CHROMIUM_PATH') if engine == 'chromium' else None)
    with kind.launch(**({'executable_path': path} if path else {})) as browser:
        print('Engine', engine, browser.version, flush=True)
        page = browser.new_page(viewport={'width':1440, 'height':850})
        open_page(page)
        for lang in ('ru','kk','en'):
            page.locator('#locale').select_option(lang)
            for view in ('dashboard','list','form','detail','catalog'):
                page.locator(f'[data-view={view}]').click()
                page.wait_for_function('(v)=>!document.getElementById(v).hidden', arg=view)
                if view == 'list':
                    page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
                snapshot = page.locator('body').aria_snapshot()
                assert snapshot.strip(), (lang, view, 'empty accessibility snapshot')
                issues = [line for line in snapshot.splitlines() if UNNAMED.search(line)]
                assert not issues, (lang, view, issues)
                print('PASS names', lang, view, flush=True)
