"""Compare condition dispatch and location selection during terminal callbacks."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('read_cases', Path(__file__).with_name('terminal-read-cases.py'))
reads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reads)
CASES = {key: value for key, value in reads.CASES.items() if value[1] == 'COW'}
TICKS = (2, 9, 70)
GATES = {
    'caster': ('Conditions', 'variableisset{var=caster.Marker}'),
    'target': ('Conditions', 'variableisset{var=target.Marker}'),
    'selected': ('TargetConditions', 'variableequals{var=target.Marker;value=73}'),
    'trigger': ('TriggerConditions', 'variableinrange{var=target.Marker;value=29to73}'),
    'pre': ('Conditions', 'variableisset{var=target.Pre}'),
    'point': ('Conditions', 'variableisset{var=target.TargetPoint2}'),
}


def native_mobs():
    return {name: definition for name, definition in reads.native_mobs().items()
            if name == 'tr_peer' or name.removeprefix('tr_') in CASES}


def command(label):
    values = ';'.join(key + '=' + value for key, value in reads.READS.items())
    return f'command{{c="terminalreadprobe hook native {label} <caster.uuid> {values};stamp=<trstamp>"}} @self'


def native_skills():
    result = {}
    for path in ('direct', 'imported'):
        terminal = []
        for tick in TICKS:
            phase = f'tr_checks{tick}_{path}'
            terminal.append(f'skill{{s={phase};delay={tick}}} @self')
            checks = [command(f'check{tick}')]
            for label, (field, expression) in GATES.items():
                for expected in ('true', 'false'):
                    name = f'tr_{label}{tick}{expected}_{path}'
                    checks.append(f'skill{{s={name}}} @self')
                    result[name] = {field: [expression + ' ' + expected],
                                    'Skills': [command(f'{label}{tick}{expected}')]}
            # Probe absence at a distinct location, without touching the caster block.
            # Use child skills so a native location lookup error does not hide its sibling.
            for role in ('caster', 'target'):
                name = f'tr_{role}point{tick}_{path}'
                checks.append(f'skill{{s={name}}} @self')
                result[name] = {'Skills': [f'setblock{{material=GOLD_BLOCK}} @variablelocation{{var={role}.{role.title()}Point{tick}}}']}
            result[phase] = {'Skills': checks}
        result[f'tr_terminal_{path}'] = {'Skills': terminal}
    return result
