from pathlib import Path
from playwright.sync_api import sync_playwright
import os
ROOT=Path(__file__).resolve().parent
html=(ROOT/'index.html').read_text()
for name in ('tokens.css','components.css','layout.css'):
    html=html.replace(f'<link rel="stylesheet" href="{name}">','<style>'+ (ROOT/name).read_text()+'</style>')
html=html.replace('<body>','<body>'+ (ROOT/'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=',1))
for name in ('catalog.js','unified.js'):
    html=html.replace(f'<script src="{name}"></script>','<script>'+ (ROOT/name).read_text()+'</script>')
html=html.replace('icons.svg#','#')

def luminance(color):
    color=color.lstrip('#');color=color*2 if len(color)==1 else color
    if len(color)==3: color=''.join(c*2 for c in color)
    channels=[int(color[i:i+2],16)/255 for i in (0,2,4)]
    linear=[c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4 for c in channels]
    return sum(c*w for c,w in zip(linear,(.2126,.7152,.0722)))

def contrast(a,b):
    x,y=sorted([luminance(a),luminance(b)]);return (y+.05)/(x+.05)

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    print('Chromium',browser.version,flush=True)
    for width,height,touch,scale in [(375,812,True,100),(768,850,True,100),(1440,1000,False,100),(320,640,False,200)]:
        for theme in ('light','dark'):
            context=browser.new_context(viewport={'width':width,'height':height},has_touch=touch,reduced_motion='reduce')
            page=context.new_page();page.set_default_timeout(4500);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content(html);page.evaluate('(n)=>document.documentElement.style.fontSize=n+"%"',scale)
            def menu():
                if page.locator('#side').is_hidden():page.locator('#nav-toggle').click()
            def go(view):
                menu();page.locator(f'[data-view={view}]').click();page.wait_for_function('(v)=>!document.getElementById(v).hidden',arg=view)
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth'),(width,theme,view)
            def token(name):return page.evaluate('(name)=>getComputedStyle(document.documentElement).getPropertyValue("--rm-"+name).trim()',name)
            menu();page.locator('#theme').select_option(theme)
            assert page.locator('html').get_attribute('data-theme')==theme
            for a,b,threshold in [('text','surface',4.5),('text-muted','surface',4.5),('text-muted','canvas',4.5),('on-primary','primary',4.5),('on-primary','primary-hover',4.5),('success','success-bg',4.5),('warning','warning-bg',4.5),('danger','danger-bg',4.5),('info','info-bg',4.5),('text-muted','neutral-bg',4.5),('disabled-text','disabled-bg',4.5),('control-border','surface',3),('focus','canvas',3),('nav-text','nav-surface',4.5),('nav-muted','nav-surface',4.5),('nav-text','nav-hover',4.5),('nav-focus','nav-active',3)]:
                ratio=contrast(token(a),token(b));assert ratio>=threshold,(theme,a,b,ratio)
            for density in ('comfortable','compact'):
                menu();page.locator('#density').select_option(density);assert page.locator('html').get_attribute('data-density')==density
                go('dashboard');assert page.locator('.guardian-panel .badge.success').count()==0
                assert 'Нет подтверждения' in page.locator('.guardian-panel').inner_text()
                page.locator('#protection-details summary').click();assert page.locator('#protection-details').get_attribute('open') is not None
                assert page.locator('.guardian-checks dd').all_inner_texts()==['Не подключено']*3
                page.locator('#chart-details summary').click();assert page.locator('#chart-values').inner_text().count('Нет данных')==1
                assert 'P2\t0' in page.locator('#chart-values').inner_text()
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
                page.locator('#protection-details summary').click();page.locator('#chart-details summary').click()
                if width in (375,1440):page.screenshot(path=str(ROOT/f'guardian-{theme}-{density}-{width}.png'),full_page=True)
                go('list');page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
                assert page.locator('#records tr').count()==3
                go('form');h=page.locator('#money').bounding_box()['height'];assert h>= (48 if width<768 or touch else 32 if density=='compact' else 40),(width,theme,density,h)
                go('catalog');assert page.locator('#icon-grid .rm-icon').count()==56
                page.locator('#open-dialog').click();assert page.locator('#demo-dialog').is_visible();assert page.locator('#demo-dialog').evaluate("e=>getComputedStyle(e).backgroundColor===getComputedStyle(document.querySelector('.guardian-panel')).backgroundColor")
                page.keyboard.press('Escape');assert page.locator('#open-dialog').evaluate('e=>e===document.activeElement')
                go('dashboard');page.locator('#protection-details summary').click();page.locator('#chart-details summary').click();page.locator('#tenant').select_option('B');page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
                assert page.locator('#protection-details').get_attribute('open') is None and page.locator('#chart-details').get_attribute('open') is None
                assert page.locator('html').get_attribute('data-theme')==theme and page.locator('html').get_attribute('data-density')==density
                page.locator('#tenant').select_option('A');page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
                assert not errors,errors
                print('PASS',width,scale,theme,density,flush=True)
            context.close()
    browser.close()
