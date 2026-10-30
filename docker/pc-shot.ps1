# Screenshots only the running BitChord desktop window (PrintWindow, so nothing else on screen is captured).
param([string]$Out)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text; using System.Collections.Generic;
public class W {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  public struct RECT { public int L, T, R, B; }
  public static List<IntPtr> For(uint want) {
    var res = new List<IntPtr>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == want && IsWindowVisible(h)) res.Add(h); return true; }, IntPtr.Zero);
    return res;
  }
}
"@
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*desktop.MainKt*' }
foreach ($p in $procs) {
  "java pid $($p.ProcessId) MB $([int]($p.WorkingSetSize/1MB))"
  foreach ($h in [W]::For([uint32]$p.ProcessId)) {
    $sb = New-Object Text.StringBuilder 256; [void][W]::GetWindowText($h, $sb, 256)
    $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r)
    $w = $r.R - $r.L; $ht = $r.B - $r.T
    "window '$($sb.ToString())' ${w}x$ht"
    if ($w -gt 200 -and $ht -gt 200 -and $Out) {
      $b = New-Object Drawing.Bitmap $w, $ht
      $g = [Drawing.Graphics]::FromImage($b); $dc = $g.GetHdc()
      [void][W]::PrintWindow($h, $dc, 2)
      $g.ReleaseHdc($dc); $g.Dispose(); $b.Save($Out, [Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
      "saved $Out"
    }
  }
}
