"""Graceful storage-stop requests and point-in-time receipts for the owned DH server."""
import argparse,datetime,json,os,signal,stat,time,fcntl
from pathlib import Path
from rig import identity,write

def record(path,value):
 path=Path(path);path.parent.mkdir(exist_ok=True)
 with path.with_suffix('.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  if path.exists():
   previous=json.loads(path.read_text())
   if previous.get('owner')!=value.get('owner'):
    history=path.parent/'stop-history';history.mkdir(exist_ok=True)
    write(history/(str(time.time_ns())+'.json'),previous)
   elif previous.get('exit_code') is not None and value.get('exit_code') is None:
    value['exit_code']=previous['exit_code'];value['state']=previous['state']
  write(path,value)

def live(owner):
 if identity(owner['pid'])!=owner:return False
 try:return Path(f"/proc/{owner['pid']}/stat").read_text().rsplit(')',1)[1].split()[0]!='Z'
 except FileNotFoundError:return False

def stop_for_storage(owner,receipt,console,reason,grace=60):
 value={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'owner':owner,
        'state':'stop-requested','reason':reason,'console_commands':False,'sigterm_fallback':False,'exit_code':None}
 record(receipt,value)
 if not live(owner):value['state']='already-stopped';record(receipt,value);return value
 descriptor=os.pidfd_open(owner['pid'])
 try:
  if not live(owner):return value
  try:
   if not stat.S_ISFIFO(os.lstat(console).st_mode):raise ValueError('Console is not an owned FIFO')
   fifo=os.open(console,os.O_WRONLY|os.O_NONBLOCK|os.O_NOFOLLOW)
   try:os.write(fifo,b'save-all\nstop\n');value['console_commands']=True
   finally:os.close(fifo)
  except (OSError,ValueError):pass
  record(receipt,value)
  deadline=time.monotonic()+(grace if value['console_commands'] else 0)
  while live(owner) and time.monotonic()<deadline:time.sleep(.1)
  if live(owner):
   signal.pidfd_send_signal(descriptor,signal.SIGTERM);value['sigterm_fallback']=True
  value['state']='stopped' if not live(owner) else 'shutdown-pending'
  record(receipt,value);return value
 finally:os.close(descriptor)

def record_exit(directory,exit_code):
 directory=Path(directory);owner=json.loads((directory/'server-owner.json').read_text())['owner'];path=directory/'server-stopped.json'
 value=json.loads(path.read_text()) if path.exists() else {'owner':owner,'reason':'runtime exited'}
 if value.get('owner')!=owner:value={'owner':owner,'reason':'runtime exited'}
 value.update(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),exit_code=exit_code,
              state='stopped' if not live(owner) else 'owner-still-alive')
 record(path,value)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('directory');parser.add_argument('exit_code',type=int)
 args=parser.parse_args();record_exit(args.directory,args.exit_code)
