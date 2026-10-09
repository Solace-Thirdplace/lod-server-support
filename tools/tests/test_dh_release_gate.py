"""Mutations must fail even outside the decoder or in multi-release/nested code."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('dh_release_gate',ROOT/'scripts/release_check.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def archive(entries):
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w') as jar:
        for name,value in entries.items():jar.writestr(name,value)
    return stream.getvalue()

class DhReleaseGateTest(unittest.TestCase):
    def test_diagnostic_prose_is_not_a_symbolic_or_reflective_class_link(self):
        def class_with_utf8(value):
            encoded=value.encode('utf-8')
            return b'\xca\xfe\xba\xbe\x00\x00\x00\x3d\x00\x02\x01'+len(encoded).to_bytes(2,'big')+encoded
        self.assertFalse(gate._links_stock_xz(class_with_utf8("Unsupported value in the system property org.tukaani.xz.ArrayCache")))
        for value in ('org/tukaani/xz/XZInputStream','org.tukaani.xz.XZInputStream','(Lorg/tukaani/xz/XZInputStream;)V','[Lorg/tukaani/xz/XZInputStream;'):
            self.assertTrue(gate._links_stock_xz(class_with_utf8(value)),value)
    def test_every_archive_class_is_checked_for_stock_xz_links(self):
        for name in ('dev/vox/lss/Other.class','META-INF/versions/21/dev/vox/lss/Other.class'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                path=Path(temp)/'test.jar';path.write_bytes(archive({'META-INF/jars/inner.jar':archive({name:b'org/tukaani/xz/XZInputStream'})}))
                problems=[];gate.check_dh_codec(path,'fabric',problems)
                self.assertTrue(any('links unrelocated XZ' in p for p in problems))
    def test_foreign_and_private_namespaces_are_rejected_at_every_level(self):
        for prefix in ('privatep4/','com/seibel/','net/caffeinemc/','org/tukaani/'):
            for multi in ('','META-INF/versions/21/'):
                with self.subTest(prefix=prefix,multi=multi),tempfile.TemporaryDirectory() as temp:
                    path=Path(temp)/'test.jar';path.write_bytes(archive({'META-INF/jarjar/inner.jar':archive({multi+prefix+'Bad.class':b'bad'})}))
                    problems=[];gate._scan_forbidden(path,'test',gate.COMMON_FORBIDDEN,problems)
                    self.assertTrue(any('forbidden entries' in p for p in problems))
    def test_a_dh_jar_requires_its_xz_third_party_paragraph(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'test.jar'
            for text,valid in ((b'ordinary notices '+b'x'*200,False),(b'XZ for Java 1.10 (0BSD) '+b'x'*200,True)):
                path.write_bytes(archive({gate.DH_CODEC:b'codec','THIRD-PARTY-NOTICES':text}))
                problems=[];gate.check_third_party_notices(path,False,problems)
                self.assertEqual(valid,not problems,problems)

if __name__=='__main__':unittest.main()
