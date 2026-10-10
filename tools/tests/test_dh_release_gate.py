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
                    self.assertTrue(any('ships dev-only code' in p for p in problems))
    def test_a_dh_jar_requires_its_xz_third_party_paragraph(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'test.jar'
            paragraph=gate.XZ_NOTICE_PARAGRAPH.replace('. ','.\n').encode()
            for text,valid in ((b'ordinary notices '+b'x'*200,False),(b'XZ for Java 1.10 (0BSD) '+b'x'*200,False),
                               (b'XZ 1.10 0BSD '+b'x'*200,False),(b'notices\n\n'+paragraph+b'\n'+b'x'*200,True)):
                path.write_bytes(archive({gate.DH_CODEC:b'codec','THIRD-PARTY-NOTICES':text}))
                problems=[];gate.check_third_party_notices(path,False,problems)
                self.assertEqual(valid,not problems,problems)
    def test_paper_carries_no_dh_codec_xz_or_license(self):
        for name in (gate.DH_CODEC,'dev/vox/lss/common/dh/DhProfile.class',gate.XZ_PREFIX+'XZInputStream.class',
                     'META-INF/versions/9/'+gate.XZ_PREFIX+'lz/LZDecoder.class',gate.XZ_LICENSE):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                path=Path(temp)/'paper.jar';path.write_bytes(archive({'plugin.yml':'name: x',name:b'x'}))
                problems=[];gate.check_dh_codec(path,'paper',problems)
                self.assertTrue(problems,name)
    def test_only_allowlisted_client_classes_may_name_dh_on_paper(self):
        def class_with_utf8(value):
            encoded=value.encode('utf-8')
            return b'\xca\xfe\xba\xbe\x00\x00\x00\x41\x00\x02\x01'+len(encoded).to_bytes(2,'big')+encoded
        for owner,allowed in (('dev/vox/lss/common/diagnostics/DhStatusSnapshot$Reason',True),
                              ('dev/vox/lss/common/diagnostics/ClientStatusText',True),
                              ('dev/vox/lss/paper/PaperCommands',False),
                              ('dev/vox/lss/common/diagnostics/DiagnosticExport',False)):
            with self.subTest(owner=owner),tempfile.TemporaryDirectory() as temp:
                path=Path(temp)/'paper.jar'
                path.write_bytes(archive({owner+'.class':class_with_utf8('Ldev/vox/lss/common/dh/DhProfile;')}))
                problems=[];gate.check_dh_codec(path,'paper',problems)
                self.assertEqual(allowed,not problems,problems)
    def test_xz_license_is_pinned_by_content_not_token(self):
        license_text=(ROOT/'common/src/main/resources/META-INF/licenses/xz-java-0BSD.txt').read_bytes()
        for text,valid in ((license_text,True),(b'see upstream 0BSD',False)):
            with tempfile.TemporaryDirectory() as temp:
                entries={gate.DH_CODEC:b'codec',gate.XZ_LICENSE:text}
                entries.update({name:b'\xca\xfe\xba\xbe\x00\x00\x00\x34' for name in gate.XZ_REQUIRED})
                path=Path(temp)/'neo.jar';path.write_bytes(archive(entries))
                problems=[];gate.check_dh_codec(path,'neoforge',problems)
                self.assertEqual(valid,not problems,problems)
    def test_mixin_configs_must_list_present_classes_and_soft_fail(self):
        meta='{"id":"lss","mixins":["lss.mixins.json","lss-sodium-legacy.mixins.json"]}'
        primary='{"required":true,"package":"dev.vox.lss.mixin","client":["SomeHook"]}'
        legacy='{"required":false,"package":"dev.vox.lss.mixin.sodium","injectors":{"defaultRequire":0},"client":["SodiumLegacyOptionsHook"]}'
        base={'fabric.mod.json':meta,'lss.mixins.json':primary,'lss-sodium-legacy.mixins.json':legacy,
              'dev/vox/lss/mixin/SomeHook.class':b'x','dev/vox/lss/mixin/sodium/SodiumLegacyOptionsHook.class':b'x'}
        cases=((dict(base),True),
               ({k:v for k,v in base.items() if k!='dev/vox/lss/mixin/SomeHook.class'},False),
               (dict(base,**{'lss-sodium-legacy.mixins.json':legacy.replace('false','true')}),False),
               (dict(base,**{'lss-dh-fixture.mixins.json':'{}'}),False))
        for entries,valid in cases:
            with tempfile.TemporaryDirectory() as temp:
                path=Path(temp)/'fabric.jar';path.write_bytes(archive(entries))
                problems=[];gate.check_mixin_configs(path,'fabric',problems)
                self.assertEqual(valid,not problems,problems)
    def test_a_distant_horizons_dependency_cannot_ship(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'fabric.jar'
            path.write_bytes(archive({'fabric.mod.json':'{"id":"lss","depends":{"minecraft":"*","distanthorizons":"*"}}'}))
            problems=[];gate.check_dependency_allowlist(path,'fabric',problems)
            self.assertTrue(any('distanthorizons' in p for p in problems),problems)

if __name__=='__main__':unittest.main()
