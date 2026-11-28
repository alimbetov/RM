from pathlib import Path
from playwright.sync_api import sync_playwright
from xml.etree import ElementTree as ET
import os,re
ROOT=Path(__file__).resolve().parent
from qualify_http import open_page
html = (ROOT/'index.html').read_text()

ids=re.findall(r'\bid="([^"]+)"',html); assert len(ids)==len(set(ids))
ns={'s':'http://www.w3.org/2000/svg'}; symbols=ET.parse(ROOT/'icons.svg').getroot().findall('s:symbol',ns); assert len(symbols)==56
assert len({s.attrib['id'] for s in symbols})==56
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    print('Chromium',browser.version,flush=True)
    for w,h,touch,scale in [(w,850,False,100) for w in (320,375,480,600,767,768,820,1023,1024,1280,1440,1920)]+[(667,320,True,100),(1024,768,True,100),(320,640,False,200)]:
        ctx=browser.new_context(viewport={'width':w,'height':h},has_touch=touch); page=ctx.new_page(); page.set_default_timeout(4500); errors=[];page.on('pageerror',lambda e:errors.append(str(e)));open_page(page);page.evaluate('(s)=>document.documentElement.style.fontSize=s+"%"',scale)
        def fit(): assert page.evaluate('document.documentElement.scrollWidth<=innerWidth'),(w,scale,'overflow')
        def status(s): page.wait_for_function('(s)=>document.getElementById("table-status").textContent===s',arg=s)
        def go(v):
            if w<768 and page.locator('#side').is_hidden(): page.locator('#nav-toggle').click()
            page.locator('[data-view='+v+']').click();page.wait_for_function('(v)=>!document.getElementById(v).hidden',arg=v);fit();assert page.locator('[aria-current=page][data-view]').count()==1
        fit(); assert page.locator('#chart-values tr').count()==5
        page.locator('#chart-type').select_option('line');assert page.locator('#chart .line').count()==2;assert page.locator('#chart .point').count()==4
        if w<768:
            page.locator('#nav-toggle').focus();page.keyboard.press('Enter');assert page.locator('#side').is_visible();page.keyboard.press('Escape');assert page.locator('#side').is_hidden();assert page.locator('#nav-toggle').evaluate('e=>e===document.activeElement')
        go('list');status('Найдено: 7');
        if w<768:
            page.locator('#nav-toggle').click();page.locator('[data-view=list]').click();assert page.locator('#side').is_hidden()
        page.locator('.skip-link').focus();page.keyboard.press('Enter');assert page.locator('#list').is_visible();assert page.locator('#content').evaluate('e=>e===document.activeElement')
        assert page.locator('#heading').inner_text()=='Реестр';assert page.locator('#crumb').inner_text()=='Реестр'
        page.locator('#tab-list').focus();page.keyboard.press('ArrowRight');assert page.locator('#pane-summary').is_visible();page.keyboard.press('Home');assert page.locator('#pane-list').is_visible()
        page.locator('#all').focus();page.keyboard.press('Space');assert page.locator('#all').evaluate('e=>e===document.activeElement');assert page.locator('#records input:checked').count()==3
        page.locator('#bulk').click();page.locator('#confirm button[value=ok]').click();page.wait_for_function('document.getElementById("feedback").textContent==="Подтверждено демо: 3"');assert page.locator('#bulk').is_disabled()
        page.locator('#sort').click();status('Найдено: 7');assert 'A-007' in page.locator('#records tr').first.inner_text();page.locator('#next').click();status('Найдено: 7');assert page.locator('#page-info').inner_text()=='Страница 2 из 3'
        page.locator('#fail').click();status('Ошибка демонстрационного адаптера');assert page.locator('#prev').is_disabled() and page.locator('#next').is_disabled();assert page.locator('#total').text_content()=='—';page.locator('#retry').click();status('Найдено: 7')
        page.locator('#state').select_option('ready');page.locator('#search-form button[type=submit]').click();status('Найдено: 4');page.locator('#q').fill('none');page.locator('#search-form button[type=submit]').click();status('Нет записей');assert page.locator('#total').text_content()=='0';page.locator('#search-form button[type=reset]').click();status('Найдено: 7')
        page.locator('#records button').first.click();assert page.locator('#drawer').is_visible();page.locator('#full-detail').click();page.wait_for_function("!document.getElementById('detail').hidden");fit()
        go('form');page.locator('#money').fill('12,34');page.locator('#quantity').fill('2');page.locator('#from').fill('2026-11-01');page.locator('#to').fill('2026-10-01');assert not page.locator('#to').evaluate('e=>e.validity.valid');page.locator('#to').fill('2026-11-28');page.locator('#business-form button[type=submit]').click();assert '12.34 KZT' in page.locator('#feedback').inner_text();fit()
        go('catalog');assert page.locator('#icon-grid .rm-icon').count()==56;page.wait_for_function("[...document.querySelectorAll('#icon-grid .rm-icon')].every(e=>e.getBBox().width>0&&e.getBBox().height>0)");page.locator('#open-dialog').focus();page.keyboard.press('Enter');assert page.locator('#demo-dialog').is_visible();page.keyboard.press('Escape');assert page.locator('#open-dialog').evaluate('e=>e===document.activeElement');fit()
        page.evaluate("for(const t of ['A','B']){document.getElementById('tenant').value=t;document.getElementById('tenant').dispatchEvent(new Event('change'));}");go('list');status('Найдено: 4');assert 'A-' not in page.locator('#records').inner_text();assert 'B-' in page.locator('#records').inner_text();assert page.locator('#money').input_value()=='';assert page.locator('#state').input_value()=='';assert not errors,errors
        if w in (375,1440) and scale==100: go('dashboard');page.screenshot(path=str(ROOT/f'unified-{w}.png'),full_page=True)
        ctx.close();print('PASS',w,h,touch,scale,flush=True)
    page=browser.new_page(viewport={'width':1280,'height':800});open_page(page);page.locator('[data-view=list]').focus();page.set_viewport_size({'width':375,'height':800});page.wait_for_function("document.getElementById('side').hidden");assert page.locator('#nav-toggle').evaluate('e=>e===document.activeElement');page.locator('#nav-toggle').click();page.set_viewport_size({'width':1280,'height':800});page.wait_for_function("document.getElementById('nav-toggle').hidden");assert page.locator('#heading').evaluate('e=>e===document.activeElement');print('PASS resize/focus',flush=True);browser.close()
