"""A slow owned save must survive the manual supervisor's explicit grace."""
import os,signal,subprocess,sys,tempfile,time,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class OwnedGraceTest(unittest.TestCase):
    def test_slow_sigterm_save_exceeds_three_seconds_and_finishes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);worker=root/'worker.py'
            worker.write_text('import signal,time\nfrom pathlib import Path\ndef stop(*a):\n time.sleep(3.4)\n Path("saved").write_text("done")\n raise SystemExit(0)\nsignal.signal(signal.SIGTERM,stop)\nPath("ready").touch()\nwhile True:time.sleep(.02)\n')
            env=os.environ.copy();env.pop('LSS_HARNESS_OWNER_PID',None);env.pop('LSS_HARNESS_LOCK_FD',None)
            proc=subprocess.Popen([sys.executable,str(ROOT/'scripts/lib/owned-process.py'),'--termination-grace','5','--cwd',str(root),'--',sys.executable,str(worker)],env=env)
            try:
                deadline=time.monotonic()+5
                while not (root/'ready').exists() and time.monotonic()<deadline:time.sleep(.02)
                self.assertTrue((root/'ready').exists());proc.terminate();self.assertEqual(143,proc.wait(timeout=8));self.assertEqual('done',(root/'saved').read_text())
            finally:
                if proc.poll() is None:proc.terminate();proc.wait(timeout=8)
    def test_invalid_grace_does_not_launch_a_child(self):
        result=subprocess.run([sys.executable,str(ROOT/'scripts/lib/owned-process.py'),'--termination-grace','nan','--',sys.executable,'-c','raise SystemExit(99)'])
        self.assertEqual(2,result.returncode)
if __name__=='__main__':unittest.main()
