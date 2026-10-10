"""Every line-specific parameter of the private DH live rig (MC 26.1.2). See PORTING.md."""
import copy
from pathlib import Path

_INSTANCES = Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances')  # read-only copies only
_SERVERS = Path('/home/vox/projects/lss-multi-test/servers')

# Vanilla 26.1.2 data/minecraft/dimension_type/overworld.json (26.x environment-attribute schema;
# byte-identical in content to 26.2's).
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
    'minecraft': '26.1.2',
    'java': '/home/vox/.local/jdk/jdk-25.0.2+10/bin/java',
    'javac': '/home/vox/.local/jdk/jdk-25.0.2+10/bin/javac',
    'java_release': '25',
    # Official DH 3.3.3 for 26.1.x (combined jar) only needs Fabric Loader >= 0.18.5, but the
    # rig's official server branch is NeoForge (as on 1.21.1/26.2), so it stays NeoForge here.
    'neoforge': '26.1.2.95',
    'neoforge_server': _SERVERS / 'neoforge-26.1',
    'neoforge_server_args': '@libraries/net/neoforged/neoforge/26.1.2.95/unix_args.txt',
    # Paper 26.1.2 build 74.
    'paper_server': _SERVERS / 'paper-26.1',
    'paper_sha256': '1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7',
    'dh_support_plugin': Path('/home/vox/projects/lss-stutter-repro/server/plugins/DistantHorizonsSupport-0.14.0.jar'),
    'dh_support_sha256': '3dd299bb8dc6fb38cde9bec54e9a87b51161ed1e44b9245f637513472c1985ca',
    'dh_default': Path('/home/vox/projects/voxel-server-support/build/dh-ports/assets/dh-26.1.2.jar'),
    'dh_default_sha256': '3df8273f35f887cc46b9faacb73372d7b9e743b764052a323d1a61bf6558bf83',
    'client_mods': {
        'neoforge': _INSTANCES / 'lss-test-neo-26.1/minecraft/mods',
        'fabric': _INSTANCES / 'lss-test-26.1/minecraft/mods',
    },
    'client_mod_pins': {
        'neoforge': [
            # The 26.1 NeoForge rig instance pairs Foxy 1.1.0 with Voxy 0.2.16 and Sodium 0.8.12.
            ('voxy-0.2.16-beta-26.1.2.jar', '0b90d266721142d7d5c4a69e0339dd7563b9e8faabb8cab2d72c2686d25bf959'),
            ('foxy-26.1.2-NeoForge-1.1.0.jar', '3725bd71f47f59314015846436a2398696bab8f2926729cd6d747143a784f18f'),
            ('sodium-neoforge-0.8.12+mc26.1.2.jar', '670df7ba4d4fcd3e0f2fde35fd967defa94f0b07376e4554126cc79afa44fec6'),
        ],
        'fabric': [
            ('voxy-0.2.18-beta-26.1.2.jar', '17ebb7ff9f311f66e9a53b6ff57c7ef4c10bb72627d5f7d6fb34b68f811ed7d5'),
            ('fabric-api-0.155.2+26.1.2.jar', '7196f2d8700cf906676db98cc13cd4b98267c4ca4ba3e8c516e62070b21a5a3a'),
            ('sodium-fabric-0.9.1+mc26.1.2.jar', '56d2eb7e8fb7f129286ab9cc89f460fe5a4cea340081ef3c50ac65bce9501b1d'),
        ],
    },
    'prism_components': {
        'neoforge': [{'uid': 'org.lwjgl3', 'version': '3.4.1'}, {'uid': 'net.minecraft', 'version': '26.1.2'},
                     {'uid': 'net.neoforged', 'version': '26.1.2.48-beta'}],
        'fabric': [{'uid': 'org.lwjgl3', 'version': '3.4.1'}, {'uid': 'net.minecraft', 'version': '26.1.2'},
                   {'uid': 'net.fabricmc.intermediary', 'version': '26.1.2'},
                   {'uid': 'net.fabricmc.fabric-loader', 'version': '0.19.3'}],
    },
    'fabric_loader_floor': '>=0.19.3',
    # 1.21.11+ renamed game rules to snake_case registry ids (1.21.1: doMobSpawning).
    'mob_spawning_off': 'gamerule spawn_mobs false',
    # 26.1.2 data pack version 101.1: pack.mcmeta uses min_format/max_format (no pack_format).
    'datapack_format': {'min_format': [101, 1], 'max_format': 101},
    'overworld_dimension_type': lambda: copy.deepcopy(_OVERWORLD),
    # Fabric 26.x runs Mojang names: the Fabric observer needs no intermediary remap.
    'fabric_remap': None,
    'fixture_link_libraries': [_SERVERS / 'neoforge-26.1/libraries', Path('/home/vox/.local/share/lss-rig-prism-auth/libraries')],
}
