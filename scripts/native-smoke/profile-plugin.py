#!/usr/bin/env python3
"""Profile a fixed synthetic chat library in a disposable IntelliJ profile."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--plugin', type=Path, required=True)
parser.add_argument('--ide', type=Path, default=Path('/Applications/IntelliJ IDEA.app'))
parser.add_argument('--label', default='run')
parser.add_argument('--restore', type=Path, help='Reopen a completed fixture profile and validate all saved content')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
root = args.restore or Path(tempfile.mkdtemp(prefix='codex-perf-' + args.label + '-'))
for name in ['config/options', 'system', 'plugins', 'logs', 'project/.idea']:
    (root / name).mkdir(parents=True, exist_ok=True)
(root / 'project/.idea/misc.xml').write_text('<project version="4"/>')
with zipfile.ZipFile(args.plugin) as archive:
    archive.extractall(root / 'plugins')
smoke = root / 'plugins/smoke/lib'
smoke.mkdir(parents=True, exist_ok=True)
shutil.copy2(repo / 'build/native-smoke/codex-tabs-native-smoke.jar', smoke / 'codex-tabs-native-smoke.jar')
options = (args.ide / 'Contents/bin/idea.vmoptions').read_text()
options += ('\n-Dcodex.smoke.performance.restore=true' if args.restore else '')
options += '\n' + '\n'.join([
    '-Didea.config.path=' + str(root / 'config'), '-Didea.system.path=' + str(root / 'system'),
    '-Didea.plugins.path=' + str(root / 'plugins'), '-Didea.log.path=' + str(root / 'logs'),
    '-Didea.initially.ask.config=false', '-Djb.consents.confirmation.enabled=false',
    '-Didea.trust.all.projects=true', '-Dide.show.tips.on.startup.default.value=false',
    '-Dcodex.smoke.performance.check=true', '-Dcodex.smoke.binary=' + str(repo / 'scripts/fake-codex.py'),
    '-Dcodex.smoke.cwd=' + str(root / 'project'),
]) + '\n'
(root / 'test.vmoptions').write_text(options)
env = os.environ.copy()
env['IDEA_VM_OPTIONS'] = str(root / 'test.vmoptions')
env.pop('JAVA_TOOL_OPTIONS', None)
print('Profile:', root, flush=True)
with (root / 'launcher.log').open('w') as log:
    process = subprocess.Popen([str(args.ide / 'Contents/MacOS/idea'), str(root / 'project')], env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            result = root / ('logs/performance-restore.json' if args.restore else 'logs/performance-result.json')
            error = root / 'logs/smoke-error.txt'
            if result.exists():
                print(json.dumps(json.loads(result.read_text())), flush=True)
                break
            if error.exists():
                raise RuntimeError(error.read_text())
            if process.poll() is not None:
                raise RuntimeError('Test IDEA exited: ' + str(process.returncode))
            time.sleep(1)
        else:
            raise RuntimeError('Timed out. See ' + str(root / 'logs'))
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
