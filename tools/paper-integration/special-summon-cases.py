"""Explicit spawning scenarios. Expectations are independent of the production parsers."""
CASES=[]
def case(key,kind,arguments='',targeter='targets',**extra):
    CASES.append(dict(key=key,kind=kind,mechanic={'cloud':'summonCloud','fall':'summonFallingBlock','passenger':'summonPassenger'}[kind],
                      arguments=arguments,targeter=targeter,wait=0,level=1,seed=0,cancel=False,count=1,**extra))
case('cloud_defaults','cloud')
case('cloud_location','cloud','particle=cloud;type=SPEED;pd=80;level=2;d=35;r=4;drou=-7;rrou=-0.25;rrot=-0.02','origin')
case('cloud_alias_priority','cloud','p=flame;particle=cloud;t=SPEED;effect=JUMP;type=SLOW;pd=90;potionduration=120;l=7;lvl=3;level=2;d=40;duration=70;r=8;radius=3')
case('cloud_typo','cloud','p=cloud;durationreductiononuse=99;durationreudctiononuse=-6;drou=-2')
case('cloud_signed_changes','cloud','p=cloud;drou=4;rrou=0.5;rrot=0.125')
case('cloud_dust_rgba','cloud','c=#12345678;size=2')
case('cloud_dust_transition','cloud','p=dustcolor;fc=#112233;tc=#445566;size=0.5')
case('cloud_entity_color','cloud','p=spellmobambient;c=blue')
case('cloud_item','cloud','p=item;m=DIAMOND')
case('cloud_block_data','cloud','p=falling_dust;m=RED_SAND')
case('cloud_shriek','cloud','p=shriek')
case('cloud_unknown_particle','cloud','p=not_a_particle')
case('cloud_integer_range','cloud','p=cloud;d=31to31;pd=80to80;l=2to2;r=4to4')
case('cloud_float_range','cloud','p=cloud;rrou=-0.5to-0.5;rrot=0.125to0.125')
case('cloud_trigger_placeholder','cloud','p=cloud;d=<target.l.y>',difference='public_integer')
case('cloud_invalid_effect','cloud','p=flame;type=NOT_A_POTION')
case('cloud_sculk_native_error','cloud','p=sculk_charge;rot=180')
case('cloud_bad_item','cloud','p=item;m=NOT_A_MATERIAL')
case('cloud_alias_collision','cloud','p=dust')
case('cloud_removed_particle','cloud','p=barrier',constructorError=True)
case('cloud_nonliving','cloud','p=cloud','trigger')
case('cloud_expiry','cloud','p=cloud;d=20;r=2'); CASES[-1]['wait']=45
case('cloud_effect_application','cloud','p=cloud;type=SLOW;pd=100;l=2;d=120;r=3;drou=-10;rrou=-0.25'); CASES[-1]['wait']=30
case('fall_defaults','fall')
case('fall_namespaced','fall','m=minecraft:red_sand','origin')
case('fall_state','fall','type=oak_log[axis=x]')
case('fall_priority','fall','m=stone;mat=gravel;material=sand;t=red_sand;type=oak_log;types=stone_bricks')
case('fall_invalid','fall','t=NOT_A_BLOCK'); CASES[-1]['count']=0
case('fall_air','fall','t=AIR')
case('fall_dynamic_native_inert','fall','t=<target.name>'); CASES[-1]['count']=0
case('fall_nonliving','fall','t=GRAVEL','trigger')
case('fall_landing','fall','t=SAND','origin'); CASES[-1]['wait']=50
case('fall_item_drop','fall','t=RED_SAND','origin',torch=True); CASES[-1]['wait']=50
case('passenger_default','passenger','p=AuditRider')
case('passenger_inherited_level','passenger','type=AuditRider'); CASES[-1]['level']=4
case('passenger_replace','passenger','rider=AuditRider'); CASES[-1]['seed']=2
case('passenger_stack','passenger','r=AuditRider;s=true'); CASES[-1]['seed']=2
case('passenger_stack_empty','passenger','p=AuditRider;stack=true')
case('passenger_missing','passenger','p=MissingRider'); CASES[-1]['count']=0
case('passenger_alias_priority','passenger','t=MissingRider;p=MissingRider;passenger=AuditRider','origin')
case('passenger_mount_cancelled','passenger','p=AuditRider'); CASES[-1]['cancel']=True
case('passenger_wolf_owner','passenger','p=AuditWolf')
case('passenger_cat_not_tamed','passenger','p=AuditCat')
MOBS={
    'HelsteraRadiusAuditMob':dict(Type='COW',Options=dict(NoAI=True,NoGravity=True,Despawn='PERSISTENT')),
    **{name:dict(Type=kind,Health=20,Options=dict(NoAI=True,NoGravity=True,Despawn='PERSISTENT',Silent=True))
       for name,kind in [('AuditRider','PIG'),('AuditWolf','WOLF'),('AuditCat','CAT')]}
}
