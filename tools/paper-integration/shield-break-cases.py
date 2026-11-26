"""Fixed native/runtime shield-disable scenarios and expected cooldowns."""
def case(key, arguments='', ticks=100, **options):
    value=dict(key=key, mechanic='shieldbreak', arguments=arguments, ticks=ticks,
               nativeTicks=ticks, hands='main', use='main', target='player',
               replacement='none', lifecycle='none', success=True)
    value.update(options)
    return value

CASES=[
    case('default-main'),
    case('alias-offhand','d=20',20,mechanic='disableshield',hands='off',use='off'),
    case('both-main','duration=40',40,hands='both'),
    case('both-off','duration=35',35,hands='both',use='off'),
    case('alias-priority','d=5;duration=30',30),
    case('last-key','duration=20;DURATION=40',40),
    case('zero','duration=0',0),
    case('negative','duration=-1',0,success=False),
    case('quoted','duration="20"',20,nativeTicks=1),
    case('dynamic-caster','duration=<target.l.y>',180,nativeTicks=1),
    case('malformed','duration=oops',1),
    case('fixed-range','duration=20to20',20),
    case('not-blocking','duration=20',0,use='none',success=False),
    case('no-shield','duration=20',0,hands='none',use='none',success=False),
    case('nonplayer','duration=20',0,use='none',target='cow',success=False),
    case('replace-space','duration=20',20,replacement='space'),
    case('replace-full','duration=20',20,replacement='full'),
    case('changed-hotbar','duration=20',20,replacement='hotbar'),
    case('disable-cleanup','duration=20',20,lifecycle='close'),
]
