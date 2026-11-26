"""Declared inputs and independent expected recipe values for M06/M07."""
from copy import deepcopy
TABLES = {
    'TradeMixed': {'Drops': ['exp 7', "command{c='scoreboard<&sp>players<&sp>add<&sp>tradeSentinel<&sp>tradeAudit<&sp>1'} 1", 'TradeGem 9']},
    'TradeZeroThen': {'Drops': ['BREAD 0 1', 'DIAMOND 1']},
    'TradeZero': {'Drops': ['DIAMOND 0 1']},
    'TradeFraction': {'Drops': ['DIAMOND 0.5 1']},
    'TradeMerchant': {'Conditions': ['isplayer false'], 'TriggerConditions': ['isplayer false'], 'Drops': ['EMERALD 7']},
    'TradeDenied': {'Conditions': ['isplayer true'], 'Drops': ['DIAMOND 2']},
    'TradeXp': {'Drops': ['exp 7']},
}
ITEM_DEFINITIONS = {'TradeGem': {'Id': 'DIAMOND', 'Options': {'CustomModelData': 4321}}}

def item(material, amount=1, model=0): return dict(material=material, amount=amount, model=model)
def recipe(result=None, ingredients=None, **fields):
    value = dict(result=result or item('STONE'), ingredients=ingredients or [item('STONE')], uses=0,
                 maxUses=2147483647, experienceReward=False, villagerExperience=0, priceMultiplier=0.0,
                 demand=1, specialPrice=1, ignoreDiscounts=False)
    value.update(fields)
    return value
SEED = [recipe(item('BREAD', 2), [item('EMERALD', 3)], maxUses=10, demand=0, specialPrice=0),
        recipe(item('APPLE'), [item('GOLD_INGOT', 2)], maxUses=8, demand=0, specialPrice=0)]

def mutation(key, arguments='', new=None, operation='ADD', slot=0, **options):
    expected=deepcopy(SEED)
    if operation == 'ADD': expected.insert(slot, new or recipe())
    elif operation == 'REPLACE': expected[slot]=new or recipe()
    elif operation == 'REMOVE': expected.pop(slot)
    row=dict(key=key, kind='mutate', mechanic='addTrade', arguments=arguments, merchant='villager',
             profession='NITWIT', target='merchant', expected=expected, nativeResult='SUCCESS')
    row.update(options)
    return row

def ui(key, arguments='', **options):
    row=dict(key=key, kind='open', mechanic='opentrades', arguments=arguments, merchant='villager',
             profession='NITWIT', target='receiver', caster='merchant', takeover=False, real=True,
             opens=True, expected=deepcopy(SEED), nativeResult='SUCCESS')
    row.update(options)
    return row

CASES=[
    mutation('default'),
    *[mutation('alias-'+name.lower(), mechanic=name) for name in ('setTrade','removeTrade','replaceTrade')],
    mutation('insert-middle', 'slot=1;i=EMERALD 4;r=DIAMOND 2', recipe(item('DIAMOND',2),[item('EMERALD',4)]),slot=1),
    mutation('append', 'index=2;r=GOLD_INGOT 3', recipe(item('GOLD_INGOT',3)),slot=2),
    mutation('replace', 'mode=replace;s=1;r=DIAMOND 4', recipe(item('DIAMOND',4)),operation='REPLACE',slot=1),
    mutation('remove', 'm=remove;s=1',operation='REMOVE',slot=1),
    mutation('all-fields', 'i1=EMERALD 5;i2=GOLD_INGOT 2;r=DIAMOND 3;u=6;dropExp=true;vexp=9;multiplier=0.25;d=4;special=-2;discounts=true',
             recipe(item('DIAMOND',3),[item('EMERALD',5),item('GOLD_INGOT',2)],maxUses=6,experienceReward=True,villagerExperience=9,priceMultiplier=.25,demand=4,specialPrice=-2,ignoreDiscounts=True)),
    mutation('alias-priority', 'm=REMOVE;action=ADD;index=1;slot=0;i=APPLE;ingredient=EMERALD 2;r=BREAD 3', recipe(item('BREAD',3),[item('EMERALD',2)])),
    mutation('repeated-key', 'result=APPLE;RESULT=DIAMOND 4', recipe(item('DIAMOND',4))),
    mutation('quoted-statics', 'slot="1";uses="4";multiplier="2";demand=oops;exp="true"'),
    mutation('unknown-mode', 'action=SET', operation='NONE'),
    mutation('bad-remove-index', 'mode=REMOVE;slot=8',operation='NONE',nativeResult='IndexOutOfBoundsException'),
    mutation('bad-add-index', 'slot=-1',operation='NONE',nativeResult='IndexOutOfBoundsException'),
    mutation('bad-replace-index', 'mode=REPLACE;slot=8',operation='NONE',nativeResult='IndexOutOfBoundsException'),
    mutation('existing-profession', 'r=DIAMOND',recipe(item('DIAMOND')), profession='FARMER'),
    mutation('wandering', 'r=EMERALD 2',recipe(item('EMERALD',2)),merchant='wandering'),
    mutation('wrong-entity', 'r=DIAMOND',operation='NONE',target='cow',nativeResult='INVALID_TARGET'),
    mutation('table-first-item', 'result=TradeMixed 4', recipe(item('DIAMOND',4,4321))),
    mutation('custom-item', 'result=TradeGem 2',recipe(item('DIAMOND',2,4321))),
    mutation('table-dropper-trigger', 'result=TradeMerchant 3',recipe(item('EMERALD',3))),
    mutation('zero-generated', 'result=TradeZero 2',operation='NONE',nativeResult='IllegalArgumentException'),
    mutation('fractional-generated', 'result=TradeFraction 3',operation='NONE',nativeResult='IllegalArgumentException'),
    mutation('no-item', 'result=TradeXp',operation='NONE',nativeResult='IllegalArgumentException'),
    mutation('denied-table', 'result=TradeDenied',operation='NONE',nativeResult='IllegalArgumentException'),
    mutation('dynamic-amount', 'result=DIAMOND <target.l.y>',recipe(item('DIAMOND',175)),nativeExpected=[recipe(item('DIAMOND')), *deepcopy(SEED)]),
    mutation('quoted-amount', 'result=DIAMOND "3"',recipe(item('DIAMOND',3)),nativeExpected=[recipe(item('DIAMOND')), *deepcopy(SEED)]),
    mutation('fixed-range', 'result=DIAMOND 2to2',recipe(item('DIAMOND',2))),
    mutation('zero-before-other', 'result=TradeZeroThen 2',recipe(item('DIAMOND',2))),
    mutation('unemployed-add',profession='NONE',expected=[recipe()]),
    mutation('unemployed-remove','mode=REMOVE',operation='NONE',profession='NONE',expected=[],nativeResult='IndexOutOfBoundsException'),
    mutation('unemployed-unknown','mode=SET',operation='NONE',profession='NONE',expected=[]),
    mutation('unemployed-index','slot=1',operation='NONE',profession='NONE',expected=[],nativeResult='IndexOutOfBoundsException'),
    ui('open-real'), ui('open-copy','real=false', real=False),
    ui('open-takeover',takeover=True), ui('copy-takeover','real=false',real=False,takeover=True),
    ui('open-direction',caster='receiver',target='merchant',opens=False),
    ui('open-wandering',merchant='wandering',opens=False),
    ui('open-nonplayer',target='cow',opens=False),
    ui('open-alias','real=true;realTrade=false',real=False,mechanic='trade'),
    ui('open-quoted-boolean','real="true"',real=False,mechanic='opentrade'),
]
