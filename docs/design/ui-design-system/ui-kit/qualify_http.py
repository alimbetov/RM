"""Serve exact UI Kit assets from loopback; no inlining, no external network."""
import os
from pathlib import Path
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from functools import partial
from threading import Thread
import atexit

ROOT = Path(__file__).resolve().parent
class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass

MODE = os.getenv('RM_UI_TEST_MODE', 'fixture')
if MODE not in ('fixture', 'http'):
    raise ValueError('RM_UI_TEST_MODE must be fixture or http')
print('Qualification mode:', MODE, flush=True)
if MODE == 'http':
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(ROOT)))
    Thread(target=server.serve_forever, daemon=True).start()
    atexit.register(server.server_close)
    URL = f'http://127.0.0.1:{server.server_port}/index.html'

def fixture_html():
    html = (ROOT/'index.html').read_text()
    for name in ('tokens.css','components.css','layout.css'):
        html = html.replace(f'<link rel="stylesheet" href="{name}">','<style>'+(ROOT/name).read_text()+'</style>')
    html = html.replace('<body>','<body>'+(ROOT/'icons.svg').read_text().replace('<svg xmlns=', '<svg class="sprite" aria-hidden="true" xmlns=',1))
    for name in ('i18n.js','catalog.js','unified.js'):
        html = html.replace(f'<script src="{name}"></script>','<script>'+(ROOT/name).read_text()+'</script>')
    return html.replace('icons.svg#','#')

def open_page(page):
    if MODE == "fixture":
        page.set_content(fixture_html())
        return
    responses, failures = {}, []
    def response(r): responses[r.url.split('?')[0]] = r.status
    def failed(r): failures.append(r.url)
    page.on('response', response)
    page.on('requestfailed', failed)
    page.goto(URL, wait_until='load')
    page.wait_for_function("document.querySelector('.brand-mark .rm-icon').getBBox().width>0")
    for name in ('index.html', 'tokens.css', 'components.css', 'layout.css', 'i18n.js', 'catalog.js', 'unified.js', 'icons.svg'):
        url = URL.rsplit('/',1)[0]+'/'+name
        assert responses.get(url) in (200,304), (name, responses, failures)
    assert not failures, failures
    page.remove_listener('response', response)
    page.remove_listener('requestfailed', failed)
