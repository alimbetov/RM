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
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    print('Chromium',browser.version,flush=True)
    for width,height,touch,scale,forced in [(1440,1000,False,100,'none'),(1024,768,False,100,'none'),(768,850,True,100,'none'),(375,812,True,100,'none'),(1440,600,False,100,'none'),(320,640,False,200,'none'),(1440,1000,False,100,'active')]:
        context=browser.new_context(viewport={'width':width,'height':height},has_touch=touch,forced_colors=forced)
        page=context.new_page();page.set_default_timeout(4000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content(html);page.evaluate('(n)=>document.documentElement.style.fontSize=n+"%"',scale)
        ornament=page.locator('.rm-oyu');assert ornament.count()==1
        assert ornament.get_attribute('aria-hidden')=='true' and ornament.get_attribute('focusable')=='false'
        assert ornament.locator('path').count()==3
        assert ornament.evaluate("e=>[...e.querySelectorAll('path')].every(p=>/^[MC0-9 .-]+$/.test(p.getAttribute('d')))")
        assert page.locator('main .rm-oyu').count()==0
        if page.locator('#side').is_hidden():page.locator('#nav-toggle').click()
        for theme in ('light','dark'):
            page.locator('#theme').select_option(theme)
            for density in ('comfortable','compact'):
                page.locator('#density').select_option(density)
                visible=width>=1024 and height>=720 and forced=='none'
                assert ornament.is_visible()==visible
                assert ornament.evaluate("e=>{const s=getComputedStyle(e);return s.opacity==='0.14'&&s.pointerEvents==='none'&&s.animationName==='none'&&s.transitionDuration==='0s'}")
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
                if visible:
                    bounds=ornament.bounding_box();note=page.locator('.nav-note').bounding_box();side=page.locator('#side').bounding_box()
                    assert bounds['y']>=note['y']+note['height'] and bounds['width']<=156
                    assert bounds['x']>=side['x'] and bounds['x']+bounds['width']<=side['x']+side['width']
                    if width==1440 and density=='comfortable':page.screenshot(path=str(ROOT/f'oyu-{theme}-desktop.png'),full_page=True)
                assert not errors,errors
                print('PASS',width,height,scale,forced,theme,density,flush=True)
        context.close()
    browser.close()
