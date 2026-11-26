"""Finite deterministic native display transformation cases."""
def case(key, arguments='', **options):
    value = dict(key=key, mechanic='displaytransformation', arguments=arguments,
                 display='block', selector='trigger', success=True, action='SET',
                 component='translation', values=[0, 0, 0])
    value.update(options)
    return value


CASES = []
for component in ('translation', 'scale', 'left_rotation', 'right_rotation'):
    for action in ('SET', 'ADD', 'MULTIPLY', 'DIVIDE'):
        values = [2, 1, -1, 3] if 'rotation' in component else [2, 4, -2]
        CASES.append(case(component + '-' + action.lower(),
                          'type=' + component + ';action=' + action + ';value=' + ','.join(map(str, values)),
                          component=component, action=action, values=values))
CASES += [
    case('default'),
    case('text-alias', 'a=ADD;tt=scale;val=1,2,3', mechanic='settransformation', display='text',
         action='ADD', component='scale', values=[1, 2, 3]),
    case('item-alias', 'a=SET;transformation=translation;val=4,5,6', mechanic='transformation', display='item', values=[4, 5, 6]),
    case('priority-last-key', 'a=DIVIDE;action=ADD;ACTION=SET;tt=scale;type=left_rotation;transformation=right_rotation;transformationtype=translation;val=1,2,3;value=4,5,6', values=[4, 5, 6]),
    case('invalid-enums', 'a=oops;type=oops;value=4,5,6', values=[4, 5, 6]),
    case('dynamic-target', 'value=<target.l.y>,5,6', values=[170, 5, 6], nativeValues=[1, 5, 6]),
    case('quoted-vector', 'value="4,5,6"', values=[4, 5, 6], nativeValues=[1, 5, 1]),
    case('malformed-number', 'value=oops,5,6', values=[1, 5, 6]),
    case('fixed-range', 'value=2to2,3-3,4', values=[2, 3, 4]),
    case('extra-components', 'value=4,5,6,7', values=[4, 5, 6]),
    case('no-target-caster', 'value=4,5,6', selector='none', values=[4, 5, 6]),
    case('wrong-entity', 'value=4,5,6', selector='cow', success=False),
    case('short-vector', 'value=1,2', success=False, invalid=True),
]
