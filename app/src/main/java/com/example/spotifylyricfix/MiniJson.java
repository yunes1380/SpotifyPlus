package com.example.spotifylyricfix;

import java.util.ArrayList;
import java.util.List;

// Minimal JSON helpers for the SpotifyPlus lyrics API shape.
// Handles objects with string/double fields and nested arrays/objects.
public class MiniJson {

    public static String optString(String obj, String key) {
        int keyIndex = indexOfKey(obj, key);
        if (keyIndex < 0) return null;
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && isSpace(obj.charAt(i))) i++;
        if (i >= obj.length() || obj.charAt(i) != '"') return null;
        StringBuilder out = new StringBuilder();
        i++;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                char next = obj.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '/') {
                    out.append(next);
                    i += 2;
                    continue;
                } else if (next == 'n') {
                    out.append('\n');
                    i += 2;
                    continue;
                } else if (next == 'u' && i + 5 < obj.length()) {
                    try {
                        out.append((char) Integer.parseInt(obj.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException ignored) {
                    }
                    i += 6;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '"') break;
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static Double optDouble(String obj, String key) {
        int keyIndex = indexOfKey(obj, key);
        if (keyIndex < 0) return null;
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && isSpace(obj.charAt(i))) i++;
        int start = i;
        while (i < obj.length() && "-+0123456789.eE".indexOf(obj.charAt(i)) >= 0) i++;
        if (start == i) return null;
        try {
            return Double.parseDouble(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Returns the inner body (without outer brackets) of the array value for key, or null.
    public static String optArrayBody(String obj, String key) {
        int keyIndex = indexOfKey(obj, key);
        if (keyIndex < 0) return null;
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && isSpace(obj.charAt(i))) i++;
        if (i >= obj.length() || obj.charAt(i) != '[') return null;
        int depth = 0;
        boolean inString = false;
        int start = -1;
        for (int j = i; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') {
                if (depth == 0) start = j + 1;
                depth++;
                continue;
            }
            if (c == ']') {
                depth--;
                if (depth == 0) return obj.substring(start, j);
                if (depth < 0) return null;
            }
        }
        return null;
    }

    // Returns the full "{...}" object value for key (including braces), or null.
    public static String optObject(String obj, String key) {
        int keyIndex = indexOfKey(obj, key);
        if (keyIndex < 0) return null;
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && isSpace(obj.charAt(i))) i++;
        if (i >= obj.length() || obj.charAt(i) != '{') return null;
        int depth = 0;
        boolean inString = false;
        for (int j = i; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) return obj.substring(i, j + 1);
            }
        }
        return null;
    }

    // Splits an array body into top-level JSON values (objects expected).
    public static List<String> splitTopLevel(String arrayBody) {
        List<String> out = new ArrayList<String>();
        if (arrayBody == null) return out;
        int depthCurly = 0;
        int depthSquare = 0;
        boolean inString = false;
        int start = -1;
        for (int j = 0; j < arrayBody.length(); j++) {
            char c = arrayBody.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                if (depthCurly == 0 && depthSquare == 0 && start < 0) start = j;
                if (c == '{') depthCurly++;
                else depthSquare++;
                continue;
            }
            if (c == '}' || c == ']') {
                if (c == '}') depthCurly--;
                else depthSquare--;
                if (depthCurly == 0 && depthSquare == 0 && start >= 0) {
                    out.add(arrayBody.substring(start, j + 1).trim());
                    start = -1;
                }
                continue;
            }
        }
        return out;
    }

    private static int indexOfKey(String obj, String key) {
        String quoted = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int index = obj.indexOf(quoted, from);
            if (index < 0) return -1;
            int after = index + quoted.length();
            int i = after;
            while (i < obj.length() && isSpace(obj.charAt(i))) i++;
            if (i < obj.length() && obj.charAt(i) == ':') return index;
            from = after;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
