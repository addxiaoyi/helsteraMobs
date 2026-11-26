"""Independently declared M04b inputs and expected server outcomes."""
COMMAND = "command{c='scoreboard<&sp>players<&sp>add<&sp><trigger.name><&sp>grantAudit<&sp><drop.amount>'} 3"
TABLES = {
    'MixedRewards': {'Drops': ['DIAMOND 2', 'exp 5', COMMAND]},
    'ChildRewards': {'Drops': ['EMERALD 2', 'exp 3']},
    'NestedRewards': {'Drops': ['ChildRewards 2']},
    'OnlyXp': {'TotalItems': 2, 'Drops': ['exp 2']},
    'MaxRewards': {'MaxItems': 1, 'Drops': ['DIAMOND 2', 'EMERALD 4']},
    'NoPlayer': {'Conditions': ['isplayer false'], 'Drops': ['DIAMOND 8']},
    'PlayerCause': {'TriggerConditions': ['isplayer true'], 'Drops': ['DIAMOND 4']},
}
ITEM_DEFINITIONS = {'GrantGem': {'Id': 'DIAMOND', 'Options': {'CustomModelData': 4321}}}

def case(key, items, expected=None, xp=0, score=0, fake=False, **options):
    row = dict(key=key, mechanic='giveitemfromtarget', items=items,
               arguments='items='+items+('' if fake else ';fl=false'),
               target='observer', source='player', inventory='empty', expected=expected or {},
               xp=xp, score=score, fake=fake)
    row.update(options)
    return row

CASES = [
    case('default-visual', 'DIAMOND 2', {'DIAMOND':2}, fake=True),
    case('inline-mixed', 'DIAMOND 2,exp 5', {'DIAMOND':2}, xp=5),
    case('alias-givefromtarget', 'EMERALD 3', {'EMERALD':3}, mechanic='givefromtarget'),
    case('alias-giveitemsfromtarget', 'EMERALD 3', {'EMERALD':3}, mechanic='giveitemsfromtarget'),
    case('alias-itemgivefromtarget', 'EMERALD 3', {'EMERALD':3}, mechanic='itemgivefromtarget'),
    case('item-alias', 'DIAMOND 2', {'DIAMOND':2}, arguments='item=DIAMOND 2;fl=false'),
    case('i-alias', 'DIAMOND 2', {'DIAMOND':2}, arguments='i=DIAMOND 2;fl=false'),
    case('alias-priority', 'DIAMOND 2', {'DIAMOND':2}, arguments='items=DIAMOND 2;i=EMERALD 9;fl=false'),
    case('last-key', 'DIAMOND 3', {'DIAMOND':3}, arguments='items=EMERALD 9;ITEMS=DIAMOND 3;fl=false'),
    case('full-overflow', 'DIAMOND 2', {'DIAMOND':2}, inventory='full'),
    case('partial-overflow', 'DIAMOND 5', {'DIAMOND':5}, inventory='partial'),
    case('entity-origin', 'EMERALD 2', {'EMERALD':2}, target='cow', fake=True),
    case('nonliving-origin', 'EMERALD 2', {'EMERALD':2}, target='nonliving', fake=True),
    case('location-origin', 'EMERALD 2', {'EMERALD':2}, target='location', fake=True),
    case('nonplayer-caster', 'DIAMOND 2,exp 5', source='cow'),
    case('zero-chance', 'DIAMOND 2 0'),
    case('zero-shorthand', 'DIAMOND 0'),
    case('fractional-item', 'DIAMOND 2.75 1', {'DIAMOND':2}),
    case('fractional-experience', 'exp 1.75 1,exp 2.75 1', xp=4),
    case('quoted-inline', '"DIAMOND 2,exp 5"', {'DIAMOND':2}, xp=5),
    case('quoted-comma', '"DIAMOND 2<&cm>exp 5"', {'DIAMOND':2}, xp=5),
    case('command-context', COMMAND, score=3),
    case('named-mixed', 'MixedRewards', {'DIAMOND':2}, xp=5, score=3),
    case('named-nested', 'NestedRewards', {'EMERALD':4}, xp=6),
    case('named-weighted-xp', 'OnlyXp', xp=4),
    case('named-max', 'MaxRewards', {'DIAMOND':2}),
    case('dropper-rejected', 'NoPlayer'),
    case('caster-is-cause', 'PlayerCause', {'DIAMOND':4}),
    case('custom-item', 'GrantGem 3', {'DIAMOND':3}, fake=True, model=4321),
    case('custom-overflow', 'GrantGem 3', {'DIAMOND':3}, inventory='full', model=4321),
]

def source_cases(): return {'cases': CASES, 'tables': TABLES, 'items': ITEM_DEFINITIONS}
