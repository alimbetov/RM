from pathlib import Path
from playwright.sync_api import sync_playwright
import os, json
ROOT=Path(__file__).resolve().parent
html=(ROOT/'shell.html').read_text()
for name in ('tokens.css','components.css','shell.css'):
    html=html.replace(f'<link rel="stylesheet" href="{name}">','<style>'+ (ROOT/name).read_text()+'</style>')
html=html.replace('<body>','<body>'+ (ROOT/'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=',1))
html=html.replace('<script src="shell.js"></script>','<script>'+ (ROOT/'shell.js').read_text()+'</script>').replace('icons.svg#','#')
scenarios=[(w,850,False,100) for w in (320,375,480,600,767,768,820,1023,1024,1280,1440,1920)]+[(667,320,True,100),(1024,768,True,100),(320,640,False,200)]
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    for w,h,touch,scale in scenarios:
        ctx=browser.new_context(viewport={'width':w,'height':h},has_touch=touch);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        page.set_content(html);page.evaluate('(s)=>document.documentElement.style.fontSize=s+"%"',scale)
        check_width=lambda: page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        assert check_width(),(w,'initial overflow')
        assert page.locator('.nav-item').count()==10
        toggle=page.locator('#nav-toggle');side=page.locator('#side')
        if w<768:
            assert side.is_hidden() and toggle.is_visible()
            toggle.focus();page.keyboard.press('Enter');assert side.is_visible() and toggle.get_attribute('aria-expanded')=='true'
            assert check_width(),(w,'open nav overflow')
            page.locator('[data-section=warehouse]').focus();page.keyboard.press('Escape');assert side.is_hidden() and toggle.evaluate('e=>e===document.activeElement')
            toggle.click();assert toggle.bounding_box()['width']>=48
        else: assert side.is_visible() and toggle.is_hidden()
        page.locator('[data-section=orders]').click();assert page.locator('#heading').inner_text()=='Заказы';assert page.locator('#crumb').inner_text()=='Заказы';assert page.locator('.nav-item[aria-current=page]').count()==1
        assert page.locator('#heading').evaluate('e=>e===document.activeElement')
        if w<768: assert side.is_hidden()
        page.locator('#tab-list').focus();page.keyboard.press('ArrowRight');assert page.locator('#pane-summary').is_visible() and page.locator('#tab-summary').get_attribute('aria-selected')=='true';page.keyboard.press('Home');assert page.locator('#pane-list').is_visible()
        assert page.locator('#prev').is_disabled();page.locator('#next').click();assert page.locator('#page-label').inner_text()=='Страница 2 из 3';page.locator('#next').click();assert page.locator('#next').is_disabled() and page.locator('#rows tr').count()==1
        page.locator('#state').select_option('ready');page.locator('#filters button[type=submit]').click();assert page.locator('#result-count').inner_text()=='Найдено: 4' and page.locator('#page-label').inner_text()=='Страница 1 из 2'
        page.locator('#query').fill('no match');page.locator('#filters button[type=submit]').click();assert page.locator('#rows').inner_text()=='Нет записей' and page.locator('#next').is_disabled() and page.locator('#prev').is_disabled()
        page.locator('#filters button[type=reset]').click();assert page.locator('#result-count').inner_text()=='Найдено: 7' and page.locator('#query').input_value()==''
        page.locator('#workspace').select_option(index=1);assert 'Демо B' in page.locator('#shell-status').inner_text()
        assert check_width() and not errors,(w,errors)
        assert page.evaluate("[...document.querySelectorAll('.rm-icon')].filter(e=>e.getClientRects().length).every(e=>e.getBBox().width>0&&e.getBBox().height>0)")
        if w in (375,1440) and scale==100: page.screenshot(path=str(ROOT/f'shell-{w}.png'),full_page=True)
        ctx.close();print('PASS',w,h,touch,scale,flush=True)
    page=browser.new_page(viewport={'width':1280,'height':800});page.set_content(html);page.locator('[data-section=orders]').focus();page.set_viewport_size({'width':375,'height':800});page.wait_for_function("document.getElementById('side').hidden");assert page.locator('#nav-toggle').evaluate('e=>e===document.activeElement');page.locator('#nav-toggle').click();page.set_viewport_size({'width':1280,'height':800});page.wait_for_function("document.getElementById('nav-toggle').hidden");assert page.locator('#side').is_visible() and page.locator('#heading').evaluate('e=>e===document.activeElement');page.set_viewport_size({'width':375,'height':800});page.wait_for_function("document.getElementById('side').hidden");print('PASS resize/focus',flush=True)
    print('Browser',browser.version);browser.close()
