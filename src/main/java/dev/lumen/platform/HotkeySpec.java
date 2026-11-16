package dev.lumen.platform;

import java.util.Locale;

/**
 * One key combination, in the form {@code RegisterHotKey} wants: modifier flags plus a virtual
 * key code.
 *
 * <p>Kept separate from {@link HotkeyManager} because parsing and validating user input is a
 * different job from keeping a registration alive, and because a bad string here fails silently
 * at the Win32 layer: {@code RegisterHotKey} with the wrong virtual key just registers something
 * the user will never press.
 *
 * @param mods bitmask of {@code MOD_ALT}, {@code MOD_CONTROL}, {@code MOD_SHIFT}, {@code MOD_WIN}
 * @param vk   Windows virtual key code
 */
record HotkeySpec(int mods, int vk) {

    private static final int MOD_ALT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_SHIFT = 0x0004;
    private static final int MOD_WIN = 0x0008;

    /**
     * Whether this is a combination worth registering.
     *
     * <p>Two rejections matter. A bare key with no modifier would swallow that key system-wide,
     * and an unrecognised key name would otherwise fall through to Space and register
     * Alt+Space while the config says something else — a wrong hotkey that looks successful.
     */
    static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        int mods = 0;
        boolean sawKey = false;
        for (String part : raw.toUpperCase(Locale.ROOT).split("\\+")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "ALT" -> mods |= MOD_ALT;
                case "CTRL", "CONTROL" -> mods |= MOD_CONTROL;
                case "SHIFT" -> mods |= MOD_SHIFT;
                case "WIN", "META" -> mods |= MOD_WIN;
                default -> {
                    if (!isKnownKey(name)) {
                        return false;
                    }
                    sawKey = true;
                }
            }
        }
        return sawKey && mods != 0;
    }

    private static boolean isKnownKey(String key) {
        if (key.length() == 1) {
            char c = key.charAt(0);
            return Character.isLetterOrDigit(c) || c == '`';
        }
        return switch (key) {
            case "SPACE", "BACKTICK", "ENTER", "RETURN", "TAB", "ESC", "ESCAPE" -> true;
            default -> key.startsWith("F") && key.length() <= 3 && isFunctionKey(key);
        };
    }

    private static boolean isFunctionKey(String key) {
        try {
            int n = Integer.parseInt(key.substring(1));
            return n >= 1 && n <= 24;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static HotkeySpec parse(String raw) {
        int mods = 0;
        int vk = 0x20; // SPACE
        for (String part : raw.toUpperCase(Locale.ROOT).split("\\+")) {
            switch (part.trim()) {
                case "ALT" -> mods |= MOD_ALT;
                case "CTRL", "CONTROL" -> mods |= MOD_CONTROL;
                case "SHIFT" -> mods |= MOD_SHIFT;
                case "WIN", "META" -> mods |= MOD_WIN;
                default -> vk = virtualKey(part.trim());
            }
        }
        return new HotkeySpec(mods, vk);
    }

    private static int virtualKey(String key) {
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (Character.isLetterOrDigit(c)) {
                return Character.toUpperCase(c);
            }
            if (c == '`') {
                return 0xC0;
            }
            return 0x20;
        }
        return switch (key) {
            case "SPACE" -> 0x20;
            case "ENTER", "RETURN" -> 0x0D;
            case "TAB" -> 0x09;
            case "ESC", "ESCAPE" -> 0x1B;
            case "BACKTICK", "`" -> 0xC0;
            default -> {
                if (key.startsWith("F") && key.length() <= 3) {
                    try {
                        int n = Integer.parseInt(key.substring(1));
                        if (n >= 1 && n <= 24) {
                            yield 0x70 + n - 1;
                        }
                    } catch (NumberFormatException ignored) {
                        // Not a function key after all; fall through to SPACE.
                    }
                }
                yield 0x20;
            }
        };
    }

    /** Human-readable form, in the same order Windows tools show modifiers. */
    String label() {
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CONTROL) != 0) {
            sb.append("Ctrl+");
        }
        if ((mods & MOD_ALT) != 0) {
            sb.append("Alt+");
        }
        if ((mods & MOD_SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((mods & MOD_WIN) != 0) {
            sb.append("Win+");
        }
        sb.append(keyName(vk));
        return sb.toString();
    }

    private static String keyName(int vk) {
        if (vk >= 0x41 && vk <= 0x5A) {
            return String.valueOf((char) vk);
        }
        if (vk >= 0x30 && vk <= 0x39) {
            return String.valueOf((char) vk);
        }
        if (vk >= 0x70 && vk <= 0x87) {
            return "F" + (vk - 0x70 + 1);
        }
        return switch (vk) {
            case 0x20 -> "Space";
            case 0x0D -> "Enter";
            case 0x09 -> "Tab";
            case 0x1B -> "Esc";
            case 0xC0 -> "`";
            default -> "VK_" + vk;
        };
    }

    boolean sameBinding(HotkeySpec other) {
        return other != null && mods == other.mods && vk == other.vk;
    }
}
