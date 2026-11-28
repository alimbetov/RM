"""Locale expansion and exact-value fixture checks; not a translation certification."""
from playwright.sync_api import sync_playwright
from qualify_http import open_page
import os

LABELS = {
    'ru': ('Долгосрочное планирование и контроль финансовых результатов', 'Сводная информация о движении товарно-материальных запасов'),
    'kk': ('Қаржылық нәтижелерді ұзақ мерзімді жоспарлау және бақылау', 'Тауарлық-материалдық қорлардың қозғалысы туралы жиынтық ақпарат'),
    'en': ('Long-term planning and financial performance monitoring', 'Consolidated inventory movements and reconciliation information'),
}
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH','/usr/bin/chromium'), args=['--no-sandbox'])
    print('Chromium',browser.version,flush=True)
    for width,touch in ((320,True),(1440,False)):
        for theme in ('light','dark','taupe','sage'):
            ctx=browser.new_context(viewport={'width':width,'height':850},has_touch=touch,reduced_motion='reduce')
            page=ctx.new_page();page.set_default_timeout(5000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));open_page(page)
            def menu():
                if page.locator('#side').is_hidden():page.locator('#nav-toggle').click()
            def go(view):
                menu();page.locator(f'[data-view={view}]').click();page.wait_for_function('(v)=>!document.getElementById(v).hidden',arg=view)
            def fit():assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            menu();page.locator('#theme').select_option(theme);go('dashboard')
            for locale,labels in LABELS.items():
                page.evaluate('([locale,labels])=>{document.documentElement.lang=locale;document.getElementById("heading").textContent=labels[0];document.getElementById("crumb").textContent=labels[1];document.querySelector(".nav-item span").textContent=labels[1];}',[locale,labels]);menu();fit()
                if width<768:page.locator('#nav-toggle').click()
                page.locator('#chart-type').select_option('line');assert page.locator('#chart .line').count()==2
                assert page.locator('#chart-values td').all_text_contents()==['P1','12','P2','0','P3','Нет данных','P4','18','P5','14']
                fit();print('PASS locale expansion',width,theme,locale,flush=True)
            go('form');money=page.locator('#money');money.fill('-1');assert not money.evaluate('e=>e.validity.valid');money.fill('12.345');assert not money.evaluate('e=>e.validity.valid');money.fill('123456789012345.67');assert money.evaluate('e=>e.validity.valid');page.locator('#quantity').fill('1');page.locator('#from').fill('2026-11-01');page.locator('#to').fill('2026-11-28');page.locator('#business-form button[type=submit]').click();assert '123456789012345.67 KZT' in page.locator('#feedback').inner_text();fit();assert not errors,errors
            print('PASS exact value',width,theme,flush=True);ctx.close()
    browser.close()
