"""Every line-specific parameter of the private DH live rig (MC 26.2). See PORTING.md."""
import copy
from pathlib import Path

_INSTANCES = Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances')  # read-only copies only
_SERVERS = Path('/home/vox/projects/lss-multi-test/servers')

# Vanilla 26.2 data/minecraft/dimension_type/overworld.json (26.x environment-attribute schema).
_OVERWORLD = {
    'ambient_light': 0.0,
    'attributes': {
        'minecraft:audio/ambient_sounds': {'mood': {'block_search_extent': 8, 'offset': 2.0, 'sound': 'minecraft:ambient.cave', 'tick_delay': 6000}},
        'minecraft:audio/background_music': {
            'creative': {'max_delay': 24000, 'min_delay': 12000, 'sound': 'minecraft:music.creative'},
            'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': 'minecraft:music.game'}},
        'minecraft:gameplay/bed_rule': {'can_set_spawn': 'always', 'can_sleep': 'when_dark', 'error_message': {'translate': 'block.minecraft.bed.no_sleep'}},
        'minecraft:gameplay/nether_portal_spawns_piglin': True,
        'minecraft:gameplay/respawn_anchor_works': False,
        'minecraft:visual/ambient_light_color': '#0a0a0a',
        'minecraft:visual/cloud_color': '#ccffffff',
        'minecraft:visual/cloud_height': 192.33,
        'minecraft:visual/fog_color': '#c0d8ff',
        'minecraft:visual/sky_color': '#78a7ff'},
    'coordinate_scale': 1.0,
    'default_clock': 'minecraft:overworld',
    'has_ceiling': False,
    'has_ender_dragon_fight': False,
    'has_skylight': True,
    'height': 384,
    'infiniburn': '#minecraft:infiniburn_overworld',
    'logical_height': 384,
    'min_y': -64,
    'monster_spawn_block_light_limit': 0,
    'monster_spawn_light_level': {'type': 'minecraft:uniform', 'max_inclusive': 7, 'min_inclusive': 0},
    'timelines': '#minecraft:in_overworld',
}

LINE = {
    'minecraft': '26.2',
    'java': '/home/vox/.local/jdk/jdk-25.0.2+10/bin/java',
    'javac': '/home/vox/.local/jdk/jdk-25.0.2+10/bin/javac',
    'java_release': '25',
    # Official DH 3.3.3 (combined jar) needs Fabric Loader >= 0.19.5; the cached Fabric
    # 26.2 server has 0.19.3, so the official server runs on NeoForge (as on 1.21.1).
    'neoforge': '26.2.0.59',
    'neoforge_server': _SERVERS / 'neoforge-26.2',
    'neoforge_server_args': '@libraries/net/neoforged/neoforge/26.2.0.59/unix_args.txt',
    'paper_server': _SERVERS / 'paper-26.2',
    'paper_sha256': 'defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16',
    'dh_support_plugin': Path('/home/vox/projects/lss-stutter-repro/server/plugins/DistantHorizonsSupport-0.14.0.jar'),
    'dh_support_sha256': '3dd299bb8dc6fb38cde9bec54e9a87b51161ed1e44b9245f637513472c1985ca',
    'dh_default': Path('/home/vox/projects/voxel-server-support/build/dh-ports/assets/dh-26.2.jar'),
    'dh_default_sha256': 'abd4b8504c703d6a4517c154fdcef8c9a9da12599a653b3bedcfb100788cd2be',
    'client_mods': {
        'neoforge': _INSTANCES / 'lss-test-neo-26.2/minecraft/mods',
        'fabric': _INSTANCES / 'lss-test-26.2/minecraft/mods',
    },
    'client_mod_pins': {
        'neoforge': [
            ('voxy-0.2.18-beta.jar', 'dd9e10d2110879f07c1ce9bdf1945f3470b32e83977f094005ee8c642e8128d5'),
            ('foxy-26.2-NeoForge-1.1.0.jar', '7aec6b1a6309fad31501128c5ec0ec5dbbc02addc8866cf2fba05b0878059ab8'),
            ('sodium-neoforge-0.9.1+mc26.2.jar', '99211fc2a1d91c9beaa569044d191c08c68a1dbf5eb9bf1f5ee8296b9f768c81'),
        ],
        'fabric': [
            ('voxy-0.2.18-beta.jar', 'dd9e10d2110879f07c1ce9bdf1945f3470b32e83977f094005ee8c642e8128d5'),
            ('fabric-api-0.155.2+26.2.jar', 'd6518c770024cbe8a556248f16fcdbb91c6a62f50227a6c3bae8190511e2c1b8'),
            ('sodium-fabric-0.9.1+mc26.2.jar', 'de406c7a0ca5e748dfbe44740278400882a44e3109e2584b243ec02d4003344b'),
        ],
    },
    'prism_components': {
        'neoforge': [{'uid': 'org.lwjgl3', 'version': '3.4.1'}, {'uid': 'net.minecraft', 'version': '26.2'},
                     {'uid': 'net.neoforged', 'version': '26.2.0.59'}],
        'fabric': [{'uid': 'org.lwjgl3', 'version': '3.4.1'}, {'uid': 'net.minecraft', 'version': '26.2'},
                   {'uid': 'net.fabricmc.intermediary', 'version': '26.2'},
                   {'uid': 'net.fabricmc.fabric-loader', 'version': '0.19.3'}],
    },
    'fabric_loader_floor': '>=0.19.3',
    # 1.21.11+ renamed game rules to snake_case registry ids (1.21.1: doMobSpawning).
    'mob_spawning_off': 'gamerule spawn_mobs false',
    # 26.2 data pack version 107.1: pack.mcmeta uses min_format/max_format (no pack_format).
    'datapack_format': {'min_format': [107, 1], 'max_format': 107},
    'overworld_dimension_type': lambda: copy.deepcopy(_OVERWORLD),
    # Fabric 26.x runs Mojang names: the Fabric observer needs no intermediary remap.
    'fabric_remap': None,
    'fixture_link_libraries': [_SERVERS / 'neoforge-26.2/libraries', Path('/home/vox/.local/share/lss-rig-prism-auth/libraries')],
}
