"""Every line-specific parameter of the private DH live rig (MC 1.21.11). See PORTING.md."""
import copy
from pathlib import Path

_INSTANCES = Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances')  # read-only copies only
_SERVERS = Path('/home/vox/projects/lss-multi-test/servers')
_LOOM = Path('/home/vox/.gradle/caches/fabric-loom/1.21.11')

# Vanilla 1.21.11 data/minecraft/dimension_type/overworld.json (the environment-attribute
# schema already, but without 26.x's ambient_light_color attribute and default_clock).
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
        'minecraft:visual/cloud_color': '#ccffffff',
        'minecraft:visual/cloud_height': 192.33,
        'minecraft:visual/fog_color': '#c0d8ff',
        'minecraft:visual/sky_color': '#78a7ff'},
    'coordinate_scale': 1.0,
    'has_ceiling': False,
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
    'minecraft': '1.21.11',
    'java': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java',
    'javac': '/usr/lib/jvm/java-21-openjdk-amd64/bin/javac',
    'java_release': '21',
    # Official DH 3.3.3 for 1.21.11 (combined jar) accepts any NeoForge and Fabric Loader >= 0.17.3;
    # the rig's official server branch stays NeoForge (the cached neoforge-1.21.11 server).
    'neoforge': '21.11.45',
    'neoforge_server': _SERVERS / 'neoforge-1.21.11',
    'neoforge_server_args': '@libraries/net/neoforged/neoforge/21.11.45/unix_args.txt',
    # Paper 1.21.11 build 132 (c5eb079).
    'paper_server': _SERVERS / 'paper-1.21.11',
    'paper_sha256': '5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba',
    'dh_support_plugin': Path('/home/vox/projects/lss-stutter-repro/server/plugins/DistantHorizonsSupport-0.14.0.jar'),
    'dh_support_sha256': '3dd299bb8dc6fb38cde9bec54e9a87b51161ed1e44b9245f637513472c1985ca',
    'dh_default': Path('/home/vox/projects/voxel-server-support/build/dh-ports/assets/dh-1.21.11.jar'),
    'dh_default_sha256': '96c2e8879dc2168d4979af96107f959a0af2f64f9dfb4847605f2c0bf0aa7468',
    'client_mods': {
        # No NeoForge client route to Voxy on 1.21.11: the rig runs Fabric clients only.
        'neoforge': None,
        'fabric': _INSTANCES / 'lss-test-1.21.11/minecraft/mods',
    },
    'client_mod_pins': {
        'neoforge': [],
        'fabric': [
            ('voxy-0.2.9-alpha-1.21.11.jar', '7a640f888c11f33d765dd6b7e6666538d04c1a5ea0e2160bd02acfa5ad6f6ef4'),
            ('fabric-api-0.141.1+1.21.11.jar', '6a577f83bd8b33c9404127d16531acc9c4195f47de0395957c9d4cd09d7b98c6'),
            ('sodium-fabric-0.8.2+mc1.21.11.jar', 'd89173494ac89676d43cc9c330607fc07cf44a145e84e8d07374c2b370f0b87c'),
        ],
    },
    'prism_components': {
        'neoforge': [],
        'fabric': [{'uid': 'org.lwjgl3', 'version': '3.3.3'}, {'uid': 'net.minecraft', 'version': '1.21.11'},
                   {'uid': 'net.fabricmc.intermediary', 'version': '1.21.11'},
                   {'uid': 'net.fabricmc.fabric-loader', 'version': '0.18.4'}],
    },
    'fabric_loader_floor': '>=0.18.4',
    # 1.21.11 introduced the snake_case game rule registry ids (1.21.1: doMobSpawning).
    'mob_spawning_off': 'gamerule spawn_mobs false',
    # 1.21.11 data pack version 94.1: pack.mcmeta uses min_format/max_format (no pack_format).
    'datapack_format': {'min_format': [94, 1], 'max_format': 94},
    'overworld_dimension_type': lambda: copy.deepcopy(_OVERWORLD),
    # 1.21.11 Fabric is obfuscated: the observer compiles against Mojang (loom "named") names
    # and is remapped to intermediary, as on 1.21.1.
    'fabric_remap': {
        'mappings': _LOOM / 'loom.mappings.1_21_11.layered+hash.2198-v2/mappings.tiny',
        'remapper': Path('/home/vox/.gradle/caches/modules-2/files-2.1/net.fabricmc/tiny-remapper/0.10.3/4977f7d5ca1f9be9de6695a9cf4e86e440f72427/tiny-remapper-0.10.3-fat.jar'),
    },
    'fixture_link_libraries': [_SERVERS / 'neoforge-1.21.11/libraries', Path('/home/vox/.local/share/lss-rig-prism-auth/libraries')],
}
