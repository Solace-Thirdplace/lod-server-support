#!/usr/bin/env python3
"""Assemble a local Fabric test artifact when Gradle cannot start in the sandbox.

Requires separately compiled Java-21 classes, a relocated/private codec JAR, the
exact named 1.21.1 compile classpath, and a baseline jar from the same source ref.
This is an incremental development artifact, not a substitute for release gates.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASELINE_SHA256 = "f93fa217e0fdc6433e47c38555dcb8efda3d808490265974d6cdececfbaaff1e"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unpack(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), "Duplicate ZIP members"
        return {n: archive.read(n) for n in archive.namelist() if not n.endswith("/")}


def pack(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline", "classes", "common-classes", "private-codec", "classpath", "remapper", "mappings", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--java", type=Path, default=Path("/usr/lib/jvm/java-21-openjdk-amd64/bin/java"))
    args = parser.parse_args()
    base_data = args.baseline.read_bytes()
    if digest(base_data) != BASELINE_SHA256:
        raise ValueError("Baseline is not the pinned 0.16.0+mc1.21.1 development artifact")
    fabric = unpack(base_data)
    assert not any("dhpoc" in n.lower() for n in fabric), "POC must not be in the baseline jar"
    common_names = [n for n in fabric if n.startswith("META-INF/jars/common-") and n.endswith(".jar")]
    assert len(common_names) == 1
    common_name = common_names[0]
    original_common = unpack(fabric[common_name])
    common = dict(original_common)
    common.update(unpack(args.private_codec.read_bytes()))
    for path in args.common_classes.rglob("*.class"):
        name = path.relative_to(args.common_classes).as_posix()
        if any(name.startswith("dev/vox/lss/common/config/" + prefix) for prefix in
               ("SettingsSchema$", "SettingsSchema.", "SettingsSchemaSpecs", "ClientSettings")):
            common[name] = path.read_bytes()
    common["dev/vox/lss/settings/client.yaml"] = (ROOT / "common/src/main/resources/dev/vox/lss/settings/client.yaml").read_bytes()
    # The SQLite capsule and native-LSS store implementation must remain byte-identical.
    capsule_names = [n for n in original_common if "sqlite" in n.lower()]
    assert capsule_names and all(common[n] == original_common[n] for n in capsule_names)
    fabric[common_name] = pack(common)
    with tempfile.TemporaryDirectory(prefix="dh-remap-") as scratch:
        scratch = Path(scratch)
        named = scratch / "named.jar"
        mapped = scratch / "intermediary.jar"
        named.write_bytes(pack({p.relative_to(args.classes).as_posix(): p.read_bytes()
                               for p in args.classes.rglob("*.class")}))
        classpath = [p for p in args.classpath.read_text().strip().split(":") if Path(p).exists()]
        subprocess.run([str(args.java), "-Xmx1g", "-jar", str(args.remapper), str(named), str(mapped),
                        str(args.mappings), "named", "intermediary", *classpath], check=True)
        fabric.update(unpack(mapped.read_bytes()))
    for path in (ROOT / "fabric/src/main/resources/assets/lss/lang").glob("*.json"):
        fabric[path.relative_to(ROOT / "fabric/src/main/resources").as_posix()] = path.read_bytes()
    metadata = json.loads(fabric["fabric.mod.json"])
    metadata["version"] = "0.16.0+dh-playable.mc1.21.1"
    metadata["name"] = "LOD Server Support (DH Playable Test)"
    fabric["fabric.mod.json"] = (json.dumps(metadata, indent=2) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(pack(fabric))
    receipt = {
        "kind": "local-incremental-development-build",
        "baseline_sha256": digest(base_data),
        "artifact_sha256": digest(args.output.read_bytes()),
        "artifact_bytes": args.output.stat().st_size,
        "minecraft": "1.21.1", "loader": "fabric", "java": 21,
        "baseline_source_ref": "6677fe93dfc35b8ca1e158972d4cf58eb9703bd2",
        "sqlite_capsule_unchanged": True,
        "changed_classes": {p.relative_to(args.classes).as_posix(): digest(p.read_bytes())
                            for p in sorted(args.classes.rglob("*.class"))},
        "normal_gradle_gate": "pending: sandbox socket restriction",
        "live_client_server_gate": "pending: sandbox rejects loopback listeners",
    }
    args.output.with_suffix(".receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Created {args.output.name}: {receipt['artifact_bytes']} bytes, SHA256 {receipt['artifact_sha256']}")


if __name__ == "__main__":
    main()
