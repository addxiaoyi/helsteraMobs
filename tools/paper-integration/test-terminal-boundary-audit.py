"""Corrupt boundary callbacks and require the observation audit to reject them."""
import base64
import copy
import importlib.util
import sys

spec = importlib.util.spec_from_file_location('terminal_boundary_audit', __file__.replace(
    'test-terminal-boundary-audit.py', 'audit-terminal-boundary.py'))
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)


def main(path):
    report, payloads = audit_module.read_report(path)
    audit_module.audit(report, payloads)
    rejected = 0

    def check(label, mutation):
        nonlocal rejected
        report_copy, payload_copy = copy.deepcopy(report), dict(payloads)
        mutation(report_copy, payload_copy)
        try:
            audit_module.audit(report_copy, payload_copy)
        except (ValueError, KeyError):
            rejected += 1
            return
        raise AssertionError('accepted invalid boundary evidence: ' + label)

    lines = payloads['server.log'].decode().splitlines()

    def hook_line(key, backend, label):
        found = [line for line in lines if 'TREAD_HOOK|' in line and all(
            audit_module.parse_event(line, 'HOOK').get(field) == value
            for field, value in (('key', key), ('backend', backend), ('label', label)))]
        assert len(found) == 1
        return found[0]

    def replace_hook(key, backend, label, field, value):
        original = hook_line(key, backend, label)

        def mutation(report_copy, payload_copy):
            changed, count = __import__('re').subn(
                r'(?<=\|)' + field + r'=[^|\n]*', field + '=' + str(value), original)
            assert count == 1
            altered = [changed if line == original else line for line in lines]
            payload_copy['server.log'] = ('\n'.join(altered) + '\n').encode()
        return mutation

    def replace_read(key, backend, label, field, value):
        original = hook_line(key, backend, label)

        def mutation(report_copy, payload_copy):
            event = audit_module.parse_event(original, 'HOOK')
            reads = audit_module.decode_hook(event)
            reads[field] = value
            encoded = base64.b64encode(';'.join(k + '=' + v for k, v in reads.items()).encode()).decode()
            changed, count = __import__('re').subn(r'(?<=\|)reads=[^|\n]*', 'reads=' + encoded, original)
            assert count == 1
            altered = [changed if line == original else line for line in lines]
            payload_copy['server.log'] = ('\n'.join(altered) + '\n').encode()
        return mutation

    scenarios = ('selfdeath', 'peerdeath', 'death', 'chunk', 'command', 'suspended')
    for scenario in scenarios:
        for kind in ('cow', 'zombie'):
            for path_part in ('direct', 'imported'):
                key = f'{scenario}_{kind}_{path_part}'
                for backend in audit_module.BACKENDS:
                    boundary_label = 'before6' if scenario == 'chunk' and backend == 'runtime' else 'before5'
                    check('missing after boundary', lambda r, p, k=key, b=backend:
                          p.update({'server.log': ('\n'.join(line for line in lines if not (
                              'TREAD_HOOK|' in line and (lambda e: e['key'] == k and e['backend'] == b
                                  and e['label'] == 'before0')(audit_module.parse_event(line, 'HOOK')))) + '\n').encode()}))
                    if scenario in ('selfdeath', 'peerdeath', 'death', 'chunk'):
                        check('wrong callback tick', replace_hook(key, backend, boundary_label, 'tick', '1'))
                    if backend == 'native' or scenario in ('selfdeath', 'peerdeath', 'death', 'chunk'):
                        check('target fallback after removal', replace_read(key, backend, boundary_label,
                              'targetMarker', '73'))
    for backend in audit_module.BACKENDS:
        key = 'peerdeath_cow_direct'
        check('lost independent live trigger', replace_read(key, backend, 'before5', 'triggerMarker', '73'))
        check('counterfeit native timestamp', replace_read(key, 'native', 'before0', 'stamp', '0,false'))
        check('wrong active registry sample', replace_read(key, 'native', 'before5', 'stamp', '0,true'))
    check('false parity promotion', lambda r, p: r.update(parityStatus='complete', evidenceMode='release'))
    check('wrong oracle', lambda r, p: r.update(mythicMobsSha256='0' * 64))
    print(f'Terminal boundary audit: {rejected} negative controls rejected')


if __name__ == '__main__':
    main(sys.argv[1])
