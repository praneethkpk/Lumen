package dev.lumen.platform;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Win32 calls that JNA's platform library does not expose.
 *
 * <p>Kept in one place so the ad-hoc bindings are easy to audit: shell icon extraction
 * ({@code SHGetFileInfo}), icon painting ({@code DrawIconEx}) and the minimized-window check
 * ({@code IsIconic}).
 */
public final class Win32Ext {

    private Win32Ext() {
    }

    interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean IsIconic(HWND window);

        boolean DrawIconEx(HDC dc, int x, int y, HICON icon, int width, int height,
                           int step, WinDef.HBRUSH brush, int flags);
    }

    interface Shell32Ex extends StdCallLibrary {
        Shell32Ex INSTANCE = Native.load("shell32", Shell32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        WinDef.UINT_PTR SHGetFileInfo(WString path, int attributes, SHFILEINFO info,
                                      int size, int flags);
    }

    @Structure.FieldOrder({"hIcon", "iIcon", "dwAttributes", "szDisplayName", "szTypeName"})
    public static final class SHFILEINFO extends Structure {
        public HICON hIcon;
        public int iIcon;
        public int dwAttributes;
        public char[] szDisplayName = new char[260];
        public char[] szTypeName = new char[80];
    }
}
