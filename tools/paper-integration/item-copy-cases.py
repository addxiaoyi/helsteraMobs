"""Source-owned M04a inventory and private-packet acceptance cases."""
SLOTS = {
    'HEAD': ['HEAD', '4', 'HELMET', 'HELM', 'HAT'],
    'CHEST': ['CHEST', '3', 'CHESTPIECE', 'CHESTPLATE', 'BODY'],
    'LEGS': ['LEGS', '2', 'LEGGINGS', 'PANTS'],
    'FEET': ['FEET', '1', 'BOOTS', 'SHOES'],
    'HAND': ['HAND', '0', 'WEAPON', 'MAINHAND'],
    'OFF_HAND': ['OFFHAND', '5', 'SHIELD'],
}
CASES = []

def add(key, mechanic='giveitemfromslot', arguments='slot=HAND', slot='HAND',
        target='player', source='mob', fill='empty', fake=False, copy=False, result='SUCCESS',
        variant='normal', cleanup='normal'):
    CASES.append(dict(key=key, mechanic=mechanic, arguments=arguments, slot=slot, target=target,
                      source=source, fill=fill, fake=fake, copy=copy, result=result,
                      variant=variant, cleanup=cleanup))

for slot, aliases in SLOTS.items():
    for alias in aliases:
        add('slot-' + alias.lower(), arguments='slot=' + alias, slot=slot)
add('short-alias', 'givefromslot', 's=shield;amount=0', slot='OFF_HAND')
add('alias-priority', arguments='s=HEAD;slot=HAND;SLOT=SHIELD', slot='OFF_HAND')
for value in ('NONE', 'MAIN_HAND', 'OFF_HAND', '"HAND"', '', '<caster.var.slot>'):
    add('invalid-' + str(len(CASES)), arguments='slot=' + value + ';s=HEAD', slot='NONE', result='INVALID_CONFIG')
add('default-slot', arguments='', slot='NONE', result='INVALID_CONFIG')
add('wrong-target', target='mob', result='INVALID_TARGET')
add('nonliving-caster', source='nonliving', result='INVALID_CONFIG')
add('full-inventory', fill='full')
add('partial-inventory', fill='partial')
add('metadata-isolation', variant='mutate')
add('damageable-metadata', variant='damageable')
add('self-copy', source='player', target='self', arguments='slot=CHEST', slot='CHEST')
add('fake-true', arguments='slot=HAND;fl=true', fake=True)
add('fake-long-alias', arguments='slot=HAND;dofakelooting=TrUe;fl=false', fake=True)
add('fake-quoted-disabled', arguments='slot=HAND;fl="true"')
add('fake-priority-disabled', arguments='slot=HAND;dofakelooting=false;fl=true')
add('fake-full-inventory', arguments='slot=HAND;fl=true', fill='full', fake=True)
add('fake-disable-cleanup', arguments='slot=HAND;fl=true', fake=True, cleanup='disable')
add('fake-world-change', arguments='slot=HAND;fl=true', fake=True, cleanup='world')
for value in ('ALL', 'HEAD', 'NONE', '"HEAD"'):
    add('equip-' + value.replace('"', 'quoted-').lower(), 'equipCopy', 'slots=' + value,
        copy=True, target='mob')
add('equip-air', 'equipCopy', 's=HEAD;amount=0', copy=True, target='mob', variant='air')
add('equip-nonliving-source', 'equipCopy', 'slots=ALL', copy=True, target='nonliving')
add('equip-nonliving-caster', 'equipCopy', 'slots=ALL', copy=True, source='nonliving', target='mob')

def canonical(name): return 'equip-copy' if name.lower() == 'equipcopy' else 'give-item-from-slot'
def source_cases(): return {'cases': CASES}
