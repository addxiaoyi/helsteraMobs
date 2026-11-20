"""Negative controls for the observation audit, not a release-gate promotion."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('terminal_read_audit', Path(__file__).with_name('audit-terminal-reads.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


def main(report_path):
    report, payloads = a.read(report_path)
    result = a.audit(report, payloads)
    assert result['identityPlaceholdersVerified'] and not result['detachedReadFailures'], 'requires actual corrected observation'
    assert result['differences'], 'remaining differences must stay visible'
    rejected = 0

    def check(label, mutate):
        nonlocal rejected
        r, p = copy.deepcopy(report), dict(payloads)
        mutate(r, p)
        try:
            result = a.audit(r, p)
            a.require(not result['detachedReadFailures'], 'bad detached reads')
        except (ValueError, KeyError):
            rejected += 1
            return
        raise AssertionError('accepted invalid observation: ' + label)

    def event(label, marker, match, modify):
        def mutate(r, p):
            lines = p['server.log'].decode().splitlines()
            positions = [i for i, line in enumerate(lines) if marker in line and all(
                a.fields(line, marker).get(k) == value for k, value in match.items())]
            assert len(positions) == 1, label
            at = positions[0]
            lines[at] = modify(lines[at])
            p['server.log'] = ('\n'.join(lines) + '\n').encode()
            r['observations'] = [line for line in lines if 'TREAD_ROW|' in line]
        check(label, mutate)

    def replace(line, field, value):
        result, count = re.subn(r'(?<=\|)' + field + r'=[^|\n]*', field + '=' + str(value), line)
        assert count == 1
        return result

    def read_value(line, field, value):
        row = a.fields(line, 'TREAD_HOOK|')
        values = a.decode(row)
        values[field] = value
        encoded = base64.b64encode(';'.join(k + '=' + v for k, v in values.items()).encode()).decode()
        return replace(line, 'reads', encoded)

    for backend in a.BACKENDS:
        for path in ('direct', 'imported'):
            key = 'selfdeath_cow_' + path
            for role in ('target', 'trigger'):
                for suffix in ('Marker', 'Counter', 'Pre', 'Missing', 'Raw'):
                    for wrong in ('73', 'absent'):
                        event(f'{backend}/{path}/{role}{suffix}/{wrong}', 'TREAD_HOOK|',
                              dict(key=key, backend=backend, label='targetadd'),
                              lambda line, f=role+suffix, value=wrong: read_value(line, f, value))
            for field in ('casterUuid', 'targetUuid', 'triggerUuid'):
                event('wrong role UUID/' + field, 'TREAD_HOOK|', dict(key=key, backend=backend, label='targetadd'),
                      lambda line, f=field: read_value(line, f, '00000000-0000-0000-0000-000000000001'))
            event('lost live trigger', 'TREAD_HOOK|', dict(key='peerdeath_cow_' + path, backend=backend, label='targetadd'),
                  lambda line: read_value(line, 'triggerMarker', '73'))
            event('lost retained caster', 'TREAD_HOOK|', dict(key=key, backend=backend, label='mutated'),
                  lambda line: read_value(line, 'casterCounter', '1'))
            for field, value in (('registry', 'true'), ('liveVariables', '1'), ('primary', 'false'), ('cleaning', 'true')):
                event('resurrected/wrong callback/' + field, 'TREAD_HOOK|', dict(key=key, backend=backend, label='targetadd'),
                      lambda line, f=field, val=value: replace(line, f, val))
            event('missing callback', 'TREAD_HOOK|', dict(key=key, backend=backend, label='finish'), lambda line: '')
            event('duplicate callback', 'TREAD_HOOK|', dict(key=key, backend=backend, label='finish'), lambda line: line + '\n' + line)
            event('wrong delay', 'TREAD_HOOK|', dict(key=key, backend=backend, label='targetadd'),
                  lambda line: replace(line, 'tick', int(a.fields(line, 'TREAD_HOOK|')['tick']) + 3))
            event('bad encoding', 'TREAD_HOOK|', dict(key=key, backend=backend, label='targetadd'),
                  lambda line: replace(line, 'reads', '!invalid!'))
    for field, value in (('entity', 'true'), ('registry', 'true'), ('liveVariables', '1'), ('visuals', '11'), ('terminalExecutions', '1')):
        event('cleanup/' + field, 'TREAD_ROW|', dict(key='selfdeath_cow_direct', backend='runtime', phase='cleaned'),
              lambda line, f=field, val=value: replace(line, f, val))
    check('false promotion', lambda r, p: r.update(evidenceMode='comparison', parityStatus='complete'))
    check('orphan snapshot', lambda r, p: p.update({'snapshots/extra.yml': b'{}'}))
    check('missing row evidence', lambda r, p: r.update(observations=[]))
    check('wrong oracle', lambda r, p: r.update(mythicMobsSha256='0' * 64))

    # Check the archive reader independently of the semantic analyzer.
    source = Path(report_path)
    with tempfile.TemporaryDirectory(prefix='terminal-read-audit-') as temporary:
        root = Path(temporary)
        archive = root / report['logArchive']
        original = (source.parent / report['logArchive']).read_bytes()
        for label, data in (('changed archive', original + b'changed'), ('changed member', None)):
            r = copy.deepcopy(report)
            if data is not None:
                archive.write_bytes(data)
            else:
                with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
                    for name, contents in payloads.items():
                        z.writestr(name, contents + b'changed' if name == 'server.log' else contents)
                r['logArchiveSha256'] = a.v.digest(archive.read_bytes())
            local = root / 'report.json'
            local.write_text(json.dumps(r))
            try:
                a.read(local)
            except ValueError:
                rejected += 1
            else:
                raise AssertionError('accepted ' + label)
    print(f'Terminal read observation audit: {rejected} negative controls rejected; remaining parity differences preserved')


if __name__ == '__main__':
    main(sys.argv[1])
