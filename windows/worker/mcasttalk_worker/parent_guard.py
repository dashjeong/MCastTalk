"""Terminate only this worker's own process tree if its owning Windows host disappears."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import subprocess
import threading

def watch_host():
    if os.name != 'nt' or 'MCASTTALK_HOST_PID' not in os.environ: return
    pid=int(os.environ['MCASTTALK_HOST_PID'])
    if not 0<pid<2**32 or pid==os.getpid(): raise ValueError('Invalid owning host PID')
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];kernel.WaitForSingleObject.restype=wintypes.DWORD
    kernel.CloseHandle.argtypes=[wintypes.HANDLE];kernel.CloseHandle.restype=wintypes.BOOL
    handle=kernel.OpenProcess(0x00100000,False,pid) # SYNCHRONIZE; stable handle avoids PID reuse.
    if not handle: raise OSError('Cannot observe owning host')
    def wait():
        try:
            if kernel.WaitForSingleObject(handle,0xFFFFFFFF)==0:
                taskkill=Path(os.environ['SystemRoot'])/'System32'/'taskkill.exe'
                subprocess.run([str(taskkill),'/PID',str(os.getpid()),'/T','/F'],stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW,timeout=10)
                os._exit(1)
        finally:kernel.CloseHandle(handle)
    threading.Thread(target=wait,name='owned-host-guard',daemon=True).start()
