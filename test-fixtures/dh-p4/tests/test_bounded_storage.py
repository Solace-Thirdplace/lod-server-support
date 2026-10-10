import importlib,sys,tempfile,unittest,os
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[3]
sys.path[:0]=[str(ROOT/'tools/rig'),str(ROOT/'test-fixtures/dh-p4')]
import storage_guard as guard
import bounded_storage as bounded
class StorageTests(unittest.TestCase):
 def test_disappeared_child_is_tolerated_during_atomic_replacement(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);child=root/'vanishing';child.write_bytes(b'x'*100);normal_stat=os.stat
   def racing_stat(path,**kwargs):
    if Path(path)==child:raise FileNotFoundError('Atomic replacement')
    return normal_stat(path,**kwargs)
   with patch.object(bounded.os,'stat',side_effect=racing_stat):self.assertEqual(root.stat().st_size,bounded.owned_size([root]))
 def test_root_loss_and_permission_failures_are_not_ignored(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);child=root/'locked';child.write_bytes(b'x');normal_stat=os.stat
   def failing_stat(path,**kwargs):
    if Path(path)==child:raise PermissionError('Unreadable owned entry')
    return normal_stat(path,**kwargs)
   with patch.object(bounded.os,'stat',side_effect=failing_stat):
    with self.assertRaises(PermissionError):bounded.owned_size([root])
   with self.assertRaises(FileNotFoundError):bounded.owned_size([root/'missing'])
 def test_links_are_not_followed_or_double_counted(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);one=root/'one';one.write_bytes(b'12345');os.link(one,root/'two');link=root/'link';link.symlink_to(root)
   self.assertEqual(root.stat().st_size+one.stat().st_size+link.lstat().st_size,bounded.owned_size([root]))
 def test_growth_scan_has_a_deadline(self):
  with tempfile.TemporaryDirectory() as directory,patch.object(bounded.time,'monotonic',side_effect=[0,21]):
   with self.assertRaises(TimeoutError):bounded.owned_size([directory])
 def setUp(self):bounded.install()
 def value(self):return {'schema_version':2,'copy_bytes':100,'growth_bytes':2*guard.GIB,'copied_files':1,'bounded_profile':bounded.POLICY}
 def test_preflight_reports_the_actual_frozen_profile_reserve(self):
  with tempfile.TemporaryDirectory() as t,patch.object(guard,'wsl',return_value=False),patch.object(guard,'capacity',return_value={'free_bytes':45*guard.GIB}):
   result=guard.preflight({}, {},Path(t),value=self.value(),phase='run')
   self.assertEqual(40*guard.GIB,result['reserve_bytes']);self.assertEqual(42*guard.GIB,result['required_free_bytes'])
   with self.assertRaisesRegex(ValueError,'50 GiB'):
    guard.preflight({}, {},Path(t),value={**self.value(),'bounded_profile':'dh-p4-2g-v1'},phase='run')
 def test_legacy_receipts_keep_their_original_reserve(self):
  old={**self.value(),'bounded_profile':'dh-p4-2g-v1'}
  self.assertEqual(52*guard.GIB,guard.requirement(old,'run'))
  self.assertEqual(42*guard.GIB,guard.requirement(self.value(),'run'))
  self.assertEqual(50*guard.GIB,guard.RESERVE)
 def test_general_estimates_remain_strict(self):
  old={'schema_version':1,'copy_bytes':100,'growth_bytes':8*guard.GIB,'copied_files':1}
  self.assertEqual(guard.RESERVE+8*guard.GIB+100,guard.requirement(old,'create'))
  old['growth_bytes']=2*guard.GIB
  with self.assertRaises(ValueError):guard.requirement(old,'create')
 def test_bounded_profile_is_explicit_and_exact(self):
  self.assertEqual(bounded.RESERVES[bounded.POLICY]+2*guard.GIB+100,guard.requirement(self.value(),'create'))
  for change in [{'bounded_profile':'unknown'},{'growth_bytes':0},{'copy_bytes':3*guard.GIB},{'copied_files':True},{'extra':1}]:
   with self.assertRaises(ValueError):guard.requirement({**self.value(),**change},'create')
 def test_growth_and_floor_are_enforced(self):
  with tempfile.TemporaryDirectory() as t:
   pre={'estimate':self.value(),'linux_path':t,'host_monitor':None,'host':None}
   with patch.object(guard.Monitor,'size',return_value=100):monitor=guard.Monitor(Path(t),pre)
   with patch.object(guard,'capacity',return_value={'free_bytes':bounded.RESERVES[bounded.POLICY]+1}),patch.object(monitor,'size',return_value=100+bounded.GROWTH+1):
    with self.assertRaisesRegex(ValueError,'growth exceeded'):monitor.check()
   monitor.last_capacity=None
   with patch.object(guard,'capacity',return_value={'free_bytes':bounded.RESERVES[bounded.POLICY]-1}):
    with self.assertRaisesRegex(ValueError,'reserve'):monitor.check()
if __name__=='__main__':unittest.main()
