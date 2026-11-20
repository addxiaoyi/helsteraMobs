"""Observe native target writes and reads on both sides of registry removal."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('read_contract', Path(__file__).with_name('terminal-read-cases.py'))
reads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reads)
CASES = reads.CASES
native_mobs = reads.native_mobs


def command(label):
    values = ';'.join(key + '=' + value for key, value in reads.READS.items())
    return f'command{{c="terminalreadprobe hook native {label} <caster.uuid> {values};stamp=<trstamp>"}} @self'


def native_skills():
    base = {'tr_terminal': {'Skills': []}}
    for tick in range(9):
        name = f'tr_boundary{tick}'
        base['tr_terminal']['Skills'].append(f'skill{{s={name};delay={tick}}} @self')
        base[name] = {'Skills': [command(f'before{tick}'),
                                'variableadd{var=target.Marker;amount=1} @self',
                                command(f'after{tick}')]}
    base['tr_terminal']['Skills'].extend([
        'skill{s=tr_repeated;delay=1;repeat=7;repeatinterval=1} @self',
        'skill{s=tr_late;delay=20} @self'])
    base['tr_repeated'] = {'Skills': [command('repeat')]}
    base['tr_late'] = {'Skills': [command('late')]}
    result = {}
    for path in ('direct', 'imported'):
        for name, value in base.items():
            actions = []
            for action in value['Skills']:
                for skill in base:
                    action = action.replace('s=' + skill + ';', 's=' + skill + '_' + path + ';').replace('s=' + skill + '}', 's=' + skill + '_' + path + '}')
                actions.append(action)
            result[name + '_' + path] = {'Skills': actions}
    return result
