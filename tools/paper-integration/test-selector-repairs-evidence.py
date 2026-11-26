"""Reject altered selector lifecycle/state/client evidence, including rehashed archives."""
import copy
import importlib.util
import io
from pathlib import Path
import re
import sys
import warnings
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('selector_validator', Path(__file__).with_name('validate-selector-repairs.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)


def rejected(label, action):
    try: action()
    except (ValueError, KeyError): return
    raise AssertionError('Accepted invalid evidence: ' + label)


def rehash(report, payloads):
    report['files'] = [{'path': name, 'sha256': v.digest(data)} for name, data in payloads.items()]
    report['observations'] = [line for line in payloads['server.log'].decode().splitlines() if 'SELECTOR_' in line]


def main(path):
    report, payloads = v.main(path); count = 0
    def reject_payload(label, transform):
        nonlocal count
        r, p = copy.deepcopy(report), dict(payloads)
        transform(p); rehash(r, p); rejected(label, lambda: v.validate(r, p)); count += 1
    for original in report['observations']:
        marker = re.search(r'SELECTOR_[A-Z_]+\|', original)[0]
        reject_payload('missing ' + marker, lambda p, line=original: p.update({'server.log': p['server.log'].replace((line+'\n').encode(), b'')}))
        reject_payload('duplicate ' + marker, lambda p, line=original: p.update({'server.log': p['server.log'].replace(line.encode(), (line+'\n'+line).encode())}))
        for field, value in v.fields(original, marker).items():
            bad = 'false' if value == 'true' else 'true' if value == 'false' else '-999' if field == 'tick' else '999'
            changed = re.sub(r'(?<=\|)' + re.escape(field) + r'=[^|\n]*', field + '=' + bad, original)
            reject_payload(marker + field, lambda p, a=original, b=changed: p.update({'server.log': p['server.log'].replace(a.encode(), b.encode())}))
    reject_payload('profiles', lambda p: p.update({'source-profiles.yml': b'{}\n'}))
    for original in payloads['client.log'].decode().splitlines():
        if 'SELECTOR_CLIENT ' not in original: continue
        reject_payload('missing client event', lambda p, line=original: p.update({'client.log': p['client.log'].replace((line+'\n').encode(), b'')}))
        reject_payload('duplicate client event', lambda p, line=original: p.update({'client.log': p['client.log'].replace(line.encode(), (line+'\n'+line).encode())}))
    for field, value in [('evidenceMode', 'observation'), ('schemaVersion', True), ('nativeGoalParity', True),
                          ('nativeGoalParity', 0), ('contract', 'native'), ('exitCode', 1), ('clientExitCode', 1),
                          ('paperJarSha256', '0'*64), ('mythicMobsSha256', '0'*64), ('pluginSha256', 'invalid'),
                          ('probeJarSha256', 'invalid'), ('sourceHashes', {}), ('observations', []), ('validationResult', 'failed')]:
        r = copy.deepcopy(report); r[field] = value
        rejected(field, lambda: v.validate(r, payloads)); count += 1
    for case in ('missing', 'extra', 'duplicate', 'unsafe', 'changed'):
        with io.BytesIO() as buffer:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(buffer, 'w') as archive:
                    for name, data in payloads.items():
                        if case == 'missing' and name == 'server.log': continue
                        archive.writestr(name, data + b'changed' if case == 'changed' else data)
                    if case == 'extra': archive.writestr('orphan.txt', 'x')
                    if case == 'duplicate': archive.writestr('server.log', payloads['server.log'])
                    if case == 'unsafe': archive.writestr('../outside', 'x')
            data = buffer.getvalue()
        r = copy.deepcopy(report); r['logArchiveSha256'] = v.digest(data)
        rejected('archive ' + case, lambda: v.archive_payloads(r, data)); count += 1
    print('Rejected', count, 'selector repair semantic/archive mutations.')


if __name__ == '__main__': main(sys.argv[1])
