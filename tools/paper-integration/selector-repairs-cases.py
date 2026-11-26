"""Expected public-Paper behavior for scoped AI selector repairs."""
def profiles():
    common = {'can-chase': False, 'can-attack': False, 'can-patrol': False, 'can-flee': False,
              'targets': ['0 clear']}
    goals = {
        'door_on': ['0 breakdoors'], 'clear': ['0 clear'], 'reset': ['0 reset'],
        'flee_players': ['0 clear', '1 fleeplayers'], 'flee_wolves': ['0 clear', '1 fleewolf'],
        'flee_bad': ['0 clear', '1 fleefaction'],
        'random_look': ['0 clear', '1 randomlookaround'],
        'cleared_look': ['0 randomlookaround', '1 clear'],
        'unavailable': ['0 clear', '1 spider', '2 closedoor'],
        'all_then_clear': ['0 float', '1 opendoors', '2 breakdoors', '3 lookatplayers',
                           '4 avoidentity', '5 tempt WHEAT', '6 rangedattack', '7 clear'],
        'all_then_reset': ['0 float', '1 opendoors', '2 breakdoors', '3 lookatplayers',
                           '4 avoidentity', '5 tempt WHEAT', '6 rangedattack', '7 reset'],
    }
    return {'profiles': {name: dict(common, goals=value) for name, value in goals.items()}}


DOORS = {
    'baseline_false': ('false', 'false'), 'apply_false': ('true', 'true'),
    'reload_clear': ('false', 'true'), 'reload_on': ('true', 'true'),
    'repeated_attach': ('true', 'true'), 'detach_false': ('false', 'false'),
    'baseline_true': ('true', 'false'), 'apply_true': ('true', 'true'),
    'reload_reset': ('true', 'true'), 'detach_true': ('true', 'false'),
    'all_then_clear': ('false', 'true'), 'all_then_reset': ('false', 'true'),
}
FLEE = {
    'players': ('player', 'FLEE', -0.336), 'wolves': ('wolf', 'FLEE', 0.336),
    'clear': ('none', 'IDLE', 0), 'reset': ('none', 'IDLE', 0),
    'invalid_faction': ('none', 'IDLE', 0), 'creative': ('none', 'IDLE', 0),
    'spectator': ('none', 'IDLE', 0),
}
LOOK = {'random': True, 'clear': False}
