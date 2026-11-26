"""Independent cases for the pinned public AI target mechanic and scheduler."""
CASES=[]
def case(key, rules, *, caster='COW', wait=0, **extra):
    CASES.append(dict(key=key,caster=caster,stages=[dict(source=source,wait=wait) for source in rules],**extra))
def skill(rule,field='s',name='aitarget',suffix=' @trigger'):
    return f'{name}{{{field}={rule}}}'+suffix
for key,rule,target in [('players','players','Player'),('monsters','monsters','Monster'),('villagers','villagers','Villager'),('golems','iron_golems','IronGolem')]:
    case(key,[skill('clear'),skill('3 '+rule)],wait=40,expectedTarget=key,goalTarget=target)
case('duplicate_rules',[skill('clear'),skill('players'),skill('player')],wait=40)
case('alias_precedence',['aitarget{s=players;target=monsters;targetselector=villagers;aitargetselector=4 ironGolem} @trigger'],wait=40)
case('quoted_rule',[skill('"1 players"')])
case('single_quoted_rule',[skill("'1 players'")])
case('double_space',[skill('1  players')])
case('overflow_priority',[skill('9999999999999 players')])
case('missing_selector',[skill('1')])
case('unknown_rule',[skill('noSuchGoal')])
case('dynamic_string',[skill('<caster.var.rule>')])
case('wrong_field',['runaitargetselector{goal=players} @trigger'])
case('missing_field',['runaitargetselector @trigger'])
case('conditional_default',[skill('nearestIf')],wait=12)
case('conditional_players',[skill('clear'),skill('4 nearestIf{c=[ - isplayer true ];ms=false;mr=false;i=0}')],wait=12,expectedTarget='players')
case('conditional_reject_players',[skill('clear'),skill('nearestIf{c=[ - isplayer false ];i=0}')],wait=12)
case('conditional_unknown',[skill('clear'),skill('nearestIf{c=[ - noSuchCondition true ];i=0}')],wait=12)
case('conditional_unknown_false',[skill('clear'),skill('nearestIf{c=[ - noSuchCondition false ];i=0}')],wait=12,expectedTarget='none')
case('conditional_nonlist',[skill('clear'),skill('nearestIf{c=isplayer false;i=0}')],wait=12,expectedTarget='players')
case('conditional_alias_precedence',[skill('nearestConditionalTarget{c=[ - isplayer true ];interval=8;int=2;i=0;mustsee=false;ms=true;mustreach=true;mr=false}')],wait=40)
for rule in ['nearestOtherFaction','otherFactionMonsters','specificFaction{f=Blue,Green}','nearestSpecificFactionMonsters{f=Blue}']:
    case('faction_'+rule.split('{')[0],[skill('clear'),skill(rule)],wait=12,factions=True)
case('faction_generic_caster',[skill('clear'),skill('specificFaction{f=Blue}')],wait=12,factions=True,generic=True,expectedTarget='none')
case('quoted_faction',[skill('clear'),skill('specificFaction{f="Blue,Red/[]{}=; A-B"}')],wait=12,factions=True,expectedTarget='none')
case('hurt_by',[skill('clear'),skill('damager')],wait=12,damage='caster',expectedTarget='monsters')
case('owner_target',[skill('clear'),skill('ownerTarget')],caster='WOLF',wait=12,damage='owner_attack',expectedTarget='monsters')
case('owner_hurt',[skill('clear'),skill('ownerHurt')],caster='WOLF',wait=12,damage='owner_attack',expectedTarget='monsters')
case('owner_hurt_by',[skill('clear'),skill('ownerAttacker')],caster='WOLF',wait=12,damage='owner_hurt',expectedTarget='monsters')
case('owner_wrong_type',[skill('ownerTarget')])
case('noncreature',[skill('players')],caster='SLIME')
case('player_caster',[skill('clear'),skill('players')],caster='PLAYER')
case('nonliving_caster',[skill('players')],caster='ARMOR_STAND')
case('preemption',[skill('clear'),skill('8 villagers'),skill('1 monsters')],wait=40)
case('equal_priority',[skill('clear'),skill('3 villagers'),skill('3 monsters')],wait=40)
case('clear_running',[skill('clear')],wait=6,sentinel=True)
case('reset_running',[skill('reset')],wait=6,sentinel=True)
case('noninterruptible',[skill('1 players')],wait=40,sentinel=True,interruptible=False)
case('interruptible',[skill('1 players')],wait=40,sentinel=True,interruptible=True)
case('priority_zero',[skill('0 players')],wait=40)
case('profile_order',[],profile=['5 players','0 clear','villagers'])
case('profile_implicit',[],profile=['clear','players','monsters'])
case('profile_rebind',[skill('clear'),skill('2 monsters')],wait=40,profile=['clear','villagers'],rebind=['clear','players'])
case('profile_spacing',[],profile=['clear',' 1 monsters','players'],expectedTarget='none')
case('profile_overflow',[],profile=['players','999999999999999 monsters','villagers'],expectedTarget='players')
for entry in CASES:
    for stage in entry['stages']:
        if stage['wait']==40:stage['wait']=80
MOBS={'HelsteraRadiusAuditMob':{'Type':'COW','Health':20,'Options':{'NoAI':True,'Silent':True,'Invincible':True}},
      'AiWolf':{'Type':'WOLF','Health':20,'Options':{'Silent':True}},
      'AiSlime':{'Type':'SLIME','Health':20,'Options':{'Silent':True}},
      'AiStand':{'Type':'ARMOR_STAND','Options':{'Silent':True}},
      'AiMonster':{'Type':'ZOMBIE','Health':20,'Faction':'Blue','Options':{'Silent':True,'NoAI':True}},
      'AiFactionCow':{'Type':'COW','Health':20,'Faction':'Red','Options':{'Silent':True,'NoAI':True}}}
