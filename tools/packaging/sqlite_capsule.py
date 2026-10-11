"""Final-artifact SQLite isolation gate. Capsule identity is pinned independently of its descriptor."""
import hashlib
import io
from pathlib import Path
import re
import struct
import zipfile
from tools.packaging.classfile import class_name

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'dev/vox/lss/internal/jdbc/'
CAPSULE = PREFIX + 'sqlite-jdbc.jar.bin'
BRIDGE = PREFIX + 'bridge.class.bin'
DESCRIPTOR = PREFIX + 'driver.properties'
LICENSE = 'META-INF/maven/org.xerial/sqlite-jdbc/LICENSE'
NATIVES = tuple(f'org/sqlite/native/{os}/{cpu}/{lib}'
                for os, lib in [('Linux', 'libsqlitejdbc.so'), ('Linux-Musl', 'libsqlitejdbc.so'),
                                ('Windows', 'sqlitejdbc.dll'), ('Mac', 'libsqlitejdbc.dylib')]
                for cpu in ('x86_64', 'aarch64'))


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


PINS = properties((ROOT / 'gradle/sqlite-runtime.properties').read_text())


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archives(data, label='', depth=0):
    if depth > 8:
        raise ValueError('nested archive depth exceeds bound')
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        yield label, jar
        for entry in jar.infolist():
            if entry.filename.endswith('.jar'):
                if entry.file_size > 256 * 1024 * 1024:
                    raise ValueError('nested jar exceeds bound')
                yield from archives(jar.read(entry), label + entry.filename + '!/', depth + 1)


def check(jar_path, problems):
    """Exactly one capsule/bridge/descriptor, no public SQLite classes/providers/dependencies."""
    base = Path(jar_path).name
    found = []
    try:
        for label, jar in archives(Path(jar_path).read_bytes()):
            names = jar.namelist()
            if len(names) != len(set(names)):
                problems.append(f'{base}!{label}: duplicate ZIP entries')
            for name in names:
                normalized = re.sub(r'^META-INF/versions/[0-9]+/', '', name)
                if (normalized.startswith('org/sqlite/') or normalized == 'dev/vox/lss/jdbc/PrivateSqliteBridge.class'
                        or (normalized.endswith('.class') and '/sqlite/' in normalized and normalized.startswith('dev/vox/lss/'))):
                    problems.append(f'{base}!{label}: exposed or relocated SQLite class/resource {name}')
                if normalized.endswith('.class') and b'org/sqlite/' in jar.read(name):
                    problems.append(f'{base}!{label}: ordinary class/module exposes or links SQLite: {name}')
                if normalized == 'module-info.class' and b'org.xerial.sqlitejdbc' in jar.read(name):
                    problems.append(f'{base}!{label}: ordinary SQLite module descriptor')
                if name == 'META-INF/MANIFEST.MF':
                    manifest = re.sub(r'\r?\n ', '', jar.read(name).decode('utf-8'))
                    if re.search(r'^Automatic-Module-Name:\s*[^\r\n]*sqlite', manifest, re.I | re.M):
                        problems.append(f'{base}!{label}: ordinary SQLite automatic module')
                if name.endswith('.jar') and 'sqlite' in name.lower():
                    problems.append(f'{base}!{label}: SQLite must not be a discoverable nested jar: {name}')
                if name.startswith(PREFIX) and name not in (CAPSULE, BRIDGE, DESCRIPTOR) and not name.endswith('/'):
                    problems.append(f'{base}!{label}: unexpected private SQLite resource {name}')
                if name == 'META-INF/services/java.sql.Driver' and b'org.sqlite' in jar.read(name):
                    problems.append(f'{base}!{label}: exposed SQLite JDBC provider')
                if name in ('fabric.mod.json', 'META-INF/jarjar/metadata.json', 'META-INF/neoforge.mods.toml',
                            'META-INF/sponge_plugins.json'):
                    metadata = jar.read(name).decode('utf-8')
                    if re.search(r'sqlite[-_]jdbc|org\.xerial|internal/jdbc', metadata, re.I):
                        problems.append(f'{base}!{label}: loader metadata exposes/requires SQLite')
            private = [name for name in (CAPSULE, BRIDGE, DESCRIPTOR) if name in names]
            if private:
                if len(private) != 3:
                    problems.append(f'{base}!{label}: incomplete private SQLite capsule/bridge/descriptor')
                else:
                    found.append(label)
                    _check_payload(base + '!' + label, jar, problems)
        if len(found) != 1:
            problems.append(f'{base}: expected exactly one private SQLite runtime, found {len(found)}')
    except (OSError, ValueError, KeyError, IndexError, TypeError, struct.error, UnicodeError, zipfile.BadZipFile) as failure:
        problems.append(f'{base}: unreadable private SQLite resources: {failure}')


