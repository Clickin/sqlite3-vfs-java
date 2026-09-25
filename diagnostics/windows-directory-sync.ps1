$ErrorActionPreference = 'Stop'
Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer, Model, HypervisorPresent | Format-List
Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber | Format-List
Get-Volume | Where-Object DriveLetter | Select-Object DriveLetter, FileSystem, DriveType | Format-Table
Write-Host "Runner environment=$env:RUNNER_ENVIRONMENT image=$env:ImageOS version=$env:ImageVersion"
Write-Host "Container marker=$([bool](Test-Path 'HKLM:\SYSTEM\CurrentControlSet\Control\ContainerType'))"
Get-Service docker, containerd, kubelet -ErrorAction SilentlyContinue | Format-Table Name, Status
$work = Join-Path $env:RUNNER_TEMP ('dir-sync-' + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
try {
@'
import java.nio.channels.*;
import java.nio.file.*;
public class DirectoryProbe {
  public static void main(String[] args) throws Exception {
    System.out.println("JAVA " + Runtime.version());
    for (String name : args) {
      Path p = Path.of(name);
      for (StandardOpenOption mode : new StandardOpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE}) {
        FileChannel c;
        try { c=FileChannel.open(p, mode); System.out.println("JAVA OPEN OK " + mode + " " + p); }
        catch(Exception e) { System.out.println("JAVA OPEN FAIL " + mode + " " + p + " " + e); continue; }
        try(c) {
          try { c.force(true); System.out.println("JAVA FORCE OK " + mode + " " + p); }
          catch(Exception e) { System.out.println("JAVA FORCE FAIL " + mode + " " + p + " " + e); }
        }
      }
    }
  }
}
'@ | Set-Content (Join-Path $work 'DirectoryProbe.java')
$regular = Join-Path $work 'ordinary-file'
[IO.File]::WriteAllText($regular, 'test')
& java --illegal-native-access=deny (Join-Path $work 'DirectoryProbe.java') $work $regular
if ($LASTEXITCODE -ne 0) { throw 'Java diagnostic failed' }
Add-Type @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class DirectoryNativeProbe {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
  [DllImport("kernel32.dll", SetLastError=true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  static extern bool FlushFileBuffers(SafeFileHandle handle);
  public static void Run(string path) {
    foreach(uint flags in new uint[]{0, 0x02000000}) {
      foreach(uint access in new uint[]{0,0x80000000,0x40000000,0xC0000000}) {
        using(var handle=CreateFileW(path,access,7,IntPtr.Zero,3,flags,IntPtr.Zero)) {
          if(handle.IsInvalid) { Console.WriteLine("WIN32 OPEN FAIL access={0:X8} flags={1:X8} error={2} path={3}",access,flags,Marshal.GetLastWin32Error(),path); continue; }
          Console.WriteLine("WIN32 OPEN OK access={0:X8} flags={1:X8} path={2}",access,flags,path);
          bool ok=FlushFileBuffers(handle);
          int error=ok?0:Marshal.GetLastWin32Error();
          Console.WriteLine("WIN32 FLUSH ok={0} error={1}",ok,error);
        }
      }
    }
  }
}
'@
[DirectoryNativeProbe]::Run($work)
[DirectoryNativeProbe]::Run($regular)
} finally {
  Remove-Item -Recurse -Force $work
}
