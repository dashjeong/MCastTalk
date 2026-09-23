"""Real Windows orphan cleanup test; creates and observes only its own process tree."""
import argparse,ctypes,json,os,subprocess,sys
from ctypes import wintypes
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--worker-home',required=True);p.add_argument('--output',required=True);a=p.parse_args()
if os.name!='nt':raise RuntimeError('Windows-only test')
k=ctypes.WinDLL('kernel32',use_last_error=True)
k.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];k.OpenProcess.restype=wintypes.HANDLE
k.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];k.WaitForSingleObject.restype=wintypes.DWORD
k.CloseHandle.argtypes=[wintypes.HANDLE]
worker="""import json,os,subprocess,sys,time
from mcasttalk_worker.parent_guard import watch_host
watch_host()
helper=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],creationflags=subprocess.CREATE_NO_WINDOW)
print(json.dumps([os.getpid(),helper.pid]),flush=True)
time.sleep(60)
"""
host=f"""import os,subprocess,sys,time
env=os.environ.copy();env['MCASTTALK_HOST_PID']=str(os.getpid())
child=subprocess.Popen([sys.executable,'-E','-s','-c',{worker!r}],env=env,stdout=subprocess.PIPE,creationflags=subprocess.CREATE_NO_WINDOW)
print(child.stdout.readline().decode().strip(),flush=True)
time.sleep(2)
"""
owner=subprocess.Popen([sys.executable,'-E','-s','-c',host],cwd=a.worker_home,stdout=subprocess.PIPE,creationflags=subprocess.CREATE_NO_WINDOW)
handles=[];pids=[];checks=[]
try:
 raw=owner.stdout.readline();pids=json.loads(raw)
 assert len(pids)==2
 for pid in pids:
  handle=k.OpenProcess(0x00100000,False,pid);assert handle;handles.append(handle)
 checks.append('owned worker and helper started under a temporary host')
 assert owner.wait(timeout=10)==0;checks.append('temporary owning host exited normally')
 for handle in handles:assert k.WaitForSingleObject(handle,12000)==0
 checks.append('watcher terminated both orphaned worker and its helper')
 print(json.dumps({'checks':checks},indent=2));Path(a.output).write_text(json.dumps({'checks':checks},indent=2),encoding='utf-8')
finally:
 if owner.poll() is None:owner.kill();owner.wait(timeout=5)
 for pid,handle in zip(pids,handles):
  if k.WaitForSingleObject(handle,0)==258:
   subprocess.run([str(Path(os.environ['SystemRoot'])/'System32/taskkill.exe'),'/PID',str(pid),'/T','/F'],creationflags=subprocess.CREATE_NO_WINDOW,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
  k.CloseHandle(handle)
