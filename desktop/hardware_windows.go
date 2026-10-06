//go:build windows

package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

type memoryStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

func Diagnose(ctx context.Context, dataDir string) Diagnostic {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	d := Diagnostic{
		OS:               "windows",
		Arch:             runtime.GOARCH,
		CPU:              "Unknown CPU",
		Cores:            runtime.NumCPU(),
		Measured:         false,
		CheckedAt:        time.Now().UTC(),
		Devices:          []Device{},
		Warnings:         []string{},
		MobileEquivalent: false,
	}

	kernel32 := windows.NewLazySystemDLL("kernel32.dll")
	procGlobalMemoryStatusEx := kernel32.NewProc("GlobalMemoryStatusEx")

	var mem memoryStatusEx
	mem.Length = uint32(unsafe.Sizeof(mem))

	ramOk := false
	ret, _, _ := procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&mem)))
	if ret != 0 {
		d.RAMGB = float64(mem.TotalPhys) / (1024 * 1024 * 1024)
		d.AvailableRAMGB = float64(mem.AvailPhys) / (1024 * 1024 * 1024)
		ramOk = true
	} else {
		d.Warnings = append(d.Warnings, "Failed to measure RAM")
	}

	var freeBytes, totalBytes, totalFreeBytes uint64
	diskOk := false
	dir16, err := windows.UTF16PtrFromString(dataDir)
	if err == nil {
		if err := windows.GetDiskFreeSpaceEx(dir16, &freeBytes, &totalBytes, &totalFreeBytes); err == nil {
			d.FreeDiskGB = float64(freeBytes) / (1024 * 1024 * 1024)
			diskOk = true
		} else {
			d.Warnings = append(d.Warnings, "Failed to measure disk space")
		}
	} else {
		d.Warnings = append(d.Warnings, "Failed to convert path for disk check")
	}

	if ramOk && diskOk {
		d.Measured = true
	}

	// Registry reads avoid starting PowerShell solely to obtain the CPU name.
	cpuKey, cpuErr := registry.OpenKey(registry.LOCAL_MACHINE, `HARDWARE\DESCRIPTION\System\CentralProcessor\0`, registry.QUERY_VALUE)
	if cpuErr == nil {
		name, _, readErr := cpuKey.GetStringValue("ProcessorNameString")
		cpuKey.Close()
		if readErr == nil && strings.TrimSpace(name) != "" {
			d.CPU = strings.TrimSpace(name)
		}
	}
	const probe = `[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$ErrorActionPreference='Stop';$cpu='';$gpus=@();$npus=@();$gc=$false;$nc=$false;$w=[System.Collections.Generic.List[string]]::new();if($env:MCAST_PROBE_CPU_FALLBACK -eq '1'){try{$cpu=[string]((Get-CimInstance Win32_Processor -OperationTimeoutSec 4 -ErrorAction Stop | Select-Object -First 1).Name)}catch{$w.Add('CPU CIM 조회 미완료')}};try{$gpus=@(Get-CimInstance Win32_VideoController -OperationTimeoutSec 4 -ErrorAction Stop | Select-Object Name,AdapterRAM);$gc=$true}catch{$w.Add('GPU CIM 조회 미완료')};try{$npus=@(Get-CimInstance Win32_PnPEntity -OperationTimeoutSec 4 -ErrorAction Stop | Where-Object {$_.Name -match '\b(NPU|Neural|AI Boost|Ryzen AI|Hexagon)\b'} | Select-Object Name,Manufacturer);$nc=$true}catch{$w.Add('NPU CIM 조회 미완료')};[pscustomobject]@{cpu=$cpu;gpus=$gpus;npus=$npus;gpuComplete=$gc;npuComplete=$nc;warnings=@($w.ToArray())}|ConvertTo-Json -Depth 4 -Compress`
	probeCtx, probeCancel := context.WithTimeout(ctx, 15*time.Second)
	systemDir, systemErr := windows.GetSystemDirectory()
	if systemErr != nil {
		d.Warnings = append(d.Warnings, "Windows 시스템 폴더를 확인하지 못해 GPU·NPU 조회를 완료하지 못했습니다.")
	} else {
		cmd := exec.CommandContext(probeCtx, filepath.Join(systemDir, "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", probe)
		fallback := "0"
		if d.CPU == "Unknown CPU" {
			fallback = "1"
		}
		cmd.Env = append(os.Environ(), "MCAST_PROBE_CPU_FALLBACK="+fallback)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		out, probeErr := cmd.Output()
		if probeErr != nil {
			if probeCtx.Err() != nil {
				d.Warnings = append(d.Warnings, "GPU·NPU 장치 조회가 시간 제한 또는 취소로 미완료되었습니다. 장치가 없다는 의미는 아닙니다.")
			} else {
				d.Warnings = append(d.Warnings, "GPU·NPU 장치 조회를 완료하지 못했습니다. Windows CIM 상태를 확인하세요.")
			}
		} else if result, parseErr := parseWindowsHardwareProbe(out); parseErr != nil {
			d.Warnings = append(d.Warnings, parseErr.Error())
		} else {
			if d.CPU == "Unknown CPU" && result.CPU != "" {
				d.CPU = result.CPU
			}
			d.Devices = append(d.Devices, result.Devices...)
			d.Warnings = append(d.Warnings, result.Warnings...)
		}
	}
	probeCancel()
	if d.CPU == "Unknown CPU" {
		d.Warnings = append(d.Warnings, "CPU 이름을 레지스트리·CIM에서 확인하지 못했습니다.")
	}
	d.Devices = append([]Device{{Name: d.CPU, Kind: "cpu", Vendor: "Unknown", VRAMGB: d.AvailableRAMGB, Verified: false}}, d.Devices...)

	// nvidia-smi timeout probe
	nctx, ncancel := context.WithTimeout(ctx, 2*time.Second)
	defer ncancel()
	smiOut, err := exec.CommandContext(nctx, "nvidia-smi", "--query-gpu=name,memory.total,memory.free", "--format=csv,noheader").Output()
	if err == nil {
		lines := strings.Split(strings.TrimSpace(string(smiOut)), "\n")
		for _, l := range lines {
			parts := strings.Split(l, ",")
			if len(parts) >= 3 {
				for i := range d.Devices {
					if d.Devices[i].Vendor == "NVIDIA" && strings.Contains(d.Devices[i].Name, strings.TrimSpace(parts[0])) {
						freeStr := strings.TrimSpace(strings.ReplaceAll(parts[2], " MiB", ""))
						var freeMB float64
						if _, err := fmt.Sscanf(freeStr, "%f", &freeMB); err == nil {
							d.Devices[i].VRAMGB = freeMB / 1024.0
						}
					}
				}
			}
		}
	}

	d.Recommendations = RecommendModels(d)
	d.MobileEquivalent = d.Measured && d.RAMGB >= 8 && d.AvailableRAMGB >= 4 && d.FreeDiskGB >= 8
	return d
}
