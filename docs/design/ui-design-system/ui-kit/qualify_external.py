"""Run external qualification; never fall back to fixture or certify manual gates."""
import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from importlib.metadata import version
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SUITES = {'unified':16, 'motion':2, 'guardian':32, 'orbit':40, 'oyu':56,
          'boundaries':32, 'k11q':32, 'names':15}


def blob(path):
    data = path.read_bytes()
    return hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True, help='New evidence directory')
    parser.add_argument('--preflight', action='store_true')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    manifest = (ROOT/'K11Q1-MANIFEST.txt').read_text().splitlines()
    expected = {name: sha for sha, name in (line.split() for line in manifest[1:] if line.strip())}
    actual = {name: blob(ROOT/name) if (ROOT/name).is_file() else None for name in expected}
    report = {'baseline': manifest[0], 'utc': datetime.now(timezone.utc).isoformat(),
              'platform': platform.platform(), 'python': sys.version,
              'mode': 'http', 'manifest': actual, 'checks': [], 'manual_freeze': 'HOLD'}
    def save():
        (out/'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n')
    if actual != expected:
        report['automated_admission'] = 'HOLD_MANIFEST_MISMATCH'; save(); return 1
    shutil.copyfile(ROOT/'K11Q1-MANIFEST.txt', out/'manifest.txt')
    try:
        from playwright.sync_api import sync_playwright
        report['playwright'] = version('playwright')
        with sync_playwright() as p:
            paths = {name: getattr(p, name).executable_path for name in ('chromium','firefox','webkit')}
    except Exception as exc:
        report['error'] = str(exc); report['automated_admission'] = 'HOLD_PREFLIGHT'; save(); return 1
    report['binaries'] = {name: {'path':path, 'exists':Path(path).is_file()} for name,path in paths.items()}
    if args.preflight or report['playwright'] != '1.57.0' or not all(x['exists'] for x in report['binaries'].values()):
        report['automated_admission'] = 'HOLD_PREFLIGHT'; save(); return 1
    # Run in a temporary copy, leaving the Git working tree untouched.
    with tempfile.TemporaryDirectory(prefix='rm-k11q1-') as tmp:
        work = Path(tmp)
        for name in expected:
            shutil.copyfile(ROOT/name, work/name)
        for engine in ('chromium','firefox','webkit'):
            suite_names = SUITES if engine == 'chromium' else ('k11q','names')
            for suite in suite_names:
                env = dict(os.environ, RM_UI_TEST_MODE='http', RM_UI_ENGINE=engine,
                           RM_UI_BROWSER_PATH=paths[engine], CHROMIUM_PATH=paths['chromium'])
                log = out/f'{engine}-{suite}.log'
                rc = None
                try:
                    with log.open('w', encoding='utf-8') as stream:
                        rc = subprocess.run([sys.executable, f'qualify_{suite}.py'], cwd=work,
                                            env=env, stdout=stream, stderr=subprocess.STDOUT,
                                            timeout=600, check=False).returncode
                except subprocess.TimeoutExpired:
                    with log.open('a', encoding='utf-8') as stream: stream.write('\nTIMEOUT\n')
                lines = log.read_text(errors='replace').splitlines()
                passed = sum(line.startswith('PASS ') for line in lines)
                ok = rc == 0 and passed == SUITES[suite] and 'Qualification mode: http' in lines
                report['checks'].append({'engine':engine, 'suite':suite, 'exit':rc,
                                         'passed':passed, 'expected':SUITES[suite],
                                         'status':'PASS' if ok else 'HOLD', 'log':log.name})
                save(); print(engine, suite, 'PASS' if ok else 'HOLD', flush=True)
        report['source_unchanged'] = all(blob(work/name)==sha for name,sha in expected.items())
    ok = report['source_unchanged'] and all(c['status']=='PASS' for c in report['checks'])
    report['automated_admission'] = 'PASS' if ok else 'HOLD'; save()
    return 0 if ok else 1

if __name__ == '__main__':
    sys.exit(main())
