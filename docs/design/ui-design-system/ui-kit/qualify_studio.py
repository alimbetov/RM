from pathlib import Path
from playwright.sync_api import sync_playwright
import os
ROOT=Path(__file__).resolve().parent
html=(ROOT/'studio.html').read_text()
for name in ('tokens.css','components.css','studio.css'):
    html=html.replace(f'<link rel="stylesheet" href="{name}">','<style>'+ (ROOT/name).read_text()+'</style>')
html=html.replace('<body>','<body>'+ (ROOT/'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=',1))
html=html.replace('<script src="studio.js"></script>','<script>'+ (ROOT/'studio.js').read_text()+'</script>').replace('icons.svg#','#')
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    for width,height,touch,scale in [(320,640,True,100),(375,812,True,100),(768,850,True,100),(1024,850,False,100),(1440,900,False,100),(1920,900,False,100),(667,320,True,100),(320,640,False,200)]:
        ctx=browser.new_context(viewport={'width':width,'height':height},has_touch=touch);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content(html);page.evaluate('(s)=>document.documentElement.style.fontSize=s+"%"',scale)
        def fit(): assert page.evaluate('document.documentElement.scrollWidth<=innerWidth'),(width,scale,'overflow')
        def status(text): page.wait_for_function('(s)=>document.getElementById("table-status").textContent===s',arg=text)
        def show(view): page.locator('[data-view='+view+']').click();fit()
        fit();assert page.locator('#chart-values tr').count()==5;assert page.locator('#chart-values').text_content().count('Нет данных')==1
        page.locator('#chart-type').select_option('line');assert page.locator('#chart .line').count()==2;assert page.locator('#chart .point').count()==4
        show('list');status('Найдено: 7');assert page.locator('#records tr').count()==3
        page.locator('#all').check();assert page.locator('#records input:checked').count()==3;page.locator('#bulk').click();page.locator('#confirm button[value=ok]').click();page.wait_for_function('document.getElementById("feedback").textContent==="Подтверждено демо: 3"');assert page.locator('#bulk').is_disabled()
        page.locator('#sort').click();status('Найдено: 7');assert 'A-007' in page.locator('#records tr').first.inner_text();page.locator('#next').click();status('Найдено: 7');assert page.locator('#page-info').inner_text()=='Страница 2 из 3'
        page.locator('#fail').click();status('Ошибка демонстрационного адаптера');page.locator('#retry').click();status('Найдено: 7')
        page.locator('#records button').first.click();assert page.locator('#drawer').is_visible();page.locator('#full-detail').click();assert page.locator('#detail').is_visible();fit();show('list')
        page.locator('#q').fill('none');page.locator('#search-form button[type=submit]').click();status('Нет записей');assert page.locator('#next').is_disabled();page.locator('#search-form button[type=reset]').click();status('Найдено: 7')
        show('form');page.locator('#money').fill('12,34');page.locator('#quantity').fill('2');page.locator('#from').fill('2026-11-01');page.locator('#to').fill('2026-10-01');assert not page.locator('#to').evaluate('e=>e.validity.valid');page.locator('#to').fill('2026-11-28');assert page.locator('#to').evaluate('e=>e.validity.valid');page.locator('#business-form button[type=submit]').click();assert '12.34 KZT' in page.locator('#feedback').inner_text();fit()
        page.evaluate("document.getElementById('tenant').value='A';document.getElementById('tenant').dispatchEvent(new Event('change'));document.getElementById('tenant').value='B';document.getElementById('tenant').dispatchEvent(new Event('change'))");show('list');status('Найдено: 4');assert 'A-' not in page.locator('#records').inner_text();assert 'B-' in page.locator('#records').inner_text();assert page.locator('#money').input_value()=='';assert not errors,errors
        if width in (375,1440) and scale==100: show('dashboard');page.screenshot(path=str(ROOT/f'studio-{width}.png'),full_page=True)
        ctx.close();print('PASS',width,height,touch,scale,flush=True)
    print(browser.version);browser.close()
