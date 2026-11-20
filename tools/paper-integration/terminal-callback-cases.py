"""Pinned terminal-callback stimuli shared by the runner and independent validator."""
import itertools

CASES = {f'{scenario}_{kind.lower()}_{path}': (scenario, kind, path)
         for scenario, kind, path in itertools.product(
             ('control', 'command', 'death', 'chunk', 'suspended'),
             ('COW', 'ZOMBIE'), ('direct', 'imported'))}

def command(label, timing=''):
    return f'command{{c="terminalprobe hook native {label} <caster.uuid> <caster.var.Marker>"{timing}}} @self'

def native_mobs():
    mobs = {}
    for key, (scenario, kind, path) in CASES.items():
        skills = [command('pending', ';delay=80') + ' ~onSpawn']
        if scenario == 'control':
            skills += ['skill{s=tc_terminal;delay=10} @self ~onSpawn']
        else:
            skills += [f'skill{{s=tc_terminal}} @self ~on{event}' for event in ('Despawn', 'Death')]
        mobs['tc_' + key] = {'Type': kind, 'Options': {
            'NoAI': True, 'NoGravity': True, 'Invincible': True,
            'PreventSunburn': True, 'Despawn': 'CHUNK' if scenario == 'chunk' else 'NEVER'}, 'Skills': skills}
    for mode in ('NEVER', 'PERSISTENT'):
        mobs['tc_child_' + mode.lower()] = {'Type': 'COW', 'Options': {
            'NoAI': True, 'NoGravity': True, 'Invincible': True, 'Despawn': mode},
            'Skills': [f'command{{c="terminalprobe child native {mode} <caster.uuid>"}} @self ~onSpawn']}
    return mobs

def native_skills():
    return {'tc_terminal': {'Skills': [command('immediate'), command('delay1', ';delay=1'),
            command('delay10', ';delay=10'), command('repeat', ';repeat=2;repeatInterval=5'),
            'skill{s=tc_nested;delay=10} @self']},
            'tc_nested': {'Skills': [command('nested'),
            'summon{type=tc_child_never;amount=1;radius=0} @self',
            'summon{type=tc_child_persistent;amount=1;radius=0} @self', command('afterchild')]}}
