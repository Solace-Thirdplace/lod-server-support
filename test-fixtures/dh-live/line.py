"""Every line-specific parameter of the private DH live rig (MC 1.21.10). See PORTING.md."""
import copy
from pathlib import Path

_INSTANCES = Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances')  # read-only copies only
_SERVERS = Path('/home/vox/projects/lss-multi-test/servers')
_LOOM = Path('/home/vox/.gradle/caches/fabric-loom/1.21.10')
# Private NeoForge 21.10.64 server built for this rig (maven.neoforged.net installer
# --install-server; provenance in build/dh-ports/1.21.10/server-provenance).
_NEOFORGE_SERVER = Path('/home/vox/projects/voxel-server-support/build/dh-ports/1.21.10/neoforge-server-1.21.10')

# Vanilla 1.21.10 data/minecraft/dimension_type/overworld.json: the flat-field schema
# (1.21.11 moved these into the environment-attribute schema).
_OVERWORLD = {
    'ambient_light': 0.0,
    'bed_works': True,
    'cloud_height': 192,
    'coordinate_scale': 1.0,
    'effects': 'minecraft:overworld',
    'has_ceiling': False,
    'has_raids': True,
    'has_skylight': True,
    'height': 384,
    'infiniburn': '#minecraft:infiniburn_overworld',
    'logical_height': 384,
    'min_y': -64,
    'monster_spawn_block_light_limit': 0,
    'monster_spawn_light_level': {'type': 'minecraft:uniform', 'max_inclusive': 7, 'min_inclusive': 0},
    'natural': True,
    'piglin_safe': False,
    'respawn_anchor_works': False,
    'ultrawarm': False,
}

LINE = {
    'minecraft': '1.21.10',
    'java': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java',
    'javac': '/usr/lib/jvm/java-21-openjdk-amd64/bin/javac',
    'java_release': '21',
    # Official DH 3.3.3 for 1.21.10 (combined jar) needs NeoForge >= 21.10.6-beta or Fabric
    # Loader >= 0.17.3; no cached 1.21.10 NeoForge/Fabric server exists, so the rig owns a new
    # NeoForge 21.10.64 server (the line's own neoforge_version). Only libraries/ is copied.
    'neoforge': '21.10.64',
    'neoforge_server': _NEOFORGE_SERVER,
    'neoforge_server_args': '@libraries/net/neoforged/neoforge/21.10.64/unix_args.txt',
    # Paper 1.21.10 build 130 (8043efd).
    'paper_server': _SERVERS / 'paper-1.21.10',
    'paper_sha256': '158703f75a26f842ea656b3dc6d75bf3d1ec176b97a2c36384d0b80b3871af53',
    'dh_support_plugin': Path('/home/vox/projects/lss-stutter-repro/server/plugins/DistantHorizonsSupport-0.14.0.jar'),
    'dh_support_sha256': '3dd299bb8dc6fb38cde9bec54e9a87b51161ed1e44b9245f637513472c1985ca',
    'dh_default': Path('/home/vox/projects/voxel-server-support/build/dh-ports/assets/dh-1.21.10.jar'),
    'dh_default_sha256': 'e987a0ab8e5406cf5ec425122b85aa5ce0a3ab9aa56a440e4065cf013f3e72d1',
    'client_mods': {
        # No NeoForge client route to Voxy on 1.21.10: the rig runs Fabric clients only.
        'neoforge': None,
        'fabric': _INSTANCES / 'lss-test-1.21.10/minecraft/mods',
    },
    'client_mod_pins': {
        'neoforge': [],
        'fabric': [
            ('voxy-0.2.9-alpha-1.21.10.jar', 'f52b817f44a4407d68d28243b1cfbac8c7ff29ae1bc0b342ced033959c56e984'),
            ('fabric-api-0.138.4+1.21.10.jar', '4dd5d07067d5cf3e874d2780806c3da223819d39b28d6296c1005a36f2d0a5e4'),
            # Legacy Sodium only (no 0.8 artifact for 1.21.10): the menu probe drives the
            # internal SodiumOptionsGUI page list.
            ('sodium-fabric-0.7.3+mc1.21.10.jar', 'b0bcfa324fa6acf8934b7bd5ad1946e5a003aab86f877d2a64af220fe284b683'),
        ],
    },
    'prism_components': {
        'neoforge': [],
        'fabric': [{'uid': 'org.lwjgl3', 'version': '3.3.3'}, {'uid': 'net.minecraft', 'version': '1.21.10'},
                   {'uid': 'net.fabricmc.intermediary', 'version': '1.21.10'},
                   {'uid': 'net.fabricmc.fabric-loader', 'version': '0.18.4'}],
    },
    'fabric_loader_floor': '>=0.18.4',
    # 1.21.10 still uses the camelCase game rule ids (snake_case arrived in 1.21.11).
    'mob_spawning_off': 'gamerule doMobSpawning false',
    # 1.21.10 data pack version 88.0: pack.mcmeta uses min_format/max_format (1.21.9+ form).
    'datapack_format': {'min_format': [88, 0], 'max_format': 88},
    'overworld_dimension_type': lambda: copy.deepcopy(_OVERWORLD),
    # 1.21.10 Fabric is obfuscated: the observer compiles against Mojang (loom "named") names
    # and is remapped to intermediary, as on 1.21.1.
    'fabric_remap': {
        'mappings': _LOOM / 'loom.mappings.1_21_10.layered+hash.2198-v2/mappings.tiny',
        'remapper': Path('/home/vox/.gradle/caches/modules-2/files-2.1/net.fabricmc/tiny-remapper/0.10.3/4977f7d5ca1f9be9de6695a9cf4e86e440f72427/tiny-remapper-0.10.3-fat.jar'),
    },
    'fixture_link_libraries': [_NEOFORGE_SERVER / 'libraries', Path('/home/vox/.local/share/lss-rig-prism-auth/libraries')],
}
