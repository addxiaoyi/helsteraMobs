"""Independent scoreboard source cases and pinned-native expected mutations."""
CASES = []

def case(key, mechanic, arguments='', *, native=3, runtime=None, recipient='mob', seed=7,
         result='SUCCESS', holder=None, objective='ordinary', suffix=''):
    CASES.append(dict(key=key, mechanic=mechanic, arguments=arguments, native=native,
                      runtime=native if runtime is None else runtime, recipient=recipient, seed=seed,
                      result=result, holder=holder, objective=objective, suffix=suffix))

for name in ('setGlobalScore', 'sgs', 'setMobScore', 'sms', 'setTargetScore', 'sts'):
    case('alias-' + name, name, 'v=3')
for name in ('modifyGlobalScore', 'mgs', 'modifyMobScore', 'mms', 'modifyTargetScore', 'mts'):
    case('alias-' + name, name, 'v=3', native=10)
case('alias-modifyScore', 'modifyScore', 'v=3')
for family in ('modifyGlobalScore', 'modifyMobScore', 'modifyTargetScore', 'modifyScore'):
    for operation, value in [('SET',3),('ADD',10),('SUBTRACT',4),('MULTIPLY',21),('DIVIDE',2),('MOD',1)]:
        case(f'{family}-{operation}', family, f'v=3;a={operation}', native=value)
    fallback = 3 if family == 'modifyScore' else 10
    case(f'{family}-invalid-action', family, 'v=3;action=invalid', native=fallback)
    case(f'{family}-divide-zero', family, 'v=0;a=DIVIDE', native=7, result='ArithmeticException')
    case(f'{family}-remainder-zero', family, 'v=0;a=MOD', native=7, result='ArithmeticException')
for name in ('setGlobalScore','setMobScore','modifyGlobalScore','modifyMobScore'):
    expected = 0 if name.startswith('set') else 7
    case(name+'-default', name, native=expected)
    case(name+'-invalid', name, 'value=bad;v=3', native=expected)
    case(name+'-quoted-static', name, 'value="3"', native=expected)
case('target-set-default', 'setTargetScore', native=0)
case('target-set-quoted', 'setTargetScore', 'v="3"', native=1, runtime=3)
case('target-set-decimal', 'setTargetScore', 'v=3.9', native=1, runtime=3)
case('target-set-placeholder', 'setTargetScore', 'v=<target.health>', native=1, runtime=10)
case('target-set-math', 'setTargetScore', 'v=(2+3)', native=1, runtime=5)
case('target-set-invalid', 'setTargetScore', 'v=bad', native=1)
case('target-set-single-range-to', 'setTargetScore', 'v=3to3', native=3)
case('target-set-single-range-hyphen', 'setTargetScore', 'v=3-3', native=3)
case('target-set-negative-hyphen', 'setTargetScore', 'v=-3-7', native=1)
case('target-set-reversed-range', 'setTargetScore', 'v=7to3', native=7, result='IllegalArgumentException')
case('target-set-range-overflow', 'setTargetScore', 'v=0to2147483647', native=7, result='IllegalArgumentException')
case('modify-target-missing', 'modifyTargetScore', native=7, result='NullPointerException')
case('modify-target-invalid', 'modifyTargetScore', 'v=2.5', native=7, result='NumberFormatException')
case('modify-target-placeholder', 'modifyTargetScore', 'v=<target.name>', native=15)
case('modify-entry-default', 'modifyScore', native=0)
case('modify-entry-invalid', 'modifyScore', 'v=2.5', native=7, result='INVALID_CONFIG')
case('modify-entry-quoted', 'modifyScore', 'v="3"', native=3)
case('modify-entry-unset', 'modifyScore', 'a=UNSET', native=7)
case('modify-entry-unset-new', 'modifyScore', 'a=UNSET', native=None, seed=None)
case('entry-alias-priority', 'modifyScore', 'entry=custom;e=other;name=else;n=last;v=3', holder='custom')
case('entry-no-target-context', 'modifyScore', 'entry=<target.uuid>;v=3', holder='<target.uuid>')
case('entry-explicit-trigger-context', 'modifyScore', 'entry=<trigger.uuid>;v=3', holder='trigger')
case('entry-explicit-caster-context', 'modifyScore', 'entry=<caster.uuid>;v=3', holder='caster')
case('objective-target-context', 'setTargetScore', 'v=3', objective='target')
case('objective-no-target-context', 'setGlobalScore', 'v=3', objective='no-target')
case('objective-explicit-trigger-context', 'setGlobalScore', 'v=3', objective='trigger')
for mechanic in ('setMobScore','modifyGlobalScore','modifyMobScore','modifyScore'):
    case(mechanic+'-objective-no-target', mechanic, 'v=3', objective='no-target',
         native=10 if mechanic.startswith('modify') and mechanic!='modifyScore' else 3)
case('entry-no-target-value', 'modifyScore', 'v=<target.name>', native=7, result='INVALID_CONFIG')
case('priority-duplicates', 'modifyGlobalScore', 'v=9;value=bad;VALUE=3;a=SET;action=ADD', native=10)
case('overflow-add', 'modifyGlobalScore', 'v=1', native=-2147483648, seed=2147483647)
case('negative-division', 'modifyGlobalScore', 'v=3;a=DIVIDE', native=-2, seed=-7)
case('negative-remainder', 'modifyGlobalScore', 'v=3;a=MOD', native=-1, seed=-7)
case('new-objective', 'setGlobalScore', 'v=3', seed=None)
case('empty-objective-default', 'setGlobalScore', 'v=3', objective='empty')
case('empty-objective-explicit', 'setGlobalScore', 'objective=;obj=ignored;v=3', objective='explicit-empty')
case('target-player', 'setTargetScore', 'v=3', recipient='player', suffix=' @trigger')
case('modify-player', 'modifyTargetScore', 'v=3', native=10, recipient='player', suffix=' @trigger')
case('target-nonliving', 'setTargetScore', 'v=3', recipient='interaction', suffix=' @trigger')
case('modify-nonliving', 'modifyTargetScore', 'v=3', native=10, recipient='interaction', suffix=' @trigger')
case('global-multiple-targets-once', 'modifyGlobalScore', 'v=3', native=10, recipient='multiple', suffix=' @targets')
case('caster-multiple-targets-once', 'modifyMobScore', 'v=3', native=10, recipient='multiple', suffix=' @targets')
case('target-multiple', 'modifyTargetScore', 'v=3', native=10, recipient='multiple', suffix=' @targets')


def source_cases():
    return {'cases': [{key: value for key, value in sample.items()
                       if key in ('key','mechanic','arguments','recipient','seed','objective','suffix','holder')}
                      for sample in CASES]}


def family(name):
    name = name.lower()
    return {'sgs':'setglobalscore','sms':'setmobscore','sts':'settargetscore',
            'mgs':'modifyglobalscore','mms':'modifymobscore','mts':'modifytargetscore'}.get(name,name)


def canonical(name):
    return {'setglobalscore':'set-global-score','setmobscore':'set-mob-score','settargetscore':'set-target-score',
            'modifyglobalscore':'modify-global-score','modifymobscore':'modify-mob-score',
            'modifytargetscore':'modify-target-score','modifyscore':'modify-score'}[family(name)]
