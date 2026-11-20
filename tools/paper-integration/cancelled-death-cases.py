"""Pinned cancelled-death stimuli shared by the runner and independent validator."""
import itertools

CASES = {f'{scenario}_{kind.lower()}_{path}': (scenario, kind, path)
         for scenario, kind, path in itertools.product(
             ('control', 'skillcancelled', 'cancelled', 'death'),
             ('COW', 'ZOMBIE'), ('direct', 'imported'))}

def command(label, timing=''):
    return f'command{{c="cancelleddeathprobe hook native {label} <caster.uuid> <caster.var.Marker>"{timing}}} @self'

def native_mobs():
    mobs = {}
    for key, (scenario, kind, path) in CASES.items():
        skills = [command('pending', ';delay=80') + ' ~onSpawn']
        if scenario == 'control':
            skills += ['skill{s=cd_terminal;delay=10} @self ~onSpawn']
        else:
            skills += ['skill{s=cd_terminal} @self ~onDeath']
            if scenario == 'skillcancelled':
                # MM 5.7.2 dispatches ordinary death mechanics asynchronously.
                # Cancellation must run before the death listener returns.
                skills += ['cancelevent{sync=true} ~onDeath']
        mobs['cd_' + key] = {'Type': kind, 'Health': 20, 'Options': {
            'NoAI': True, 'NoGravity': True, 'Invincible': True,
            'PreventSunburn': True, 'Despawn': 'NEVER', 'ReviveHealth': 20}, 'Skills': skills}
    for mode in ('NEVER', 'PERSISTENT'):
        mobs['cd_child_' + mode.lower()] = {'Type': 'COW', 'Options': {
            'NoAI': True, 'NoGravity': True, 'Invincible': True, 'Despawn': mode},
            'Skills': [f'command{{c="cancelleddeathprobe child native {mode} <caster.uuid>"}} @self ~onSpawn']}
    return mobs

def native_skills():
    return {'cd_terminal': {'Skills': [command('immediate'), command('delay1', ';delay=1'),
            command('delay10', ';delay=10'), command('repeat', ';repeat=2;repeatInterval=5'),
            'skill{s=cd_nested;delay=10} @self']},
            'cd_nested': {'Skills': [command('nested'),
            'summon{type=cd_child_never;amount=1;radius=0} @self',
            'summon{type=cd_child_persistent;amount=1;radius=0} @self', command('afterchild')]}}
