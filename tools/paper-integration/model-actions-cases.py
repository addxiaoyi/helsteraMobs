"""Helstera-defined model actions dispatched through pinned native MM, not native parity."""
MECHANICS = {
    'spawn': 'modelspawn{model=example/emberling}',
    'remove': 'modelremove{}',
    'mount': 'modelmount{}',
    'unmount': 'modelunmount{}',
    'heal': 'modelheal{amount=2.5}',
    'heal_default': 'modelheal{}',
    'heal_zero': 'modelheal{a=0}',
    'heal_cap': 'modelheal{amount=1.7976931348623157e308}',
    'bad_mount': 'modelmount{seat=1}',
    'bad_unmount': 'modelunmount{m=other}',
    'bad_negative': 'modelheal{amount=-1}',
    'bad_nan': 'modelheal{amount=NaN}',
    'bad_infinity': 'modelheal{amount=Infinity}',
    'bad_alias': 'modelheal{a=2;amount=2}',
    'bad_unknown': 'modelheal{amount=3;overheal=true}',
}


def native_skills(amount='2.5'):
    return {'ma_' + key: {'Skills': [
        (f'modelheal{{amount={amount}}}' if key == 'heal' else value) + ' @trigger']}
        for key, value in MECHANICS.items()} | {
            'ma_self_spawn': {'Skills': [MECHANICS['spawn'] + ' @self']},
            'ma_self_mount': {'Skills': [MECHANICS['mount'] + ' @self']},
            'ma_self_remove': {'Skills': [MECHANICS['remove'] + ' @self']},
        }


def native_mobs():
    return {'MaCaster': {'Type': 'HUSK', 'Health': 20,
                         'Options': {'NoAI': True, 'Silent': True, 'Despawn': 'PERSISTENT'}}}


EXPECTED = {}


def expect(key, target='target', bindings=1, casterBindings=0, health='10.0', casterHealth='10.0',
           passengers=(), casterPassengers=(), vehicle='none', targetVehicle='none'):
    EXPECTED[key] = dict(target=target, bindings=str(bindings), casterBindings=str(casterBindings),
                         health=health, casterHealth=casterHealth, passengers=passengers,
                         casterPassengers=casterPassengers, vehicle=vehicle,
                         targetVehicle=targetVehicle, primary='true')


for key in ('initial', 'unbound_mount', 'unbound_heal'):
    expect(key, bindings=0)
expect('spawn')
expect('seed_other', passengers=('other',))
for key in ('mount', 'repeat_mount'):
    expect(key, passengers=('caster', 'other'), vehicle='target')
for key in ('unmount', 'repeat_unmount'):
    expect(key, passengers=('other',))
for key in ('unmount_elsewhere', 'mount_elsewhere'):
    expect(key, passengers=('other',), vehicle='vehicle')
expect('self_mount', target='caster', casterBindings=1)
expect('cycle_mount', casterPassengers=('vehicle',), targetVehicle='vehicle')
for key in ('bad_mount', 'bad_unmount', 'bad_negative', 'bad_nan', 'bad_infinity', 'bad_alias', 'bad_unknown'):
    expect(key)
expect('cancel_mount')
expect('mount_after_cancel', passengers=('caster',), vehicle='target')
expect('cancel_unmount', passengers=('caster',), vehicle='target')
expect('unmount_after_cancel')
for key in ('heal_default', 'heal_zero'):
    expect(key, health='11.0')
expect('heal', health='13.5')
expect('heal_cap', health='20.0')
expect('reset_before_async')
expect('async_heal', health='12.5')
expect('non_damageable', target='nonDamageable', health='n/a')
for key in ('disabled_after_cast', 'reenabled_after_cast'):
    expect(key, health='12.5')
expect('reload_heal', health='16.5')
expect('live_reload_heal', health='19.5')
expect('reload_mount', health='19.5', passengers=('caster',), vehicle='target')
expect('reload_unmount', health='19.5')
expect('remove', health='19.5', bindings=0)

DIRECT = {'async_' + name: ('ERROR', 'false') for name in ('mount', 'unmount', 'heal')}
for phase in ('disabled', 'reenabled', 'reloaded_old', 'live_reload_old'):
    DIRECT.update({phase + '_' + name: ('MISSING_COMPATIBILITY', 'true')
                   for name in ('mount', 'unmount', 'heal')})
