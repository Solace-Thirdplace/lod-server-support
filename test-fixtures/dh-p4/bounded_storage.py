"""Explicit user-authorized bounded P4 budget; generic rig defaults remain unchanged.
A 2 GiB growth cap and frequent capacity checks allow these bounded fixtures to
run without reserving the generic 8 GiB. Exceeding either cap stops owned work.
"""
import os,time,stat
from pathlib import Path
import storage_guard as guard
POLICY='dh-p4-2g-v2';GROWTH=2*guard.GIB
RESERVES={'dh-p4-2g-v1':50*guard.GIB,POLICY:40*guard.GIB}

def owned_size(paths,timeout=20):
 """Logical bytes, no link traversal; atomic rename churn is not an IO fault.

 Missing child entries may disappear between enumeration and stat. Root loss,
 permission/other IO failures and the deadline still stop the owned run.
 """
 deadline=time.monotonic()+timeout;seen=set();total=0
 pending=[(Path(path),True) for path in paths]
 while pending:
  if time.monotonic()>deadline:raise TimeoutError('Owned growth scan deadline')
  path,isroot=pending.pop()
  try:info=os.stat(path,follow_symlinks=False)
  except FileNotFoundError:
   if isroot:raise
   continue
  if isroot and not stat.S_ISDIR(info.st_mode):raise ValueError('Owned growth root is not a real directory')
  key=(info.st_dev,info.st_ino)
  if key in seen:continue
  seen.add(key);total+=info.st_size
  if stat.S_ISDIR(info.st_mode):
   try:
    with os.scandir(path) as children:pending.extend((Path(child.path),False) for child in children)
   except FileNotFoundError:
    if isroot:raise
 return total

def install():
 if getattr(guard,'_dh_p4_bounded',False):return
 estimate,requirement,monitor=guard.estimate,guard.requirement,guard.Monitor
 def bounded_estimate(profile,runtime):
  value=estimate(profile,runtime)
  if value['copy_bytes']>GROWTH:raise ValueError('P4 copy estimate exceeds bounded profile')
  return {**value,'schema_version':2,'growth_bytes':GROWTH,'bounded_profile':POLICY}
 def bounded_requirement(value,phase):
  if value.get('schema_version')!=2:return requirement(value,phase)
  keys={'schema_version','copy_bytes','growth_bytes','copied_files','bounded_profile'}
  if set(value)!=keys or value['bounded_profile'] not in RESERVES or value['growth_bytes']!=GROWTH or phase not in ['create','run'] or any(type(value[k]) is not int or value[k]<0 for k in ['copy_bytes','growth_bytes','copied_files']) or value['copy_bytes']>GROWTH:raise ValueError('Invalid bounded P4 admission estimate')
  return RESERVES[value['bounded_profile']]+GROWTH+(value['copy_bytes'] if phase=='create' else 0)
 class BoundedMonitor(monitor):
  def __init__(self,root,preflight):
   super().__init__(root,preflight);self.bounded=preflight['estimate'].get('bounded_profile') in RESERVES;self.reserve=RESERVES.get(preflight['estimate'].get('bounded_profile'),guard.RESERVE);self.baseline=None;self.last_capacity=None;self.last_size=None
   self.paths=[str(root)]
   import json
   runtime=Path(root)/'runtime.json'
   if runtime.exists():
    windows=json.loads(runtime.read_text()).get('windows_client')
    if windows:self.paths.append(windows)
   if self.bounded:self.baseline=self.size()
  def size(self):
   return owned_size(self.paths)
  def check(self):
   if not self.bounded:return super().check()
   now=time.monotonic()
   if self.last_capacity is None or now-self.last_capacity>=5:
    self.last_capacity=now
    linux=guard.capacity(self.linux);host=guard.capacity(self.mount) if self.mount else None
    if self.mount and guard.drvfs(self.host)!=self.mount:raise ValueError('Physical host monitor mount changed')
    if linux['free_bytes']<self.reserve or host and host['free_bytes']<self.reserve:raise ValueError(f'P4 physical {self.reserve//guard.GIB} GiB reserve reached')
    from rig import write
    (self.root/'evidence').mkdir(exist_ok=True)
    write(self.root/'evidence/storage-health.json',{'sample_monotonic':now,'reserve_bytes':self.reserve,'linux':linux,'host':host})
   if self.last_size is None or now-self.last_size>=30:
    self.last_size=now
    if self.size()-self.baseline>GROWTH:raise ValueError('Owned P4 growth exceeded 2 GiB cap')
   # Generic monitor retains its 50 GiB policy; bounded runs use the frozen profile above.
 guard.estimate=bounded_estimate;guard.requirement=bounded_requirement;guard.Monitor=BoundedMonitor;guard._dh_p4_bounded=True
