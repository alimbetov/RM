from pathlib import Path
from playwright.sync_api import sync_playwright
import os

ROOT = Path(__file__).resolve().parent
html = (ROOT / 'index.html').read_text()
for name in ('tokens.css', 'components.css', 'layout.css'):
    html = html.replace(f'<link rel="stylesheet" href="{name}">', '<style>' + (ROOT / name).read_text() + '</style>')
html = html.replace('<body>', '<body>' + (ROOT / 'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=', 1))
for name in ('catalog.js', 'unified.js'):
    html = html.replace(f'<script src="{name}"></script>', '<script>' + (ROOT / name).read_text() + '</script>')
html = html.replace('icons.svg#', '#')

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH', '/usr/bin/chromium'), args=['--no-sandbox'])
    for preference in ('no-preference', 'reduce'):
        context = browser.new_context(viewport={'width': 375, 'height': 812}, has_touch=True, reduced_motion=preference)
        page = context.new_page()
        page.set_default_timeout(5000)
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.set_content(html)
        reduced = preference == 'reduce'
        def style(selector, prop, pseudo=None):
            return page.locator(selector).evaluate('(e, a) => getComputedStyle(e, a[1])[a[0]]', [prop, pseudo])
        def animation(selector, name, duration, pseudo=None):
            assert style(selector, 'animationName', pseudo) == ('none' if reduced else name)
            assert style(selector, 'animationDuration', pseudo) == ('0s' if reduced else duration)
        assert style('#nav-toggle', 'transitionDuration') == ('0s' if reduced else '0.1s, 0.1s, 0.1s')
        page.locator('#nav-toggle').focus()
        page.keyboard.press('Enter')
        assert page.locator('#nav-toggle').get_attribute('aria-expanded') == 'true'
        animation('#side nav', 'rm-nav-in', '0.16s')
        page.keyboard.press('Escape')
        assert page.locator('#side').is_hidden()
        assert page.locator('#nav-toggle').evaluate('e=>e===document.activeElement')
        page.locator('#nav-toggle').click()
        page.locator('[data-view=catalog]').click()
        page.locator('#open-dialog').focus()
        page.keyboard.press('Enter')
        assert page.locator('#demo-dialog').is_visible()
        animation('#demo-dialog', 'rm-dialog-in', '0.16s')
        animation('#demo-dialog', 'rm-backdrop-in', '0.16s', '::backdrop')
        page.keyboard.press('Escape')
        assert page.locator('#demo-dialog').is_hidden()
        assert page.locator('#open-dialog').evaluate('e=>e===document.activeElement')
        page.locator('#nav-toggle').click()
        page.locator('[data-view=list]').click()
        page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
        page.locator('#records button').first.click()
        assert page.locator('#drawer').is_visible()
        animation('#drawer', 'rm-drawer-in', '0.2s')
        page.keyboard.press('Escape')
        assert page.locator('#drawer').is_hidden()
        # Extend only the mock adapter for observing a real pending state; no runtime delays added.
        page.evaluate("""() => { const old=adapter.list; adapter.list=async args=>{await new Promise(r=>setTimeout(r,2500));return old(args);}; load(); }""")
        assert page.locator('#registry-region').get_attribute('aria-busy') == 'true'
        assert page.locator('#table-status').inner_text() == 'Загрузка…'
        animation('#table-status', 'rm-loading-turn', '0.65s', '::before')
        assert style('#table-status', 'animationIterationCount', '::before') == ('1' if reduced else '3')
        assert style('#table-status', 'display', '::before') == 'inline-block'
        page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
        assert page.locator('#registry-region').get_attribute('aria-busy') == 'false'
        assert style('#table-status', 'content', '::before') == 'none'
        page.locator('#nav-toggle').click()
        page.locator('[data-view=dashboard]').click()
        for selector in ('#metric', '#chart', '.metric', '.value'):
            assert page.locator(selector).evaluate_all("es=>es.every(e=>getComputedStyle(e).animationName==='none')")
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        assert not errors, errors
        print('PASS motion', preference, flush=True)
        context.close()
    browser.close()