def _check_payload(label, jar, problems):
    capsule, bridge = jar.read(CAPSULE), jar.read(BRIDGE)
    descriptor = properties(jar.read(DESCRIPTOR).decode('utf-8'))
    if digest(capsule) != PINS['capsuleSha256']:
        problems.append(f'{label}: SQLite capsule differs from independent checked-in digest')
    expected = {'version': PINS['version'], 'stockSha256': PINS['stockSha256'], 'sha256': digest(capsule), 'size': str(len(capsule)),
                'bridgeSha256': digest(bridge)}
    if descriptor != expected:
        problems.append(f'{label}: private SQLite descriptor identity mismatch')
    if (len(bridge) < 8 or bridge[:4] != b'\xca\xfe\xba\xbe'
            or struct.unpack('>H', bridge[6:8])[0] != 65
            or b'org/sqlite/SQLiteDataSource' not in bridge
            or class_name(bridge) != 'dev/vox/lss/jdbc/PrivateSqliteBridge'):
        problems.append(f'{label}: private bridge must be Java 21 with unrelocated SQLite references')
    with zipfile.ZipFile(io.BytesIO(capsule)) as inner:
        names = inner.namelist()
        if len(names) != len(set(names)):
            problems.append(f'{label}: duplicate capsule ZIP entries')
        required = (*NATIVES, LICENSE, 'META-INF/versions/9/module-info.class',
                    'org/sqlite/JDBC.class', 'org/sqlite/SQLiteDataSource.class',
                    'META-INF/services/java.sql.Driver')
        for name in required:
            if name not in names or not inner.read(name):
                problems.append(f'{label}: SQLite capsule missing {name}')
        unexpected = [name for name in names if name.startswith('org/sqlite/native/') and name not in NATIVES]
        if unexpected:
            problems.append(f'{label}: SQLite capsule has natives outside supported matrix: {unexpected[0]}')
        if any(name in names for name in ('fabric.mod.json', 'META-INF/neoforge.mods.toml',
                                           'META-INF/sponge_plugins.json')):
            problems.append(f'{label}: capsule must not carry loader mod metadata')


def resource_digests(jar_path):
    result = []
    for label, jar in archives(Path(jar_path).read_bytes()):
        for name in (CAPSULE, BRIDGE, DESCRIPTOR):
            if name in jar.namelist():
                result.append((name, digest(jar.read(name))))
    return sorted(result)


def check_pair(lss, vss, problems):
    if resource_digests(lss) != resource_digests(vss):
        problems.append(f'{Path(vss).name}: private SQLite resources differ from LSS pair')


def fixture_entries(capsule=None):
    """Synthetic payload for checker tests only. Callers explicitly pin its digest."""
    if capsule is None:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w') as jar:
            for name in (*NATIVES, LICENSE, 'META-INF/versions/9/module-info.class',
                         'org/sqlite/JDBC.class', 'org/sqlite/SQLiteDataSource.class',
                         'META-INF/services/java.sql.Driver'):
                # Fixed timestamp keeps fixtures deterministic across repeated calls.
                jar.writestr(zipfile.ZipInfo(name), b'x')
        capsule = buf.getvalue()
    bridge = fixture_class('dev/vox/lss/jdbc/PrivateSqliteBridge')
    desc = f'version={PINS["version"]}\nstockSha256={PINS["stockSha256"]}\nsize={len(capsule)}\nsha256={digest(capsule)}\nbridgeSha256={digest(bridge)}\n'
    return {CAPSULE: capsule, BRIDGE: bridge, DESCRIPTOR: desc}


def fixture_class(name):
    def utf8(value):
        encoded = value.encode('utf-8')
        return b'\x01' + struct.pack('>H', len(encoded)) + encoded
    return (b'\xca\xfe\xba\xbe\x00\x00\x00\x41\x00\x06'
            + utf8(name) + b'\x07\x00\x01' + utf8('java/lang/Object') + b'\x07\x00\x03'
            + utf8('org/sqlite/SQLiteDataSource')
            + struct.pack('>7H', 0x31, 2, 4, 0, 0, 0, 0))
