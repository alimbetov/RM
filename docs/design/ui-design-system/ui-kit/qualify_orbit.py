from pathlib import Path
from playwright.sync_api import sync_playwright
import os
ROOT=Path(__file__).resolve().parent
from qualify_http import open_page
html = (ROOT/'index.html').read_text()

def lum(s):
    s=s.lstrip('#');v=[int(s[i:i+2],16)/255 for i in (0,2,4)]
    return sum(w*(x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4) for x,w in zip(v,(.2126,.7152,.0722)))
def contrast(a,b):
    x,y=sorted((lum(a),lum(b)));return (y+.05)/(x+.05)
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
    print('Chromium',browser.version,flush=True)
    for w,h,touch,scale,forced in [(1440,1000,False,100,'none'),(768,850,True,100,'none'),(375,812,True,100,'none'),(320,640,False,200,'none'),(1440,1000,False,100,'active')]:
        ctx=browser.new_context(viewport={'width':w,'height':h},has_touch=touch,forced_colors=forced,reduced_motion='reduce')
        page=ctx.new_page();page.set_default_timeout(4000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));open_page(page);page.evaluate('(n)=>document.documentElement.style.fontSize=n+"%"',scale)
        orbit=page.locator('.brand-orbit');mark=page.locator('.brand-mark');assert orbit.count()==1 and page.locator('main .brand-orbit').count()==0
        assert orbit.get_attribute('aria-hidden')=='true' and orbit.get_attribute('focusable')=='false'
        if page.locator('#side').is_hidden():page.locator('#nav-toggle').click()
        for theme in ('light','dark','taupe','sage'):
            page.locator('#theme').select_option(theme)
            for density in ('comfortable','compact'):
                page.locator('#density').select_option(density)
                assert orbit.is_visible()==(forced=='none')
                assert orbit.evaluate("e=>{const s=getComputedStyle(e);return s.opacity==='0.38'&&s.pointerEvents==='none'&&s.animationName==='none'&&s.transitionDuration==='0s'}")
                assert page.locator('#side').evaluate("e=>getComputedStyle(e).backgroundImage.includes('radial-gradient')")== (forced=='none')
                assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
                token=lambda n:page.evaluate('(n)=>getComputedStyle(document.documentElement).getPropertyValue("--rm-"+n).trim()',n)
                for fg,bg in [('nav-text','nav-hover'),('nav-muted','nav-hover'),('nav-text','nav-surface'),('nav-muted','nav-surface')]:assert contrast(token(fg),token(bg))>=4.5
                if forced=='none':
                    a,b=orbit.bounding_box(),mark.bounding_box();assert all(abs(a[k]-b[k])<1 for k in ('x','y','width','height'))
                assert not errors,errors
                if w==1440 and density=='comfortable' and forced=='none':page.screenshot(path=str(ROOT/f'orbit-{theme}-desktop.png'),full_page=True)
                if w==375 and density=='comfortable':
                    page.locator('#nav-toggle').click();page.screenshot(path=str(ROOT/f'orbit-{theme}-mobile.png'),full_page=True);page.locator('#nav-toggle').click()
                print('PASS',w,scale,forced,theme,density,flush=True)
        page.emulate_media(media='print');assert not orbit.is_visible();assert page.locator('#side').evaluate("e=>getComputedStyle(e).backgroundImage==='none'")
        ctx.close()
    browser.close()
