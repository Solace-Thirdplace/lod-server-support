import json,os,subprocess,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'rig'))
from rig import identity
from server_storage_stop import stop_for_storage,record_exit
class StopTests(unittest.TestCase):
 def test_console_save_and_stop_precede_any_signal_and_exit_is_recorded(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);fifo=root/'console';os.mkfifo(fifo);endpoint=os.open(fifo,os.O_RDWR|os.O_NONBLOCK)
   input_file=open(fifo)
   child=subprocess.Popen([sys.executable,'-c',"import sys; a=sys.stdin.readline(); b=sys.stdin.readline(); print(a.strip(),b.strip(),flush=True)"],stdin=input_file,stdout=subprocess.PIPE,text=True)
   input_file.close()
   try:
    owner=identity(child.pid);(root/'server-owner.json').write_text(json.dumps({'owner':owner}))
    value=stop_for_storage(owner,root/'server-stopped.json',fifo,'floor reached',grace=3)
    out=child.communicate(timeout=3)[0];self.assertEqual('save-all stop\n',out)
    self.assertTrue(value['console_commands']);self.assertFalse(value['sigterm_fallback'])
    record_exit(root,child.returncode);receipt=json.loads((root/'server-stopped.json').read_text())
    self.assertEqual('stopped',receipt['state']);self.assertEqual(0,receipt['exit_code'])
   finally:
    if child.poll() is None:child.terminate();child.wait(timeout=3)
    os.close(endpoint)
 def test_missing_console_falls_back_only_on_the_recorded_live_owner(self):
  with tempfile.TemporaryDirectory() as d:
   child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'])
   try:
    value=stop_for_storage(identity(child.pid),Path(d)/'stop.json',Path(d)/'missing','floor',grace=0)
    self.assertTrue(value['sigterm_fallback']);child.wait(timeout=3)
   finally:
    if child.poll() is None:child.terminate();child.wait(timeout=3)
if __name__=='__main__':unittest.main()
