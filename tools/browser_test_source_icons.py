"""Phone UI regression against a local server; mocked API, never connects to a TV.

python tools/browser_test_source_icons.py http://127.0.0.1:8765/
Requires Playwright and Chromium. The server serves app/src/main/assets/web.
"""
import base64
import copy
import json
import sys
from urllib.parse import parse_qs, urlparse
from playwright.sync_api import sync_playwright, expect

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1sAAAAASUVORK5CYII=')
KEY = 'input:test/HW1'
BASE = {
    'sourceIconLibrary': True, 'selected': 'daily:latest', 'latestDaily': 'daily:latest',
    'rotatePhotos': 'photos:all', 'wallpapers': [], 'screensaver': 'aerial',
    'videos': [], 'focusStyle': 'outline', 'devices': [], 'inputs': [],
    'apps': [{'key': KEY, 'label': 'HDMI 1', 'default': 'HDMI 1', 'source': True,
              'port': 'HDMI 1', 'icon': 'own', 'library': False, 'custom': False, 'stamp': 1}],
    'layout': {'ready': True, 'home': [{'key': KEY, 'label': 'HDMI 1', 'kind': 'source', 'stamp': 1}],
               'folders': {}, 'hidden': [], 'columns': 5},
}

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    for supports in (True, False):
        state = copy.deepcopy(BASE)
        if not supports:
            del state['sourceIconLibrary']  # Older full app and Lite.
        requests, errors = [], []
        fail = [False]
        page = browser.new_page(viewport={'width': 390, 'height': 844}, locale='en-US')
        page.on('pageerror', lambda error: errors.append(str(error)))

        def route_request(route):
            path = urlparse(route.request.url).path
            if path.endswith('/api/state'):
                data = state
            elif path.endswith('/api/library/all'):
                data = {'base': sys.argv[1], 'apps': [{'app': 'test.app', 'name': {'en': 'Apple TV'},
                        'choices': [{'id': 'red', 'p': 'picture.png'}]}]}
            elif path.endswith('/api/icon/pick'):
                fields = {k: v[0] for k, v in parse_qs(route.request.post_data or '', keep_blank_values=True).items()}
                requests.append(fields)
                if not fail[0]:
                    a = state['apps'][0]
                    a['icon'] = fields['pick']; a['stamp'] += 1
                    a['library'] = fields['pick'] == 'library'
                    a['choices'] = ([{'id': 'red', 'from': 'test.app', 'by': {'name': 'Test'}, 'on': True}]
                                    if a['library'] else [])
                data = dict(state, saved=not fail[0])
            elif '/tile/' in path or '/library/' in path or path.endswith('picture.png'):
                route.fulfill(status=200, content_type='image/png', body=PNG)
                return
            elif '/api/' in path:
                raise AssertionError('Unexpected API request: ' + path)
            else:
                route.continue_()
                return
            route.fulfill(status=200, content_type='application/json', body=json.dumps(data))

        page.route('**/*', route_request)
        page.goto(sys.argv[1] + '#home')
        page.wait_for_load_state('networkidle')
        tile = page.locator('.atile').first
        expect(tile).to_be_visible()
        tile.click()
        more_text = page.evaluate("t('icon_more')")
        more = page.get_by_role('button', name=more_text, exact=True)
        if not supports:
            expect(more).to_have_count(0)
            assert not errors, errors
            page.close()
            continue
        expect(more).to_be_visible()
        more.click()
        page.get_by_role('button', name='Apple TV', exact=True).click()
        page.wait_for_function("state.apps[0].icon === 'library'")
        assert requests[-1] == {'app': KEY, 'pick': 'library', 'choice': 'red', 'from': 'test.app'}
        tile.click()
        own_text = page.evaluate("t('icon_own')")
        page.get_by_role('radio', name=own_text, exact=True).click()
        page.wait_for_function("state.apps[0].icon === 'own'")
        assert requests[-1]['pick'] == 'own'
        fail[0] = True
        tile.click(); page.get_by_role('button', name=more_text, exact=True).click()
        page.get_by_role('button', name='Apple TV', exact=True).click()
        expect(page.locator('#toast')).to_have_text(page.evaluate("t('toast_set_fail')"))
        assert state['apps'][0]['icon'] == 'own'
        assert not errors, errors
        page.close()
    browser.close()
print('PASS: source library pick, restore own, failed download, and legacy capability gate; no page errors.')
