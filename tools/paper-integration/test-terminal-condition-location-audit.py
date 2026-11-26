"""Check that altered terminal conditions and location observations are rejected."""
import argparse
import base64
import copy
import importlib.util
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    'condition_location_audit', Path(__file__).with_name('audit-terminal-condition-location.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


def main(report_path, plugin_jar):
    report, payloads = a.boundary.read_report(report_path)
    a.audit(report, payloads, plugin_jar)
    rejected = 0

    def check(label, mutate, message=None):
        nonlocal rejected
        r, p = copy.deepcopy(report), dict(payloads)
        mutate(r, p)
        try:
            a.audit(r, p, plugin_jar)
        except ValueError as error:
            assert message is None or message in str(error), f'{label}: wrong rejection: {error}'
            rejected += 1
            return
        raise AssertionError('accepted invalid condition or location observation: ' + label)

    def log(mutate):
        def apply(r, p):
            lines = mutate(p['server.log'].decode().splitlines())
            p['server.log'] = ('\n'.join(lines) + '\n').encode()
            r['observations'] = [line for line in lines if 'TREAD_ROW|' in line or 'TREAD_POINTS|' in line]
        return apply

    def event(kind, match, modify):
        def alter(lines):
            selected = [i for i, line in enumerate(lines) if f'TREAD_{kind}|' in line and all(
                a.boundary.parse_event(line, kind)[field] == value for field, value in match.items())]
            assert len(selected) == 1, match
            lines[selected[0]] = modify(lines[selected[0]])
            return lines
        return log(alter)

    def replace(line, field, value):
        changed, count = re.subn(r'(?<=\|)' + field + r'=[^|\n]*', field + '=' + str(value), line)
        assert count == 1
        return changed

    def replace_read(line, field, value):
        row = a.boundary.parse_event(line, 'HOOK')
        values = a.boundary.decode_hook(row)
        values[field] = value
        encoded = base64.b64encode(';'.join(k + '=' + v for k, v in values.items()).encode()).decode()
        return replace(line, 'reads', encoded)

    for backend in a.BACKENDS:
        for path in ('direct', 'imported'):
            match = dict(key='selfdeath_cow_' + path, backend=backend)
            check('wrong trigger UUID', event('HOOK', dict(match, label='check9'), lambda line:
                  replace_read(line, 'triggerUuid', '00000000-0000-0000-0000-000000000001')), 'wrong caster, target or trigger reads')
            check('forged positive gate', event('HOOK', dict(match, label='target9false'), lambda line:
                  replace(line, 'label', 'target9true')), 'HOOK event')
            check('lost retained caster callback', event('HOOK', dict(match, label='caster70true'), lambda line: ''),
                  'HOOK event')
            check('leaked detached target location', event('POINTS', dict(match, phase='settled', role='Target', offset='9'),
                  lambda line: replace(line, 'material', 'GOLD_BLOCK')), 'wrong location material')
            check('wrong expiry deadline', event('SEED', match,
                  lambda line: replace(line, 'deadline', a.boundary.parse_event(line, 'SEED')['millis'])), 'invalid registry seed')
            check('lost independent peer', event('HOOK', dict(key='peerdeath_cow_' + path, backend=backend, label='trigger70true'),
                  lambda line: replace_read(line, 'triggerMarker', '73')), 'wrong caster, target or trigger reads')
            check('registry resurrected', event('ROW', dict(match, phase='cleaned'), lambda line:
                  replace(line, 'registry', 'true')), 'lifecycle changed')
    check('missing native diagnostic', log(lambda lines: [line for line in lines if
          "Couldn't execute skill 'tr_targetpoint9_direct'" not in line]), 'wrong native location diagnostic')
    def wrong_direct_polarity(r, p):
        archive = p['imports/skills.yml']
        original = b'!variable-is-set{var=target.Pre}'
        assert archive.count(original) >= 1, 'direct precondition is missing'
        p['imports/skills.yml'] = archive.replace(original, b'variable-is-set{var=target.Pre}', 1)
    check('wrong archived direct polarity', wrong_direct_polarity, 'unexpected runtime gate')
    check('wrong pinned native artifact', lambda r, p: r.update(mythicMobsSha256='0' * 64), 'wrong pinned native environment')
    if plugin_jar:
        check('wrong production artifact', lambda r, p: r.update(pluginSha256='0' * 64), 'wrong production artifact')
    source_name = next(iter(a.SOURCES))
    check('wrong pinned source', lambda r, p: r['sourceHashes'].__setitem__(source_name, '0' * 64),
          'source changed: ' + source_name)
    check('false parity promotion', lambda r, p: r.update(parityStatus='complete'), 'expected observation-only report')
    print(f'Terminal condition and location observation audit: {rejected} negative controls rejected')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report')
    parser.add_argument('--plugin-jar', type=Path)
    args = parser.parse_args()
    main(args.report, args.plugin_jar)
