param([Parameter(Mandatory=$true)][string]$Spec)
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$cfg=Get-Content -LiteralPath $Spec -Raw | ConvertFrom-Json
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class LssPrivateDesktop {
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct STARTUPINFO {public int cb;public string reserved,desktop,title;public uint x,y,cx,cy,xchars,ychars,fill,flags;public ushort show,extra;public IntPtr bytes,input,output,error;}
 [StructLayout(LayoutKind.Sequential)] struct PROCESSINFO {public IntPtr process,thread;public uint pid,tid;}
 [StructLayout(LayoutKind.Sequential)] struct BASICLIMIT {public long processTime,jobTime;public uint flags;public UIntPtr min,max;public uint active;public UIntPtr affinity;public uint priority,scheduling;}
 [StructLayout(LayoutKind.Sequential)] struct IOCOUNTERS {public ulong a,b,c,d,e,f;}
 [StructLayout(LayoutKind.Sequential)] struct EXTLIMIT {public BASICLIMIT basic;public IOCOUNTERS io;public UIntPtr processMemory,jobMemory,peakProcess,peakJob;}
 [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateDesktopW(string name,IntPtr device,IntPtr mode,uint flags,uint access,IntPtr security);
 [DllImport("user32.dll",SetLastError=true)] static extern bool CloseDesktop(IntPtr h);
 [DllImport("user32.dll",SetLastError=true)] static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint access);
 [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool GetUserObjectInformationW(IntPtr h,int index,StringBuilder text,int length,out int needed);
 delegate bool EnumWindow(IntPtr window,IntPtr param);
 [DllImport("user32.dll",SetLastError=true)] static extern bool EnumDesktopWindows(IntPtr desktop,EnumWindow callback,IntPtr param);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcessW(string app,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref STARTUPINFO si,out PROCESSINFO pi);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObjectW(IntPtr sa,string name);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int cls,ref EXTLIMIT info,int size);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool found);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetProcessTimes(IntPtr process,out long created,out long exited,out long kernel,out long user);
 [StructLayout(LayoutKind.Sequential)] struct MEMORYINFO {public uint cb,pagefault;public UIntPtr peakWorking,working,peakPaged,paged,peakNonpaged,nonpaged,pagefile,peakPagefile,privateUsage;}
 [DllImport("psapi.dll",SetLastError=true)] static extern bool GetProcessMemoryInfo(IntPtr process,ref MEMORYINFO counters,uint size);
 [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
 [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle,uint milliseconds);
 [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint exit);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
 static string Name(IntPtr h){var t=new StringBuilder(256);int n;if(!GetUserObjectInformationW(h,2,t,512,out n))throw new System.ComponentModel.Win32Exception();return t.ToString();}
 static string InputName(){var h=OpenInputDesktop(0,false,1);if(h==IntPtr.Zero)throw new System.ComponentModel.Win32Exception();try{return Name(h);}finally{CloseDesktop(h);}}
 public static int Run(string app,string command,string cwd,string evidence,int seconds) {
  if(!Path.IsPathRooted(cwd)||!File.Exists(app)||seconds<1||seconds>3600)throw new ArgumentException("invalid owned launch");
  string before=InputName(),name="LSSP4_"+Guid.NewGuid().ToString("N");
  IntPtr desktop=CreateDesktopW(name,IntPtr.Zero,IntPtr.Zero,0,0xFF,IntPtr.Zero),job=IntPtr.Zero;PROCESSINFO pi=new PROCESSINFO();
  if(desktop==IntPtr.Zero)throw new System.ComponentModel.Win32Exception();
  var windows=new HashSet<long>();var samples=new List<string>();bool timeout=false,stopped=false;uint exit=1;long creation=0,kernel,user,ended;bool inJob;
  try{
   if(Name(desktop)!=name)throw new InvalidOperationException("desktop identity mismatch");
   job=CreateJobObjectW(IntPtr.Zero,null);var limit=new EXTLIMIT();limit.basic.flags=0x2000;
   if(job==IntPtr.Zero||!SetInformationJobObject(job,9,ref limit,Marshal.SizeOf(typeof(EXTLIMIT))))throw new System.ComponentModel.Win32Exception();
   var si=new STARTUPINFO();si.cb=Marshal.SizeOf(typeof(STARTUPINFO));si.desktop="WinSta0\\"+name;
   if(!CreateProcessW(app,new StringBuilder(command),IntPtr.Zero,IntPtr.Zero,false,4|512,IntPtr.Zero,cwd,ref si,out pi))throw new System.ComponentModel.Win32Exception();
   if(!AssignProcessToJobObject(job,pi.process)||!IsProcessInJob(pi.process,job,out inJob)||!inJob)throw new InvalidOperationException("job assignment failed");
   if(!GetProcessTimes(pi.process,out creation,out ended,out kernel,out user))throw new System.ComponentModel.Win32Exception();
   File.WriteAllText(evidence+".owner.json","{\"pid\":"+pi.pid+",\"creation_filetime\":"+creation+",\"private_desktop\":\""+name+"\",\"job_kill_on_close\":true}");
   if(ResumeThread(pi.thread)==0xFFFFFFFF)throw new System.ComponentModel.Win32Exception();
   DateTime opened=DateTime.UtcNow,deadline=opened.AddSeconds(seconds);
   while(WaitForSingleObject(pi.process,200)==0x102){
    var memory=new MEMORYINFO();memory.cb=(uint)Marshal.SizeOf(typeof(MEMORYINFO));long sampleCreation,sampleEnd,sampleKernel,sampleUser;
    if(!GetProcessTimes(pi.process,out sampleCreation,out sampleEnd,out sampleKernel,out sampleUser)||sampleCreation!=creation||!GetProcessMemoryInfo(pi.process,ref memory,memory.cb))throw new InvalidOperationException("owned resource sampling failed");
    samples.Add("{\"elapsed_ms\":"+((long)(DateTime.UtcNow-opened).TotalMilliseconds)+",\"kernel_cpu_100ns\":"+sampleKernel+",\"user_cpu_100ns\":"+sampleUser+",\"working_set_bytes\":"+memory.working.ToUInt64()+",\"private_bytes\":"+memory.privateUsage.ToUInt64()+"}");
    EnumDesktopWindows(desktop,(window,param)=>{uint id;GetWindowThreadProcessId(window,out id);if(id==pi.pid)windows.Add(window.ToInt64());return true;},IntPtr.Zero);
    if(InputName()!=before)throw new InvalidOperationException("input desktop changed; isolated ownership no longer proved");
    if(File.Exists(evidence+".stop")){stopped=true;break;}
    if(DateTime.UtcNow>deadline){timeout=true;break;}
   }
   File.WriteAllText(evidence+".resources.json","{\"pid\":"+pi.pid+",\"creation_filetime\":"+creation+",\"samples\":["+String.Join(",",samples)+"]}");
   GetExitCodeProcess(pi.process,out exit);long latest;if(!GetProcessTimes(pi.process,out latest,out ended,out kernel,out user)||latest!=creation)throw new InvalidOperationException("process identity changed");
   File.WriteAllText(evidence+".json","{\"pid\":"+pi.pid+",\"creation_filetime\":"+creation+",\"private_desktop\":\""+name+"\",\"input_desktop_before\":\""+before+"\",\"input_desktop_after\":\""+InputName()+"\",\"owned_top_level_windows\":"+windows.Count+",\"job_kill_on_close\":true,\"timeout\":"+timeout.ToString().ToLower()+",\"exit_code\":"+exit+"}");
   return stopped?130:timeout?124:(int)exit;
  }finally{if(job!=IntPtr.Zero)CloseHandle(job);if(pi.process!=IntPtr.Zero){WaitForSingleObject(pi.process,10000);CloseHandle(pi.process);}if(pi.thread!=IntPtr.Zero)CloseHandle(pi.thread);CloseDesktop(desktop);}
 }
}
'@
exit [LssPrivateDesktop]::Run($cfg.application,$cfg.command_line,$cfg.working_directory,$cfg.evidence,[int]$cfg.deadline_seconds)
