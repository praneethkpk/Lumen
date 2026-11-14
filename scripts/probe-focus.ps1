param(
    [string]$Action = "status",
    [string]$Text = ""
)

Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WinFocusProbe {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
  public static string Fg() {
    var h = GetForegroundWindow();
    var sb = new StringBuilder(512); int n = GetWindowText(h, sb, 512);
    var cn = new StringBuilder(256); GetClassName(h, cn, 256);
    string t = n > 0 ? sb.ToString() : "(no title)";
    return t + "  [" + cn.ToString() + "]";
  }
  public static void Press(byte vk, bool down) {
    keybd_event(vk, 0, down ? 0u : 2u, UIntPtr.Zero);
  }
  public static void AltTab() {
    Press(0x12, true);   // VK_LMENU down
    System.Threading.Thread.Sleep(60);
    Press(0x09, true);   // VK_TAB down
    System.Threading.Thread.Sleep(120);
    Press(0x09, false);  // VK_TAB up
    Press(0x12, false);  // VK_LMENU up
  }
  public static void AltSpace() {
    Press(0x12, true);   // VK_LMENU down
    System.Threading.Thread.Sleep(40);
    Press(0x20, true);   // VK_SPACE down
    System.Threading.Thread.Sleep(40);
    Press(0x20, false);  // VK_SPACE up
    Press(0x12, false);  // VK_LMENU up
  }
  public static void Chord(byte[] mods, byte key) {
    foreach (var m in mods) { Press(m, true); }
    System.Threading.Thread.Sleep(40);
    Press(key, true);
    System.Threading.Thread.Sleep(40);
    Press(key, false);
    foreach (var m in mods) { Press(m, false); }
  }
}
"@

switch ($Action) {
  "status" {
    "foreground: " + [WinFocusProbe]::Fg()
  }
  "alt-tab" {
    "before: " + [WinFocusProbe]::Fg()
    [WinFocusProbe]::AltTab()
    Start-Sleep -Milliseconds 1500
    "after : " + [WinFocusProbe]::Fg()
  }
  "esc" {
    [WinFocusProbe]::Press(0x1B, $true)
    Start-Sleep -Milliseconds 30
    [WinFocusProbe]::Press(0x1B, $false)
    Start-Sleep -Milliseconds 600
    "after : " + [WinFocusProbe]::Fg()
  }
  "altspace" {
    "before: " + [WinFocusProbe]::Fg()
    [WinFocusProbe]::AltSpace()
    Start-Sleep -Seconds 2
    "after : " + [WinFocusProbe]::Fg()
  }
  "chord" {
    $mods = @()
    $key = 0x20
    foreach ($part in $Text.ToUpperInvariant().Split('+')) {
      switch ($part.Trim()) {
        'CTRL' { $mods += [byte]0x11 }
        'ALT' { $mods += [byte]0x12 }
        'SHIFT' { $mods += [byte]0x10 }
        'WIN' { $mods += [byte]0x5B }
        'SPACE' { $key = [byte]0x20 }
      }
    }
    "before: " + [WinFocusProbe]::Fg()
    [WinFocusProbe]::Chord([byte[]]$mods, $key)
    Start-Sleep -Seconds 2
    "after : " + [WinFocusProbe]::Fg()
  }
  "type" {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Start-Sleep -Milliseconds 600
    "after : " + [WinFocusProbe]::Fg()
  }
  "screenshot" {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $graphics = [System.Drawing.Graphics]::FromImage($bmp)
    $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $path = Join-Path $env:TEMP 'lumen-probe.png'
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $bmp.Dispose()
    "saved: $path"
  }
}
