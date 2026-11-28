"""Browser/locale/accessibility admission probe. No fallback from HTTP or requested engine."""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright
from qualify_http import open_page, MODE

ROOT=Path(__file__).resolve().parent
LANG={
    'ru':('Обзор бизнеса','Реестр','Формы','Детализация','Нет данных','Найдено: 7','Готово','Ошибка демонстрационного адаптера','123\u00a0456\u00a0789\u00a0012,34','Окончание раньше начала'),
    'kk':('Бизнеске шолу','Тізілім','Нысандар','Толық мәлімет','Дерек жоқ','Табылды: 7','Дайын','Демо адаптер қатесі','123\u00a0456\u00a0789\u00a0012,34','Аяқталу күні басталу күнінен ерте'),
    'en':('Business overview','Registry','Forms','Details','No data','Found: 7','Ready','Demo adapter error','123,456,789,012.34','End date precedes start date'),
}
# Verify both exact decimal digits and RM locale separator policy.
# NBSP, narrow NBSP and space are equivalent grouping whitespace.
def digits(value):return ''.join(c for c in value if c.isascii() and c.isdigit())
def normalized(value):return value.replace('\u202f',' ').replace('\u00a0',' ')

def run(p):
    engine=os.getenv('RM_UI_ENGINE','chromium')
    if engine not in ('chromium','firefox','webkit'):raise ValueError('Unsupported engine')
    kind=getattr(p,engine)
    path=os.getenv('RM_UI_BROWSER_PATH') or (os.getenv('CHROMIUM_PATH','/usr/bin/chromium') if engine=='chromium' else None)
    if not Path(path or kind.executable_path).is_file():raise RuntimeError(f'{engine} binary not installed')
    browser=kind.launch(**({'executable_path':path} if path else {}),**({'args':['--no-sandbox']} if engine=='chromium' else {}))
    print('Engine',engine,browser.version,'mode',MODE,flush=True)
    for width,touch in ((375,True),(1440,False)):
        for theme in ('light','dark','taupe','sage'):
            ctx=browser.new_context(viewport={'width':width,'height':850},has_touch=touch,reduced_motion='reduce')
            page=ctx.new_page();page.set_default_timeout(5000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));open_page(page)
            def menu():
                if page.locator('#side').is_hidden():page.locator('#nav-toggle').click()
            def fit():assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            def go(view):
                menu();page.locator(f'[data-view={view}]').click();page.wait_for_function('(v)=>!document.getElementById(v).hidden',arg=view);fit()
            def locale(value):
                menu();page.locator('#locale').select_option(value);assert page.locator('html').get_attribute('lang')==value;assert page.locator('html').get_attribute('data-theme')==theme
            def ready():page.wait_for_function("document.getElementById('table-status').dataset.state==='ready'")
            menu();page.locator('#theme').select_option(theme);go('list');ready()
            page.locator('#records input').first.check()
            for lang,words in LANG.items():
                locale(lang);assert page.locator('#records input').first.is_checked();assert page.locator('#tenant').input_value()=='A';assert page.locator('#heading').inner_text()==words[1]
                assert page.locator('#table-status').inner_text()==words[5];assert page.locator('#records td').nth(4).inner_text()==words[6]
                amount=page.locator('#records .num').first.inner_text();assert digits(amount)=='12345678901234';assert normalized(amount)==normalized(words[8]),(lang,amount)
                assert page.locator('#all').get_attribute('aria-label')
                assert page.locator('#records input').first.get_attribute('aria-label').endswith('A-001') or 'A-001' in page.locator('#records input').first.get_attribute('aria-label')
                # A11y tree snapshot: each registry control must have a nonempty name.
                snapshot=page.locator('#registry-region').aria_snapshot();assert 'checkbox' in snapshot and 'button' in snapshot and 'A-001' in snapshot
                if width<768:page.locator('#nav-toggle').click()
                page.locator('#records button').first.focus();page.keyboard.press('Enter');assert page.locator('#drawer').is_visible();page.keyboard.press('Escape');assert not page.locator('#drawer').is_visible();assert page.locator('#records button').first.evaluate('e=>e===document.activeElement')
                page.locator('#records button').first.click();page.locator('#full-detail').click();assert page.locator('#heading').inner_text()==words[3];assert 'A-001' in page.locator('#detail-text').inner_text();fit()
                go('dashboard');assert page.locator('#heading').inner_text()==words[0];page.locator('#chart-details summary').click();assert page.locator('#chart-values td').all_text_contents()==['P1','12','P2','0','P3',words[4],'P4','18','P5','14'];page.locator('#chart-details summary').click()
                go('form');assert page.locator('#heading').inner_text()==words[2];page.locator('#money').fill('123456789012345.67');page.locator('#quantity').fill('1');page.locator('#from').fill('2026-11-01');page.locator('#to').fill('2026-10-01');assert page.locator('#to').evaluate('e=>e.validationMessage')==words[9];page.locator('#to').fill('2026-11-28');page.locator('#business-form button[type=submit]').click();assert '12345678901234567' in digits(page.locator('#feedback').inner_text());fit()
                go('catalog');assert page.locator('#icon-grid .rm-icon').count()==56;page.locator('#open-dialog').click();assert page.locator('#demo-dialog').is_visible();page.keyboard.press('Escape');fit();go('list');assert page.locator('#records input').first.is_checked()
                page.locator('#fail').click();page.wait_for_function("document.getElementById('table-status').dataset.state==='error'");assert page.locator('#table-status').inner_text()==words[7];page.locator('#retry').click();ready();page.locator('#records input').first.check();fit();assert not errors,errors
                print('PASS locale/browser/a11y',engine,width,theme,lang,flush=True)
            # Language change during async list load must not replace tenant or display stale status.
            menu();page.evaluate("() => { const old=adapter.list;adapter.list=async args=>{await new Promise(r=>setTimeout(r,150));return old(args);};load(); }")
            locale('kk');ready();assert page.locator('#table-status').inner_text()==LANG['kk'][5]
            page.locator('#tenant').select_option('B');ready();assert page.locator('html').get_attribute('lang')=='kk';go('list');assert 'B-001' in page.locator('#records').inner_text() and 'A-001' not in page.locator('#records').inner_text();assert page.locator('#money').input_value()=='';assert not errors,errors
            print('PASS language and workspace race',engine,width,theme,flush=True);ctx.close()
    browser.close()

with sync_playwright() as p:run(p)
