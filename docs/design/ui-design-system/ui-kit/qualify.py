from pathlib import Path
from functools import partial
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from threading import Thread
from xml.etree import ElementTree as ET
from playwright.sync_api import sync_playwright
import re, json
ROOT=Path(__file__).resolve().parent
ns={'s':'http://www.w3.org/2000/svg'}
root=ET.parse(ROOT/'icons.svg').getroot(); symbols=root.findall('s:symbol',ns); ids=[x.attrib['id'] for x in symbols]
assert len(ids)==len(set(ids))==56 and all(x.attrib['viewBox']=='0 0 24 24' for x in symbols)
assert all(x.tag.rsplit('}',1)[-1] in {'svg','symbol','path'} for x in root.iter())
assert all(set(x.attrib)<= {'fill','stroke','stroke-width','stroke-linecap','stroke-linejoin','id','viewBox','d'} for x in root.iter())
names=re.search(r"const names='([^']+)'",(ROOT/'catalog.js').read_text()).group(1).split(); assert {'rm-'+n for n in names}==set(ids)
assert set(re.findall(r'icons\.svg#(rm-[\w-]+)',(ROOT/'index.html').read_text()))<=set(ids)
class Quiet(SimpleHTTPRequestHandler):
 def log_message(self,*args): pass
html=(ROOT/'index.html').read_text()
for name in ['tokens.css','components.css']:
 html=html.replace(f'<link rel="stylesheet" href="{name}">','<style>'+ (ROOT/name).read_text()+'</style>')
html=html.replace('<body>','<body>'+ (ROOT/'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=',1))
html=html.replace('<script src="catalog.js"></script>','<script>'+ (ROOT/'catalog.js').read_text()+'</script>').replace('icons.svg#','#')
results=[]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 for w,h,touch,zoom in [(w,850,False,100) for w in [320,375,480,600,767,768,820,1023,1024,1280,1440,1920]]+[(667,320,True,100),(1024,768,True,100),(320,640,False,200)]:
  ctx=browser.new_context(viewport={'width':w,'height':h},has_touch=touch);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content(html);page.evaluate('(z)=>document.documentElement.style.fontSize=z+"%"',zoom);page.wait_for_function("document.querySelectorAll('.icon-tile').length===56")
  assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'),(w,h,touch,zoom,'overflow')
  assert page.locator('#icon-grid .rm-icon').count()==56
  page.wait_for_function("[...document.querySelectorAll('.rm-icon')].every(e=>e.getBBox().width>0 && e.getBBox().height>0)")
  if w<768 or touch:
   assert page.locator('button.icon').first.bounding_box()['width']>=48
   assert page.locator('#name').bounding_box()['height']>=48
  assert page.locator('button[disabled]').first.is_disabled();assert page.locator('#disabled-field').is_disabled()
  region=page.locator('.table-wrap');region.evaluate('(e)=>e.scrollLeft=e.scrollWidth');assert region.evaluate('(e)=>e.scrollLeft+e.clientWidth>=e.scrollWidth-1')
  page.locator('#open-dialog').focus();page.keyboard.press('Enter');assert page.locator('dialog').is_visible();assert page.locator('dialog').evaluate('(e)=>e.getBoundingClientRect().width<=innerWidth && e.getBoundingClientRect().height<=innerHeight');page.keyboard.press('Escape');assert not page.locator('dialog').is_visible();assert page.locator('#open-dialog').evaluate('(e)=>e===document.activeElement')
  assert not errors,errors
  results.append({'width':w,'height':h,'touch':touch,'text_scale':zoom,'result':'PASS'});ctx.close()
 print(json.dumps({'browser':browser.version,'icons':len(ids),'cases':results},ensure_ascii=False));browser.close()

