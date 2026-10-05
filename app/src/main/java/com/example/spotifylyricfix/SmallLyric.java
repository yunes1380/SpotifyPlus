package com.example.spotifylyricfix;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

// Fallback small lyric for the Now Playing view, v1.4.1 core.
// Renders every synced type (Syllable/Line/Static) as one clean line above the title.
public class SmallLyric {
    private static final String TAG = "[LyricFix]";
    private static final String API = "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/";
    private static volatile String pkg = "spotify";

    private static volatile String trackUri = "";
    private static volatile String trackTitle = "";
    private static volatile List<LyricLine> lines = new ArrayList<LyricLine>();
    private static volatile String linesForUri = "";
    private static volatile String linesType = "";

    private static volatile long basePosMs = 0;
    private static volatile long baseElapsed = 0;
    private static volatile float speed = 1.0f;
    private static volatile boolean playing = false;

    private static volatile Activity npvActivity = null;
    private static volatile boolean npvResumed = false;

    private static TextView overlay = null;
    private static String overlayForUri = "";
    private static boolean anchored = false;
    private static int tickCount = 0;
    private static String anchorLogUri = "";

    private static Handler handler = null;
    private static boolean tickerOn = false;
    private static boolean plusVisible = false;

    static class Ticker implements Runnable {
        @Override
        public void run() {
            tick();
            if (npvResumed && handler != null) {
                handler.postDelayed(this, 200);
            } else {
                tickerOn = false;
            }
        }
    }

    static class TickOnce implements Runnable {
        @Override
        public void run() {
            tick();
        }
    }

    // onMetadata runs on a MediaSession worker thread: never touch views directly.
    static class HideOverlay implements Runnable {
        @Override
        public void run() {
            try {
                if (overlay != null) overlay.setVisibility(View.GONE);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hide error: " + t);
            }
        }
    }

    static class Fetcher implements Runnable {
        private final String uri;
        private final String id;
        private final String title;
        private final String artist;
        private final boolean samsung;

        Fetcher(String uri, String id, String title, String artist, boolean samsung) {
            this.uri = uri;
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.samsung = samsung;
        }

        @Override
        public void run() {
            Parsed parsed;
            if (samsung) {
                parsed = fetchLrclib(title, artist);
            } else {
                String content = download(API + id);
                if (content == null) {
                    XposedBridge.log(TAG + " lyrics download failed for " + id);
                    return;
                }
                parsed = parse(content);
            }
            if (parsed == null) {
                XposedBridge.log(TAG + " lyrics parse failed for " + id);
                return;
            }
            if (!uri.equals(trackUri)) return;
            lines = parsed.lines;
            linesForUri = uri;
            linesType = parsed.type;
            long firstStart = parsed.lines.isEmpty() ? -1 : parsed.lines.get(0).startMs;
            XposedBridge.log(TAG + " small lyric ready: type=" + parsed.type
                    + " lines=" + parsed.lines.size() + " firstStartMs=" + firstStart);
            if (handler != null) {
                handler.post(new TickOnce());
            }
        }
    }

    static class Parsed {
        String type;
        List<LyricLine> lines = new ArrayList<LyricLine>();
    }

    private static volatile String trackArtist = "";

    public static void onMetadata(String uri, String title, String artist) {
        if (uri == null) uri = "";
        if (title == null) title = "";
        if (artist == null) artist = "";
        String key = "samsung".equals(pkg) ? "samsung:" + title + "|" + artist : uri;
        if (key.equals(trackUri)) {
            if (!title.equals(trackTitle)) trackTitle = title;
            if (!artist.equals(trackArtist)) trackArtist = artist;
            return;
        }
        trackUri = key;
        trackTitle = title;
        trackArtist = artist;
        lines = new ArrayList<LyricLine>();
        linesForUri = "";
        linesType = "";
        overlayForUri = "";
        anchored = false;
        tickCount = 0;
        plusVisible = false;
        lastAnimatedLine = null;
        lastLitCount = -1;
        ensureHandler();
        if (handler != null) {
            handler.post(new HideOverlay());
        } else if (overlay != null) {
            try {
                overlay.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }
        if ("samsung".equals(pkg)) {
            if (title.length() < 2) return;
            XposedBridge.log(TAG + " samsung track: title=" + title + " artist=" + artist);
            Thread thread = new Thread(new Fetcher(key, "", title, artist, true));
            thread.setDaemon(true);
            thread.start();
            return;
        }
        if (!uri.startsWith("spotify:track:")) return;
        String[] parts = uri.split(":");
        if (parts.length < 3 || parts[2].length() < 5) return;
        XposedBridge.log(TAG + " track: " + uri + " title=" + title);
        Thread thread = new Thread(new Fetcher(uri, parts[2], title, artist, false));
        thread.setDaemon(true);
        thread.start();
    }

    public static void onState(long posMs, float spd, boolean pl, long updateElapsed) {
        basePosMs = posMs;
        speed = spd <= 0 ? 1.0f : spd;
        playing = pl;
        baseElapsed = updateElapsed > 0 ? updateElapsed : SystemClock.elapsedRealtime();
    }

    public static void setPkg(String value) {
        pkg = value;
    }

    public static void onNpvResumed(Activity activity) {
        npvActivity = activity;
        npvResumed = true;
        ensureHandler();
        if (!"samsung".equals(pkg)) return;
        attachOverlay(activity);
        startTicker();
        tick();
    }

    public static void onNpvPaused(Activity activity) {
        if (npvActivity == activity) {
            npvResumed = false;
            removeOverlay();
        }
    }

    private static void ensureHandler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
    }

    private static void startTicker() {
        if (tickerOn || handler == null) return;
        tickerOn = true;
        handler.post(new Ticker());
    }

    private static long currentPos() {
        if (!playing) return basePosMs;
        long delta = SystemClock.elapsedRealtime() - baseElapsed;
        if (delta < 0) delta = 0;
        if (delta > 3600000) delta = 3600000;
        return basePosMs + (long) (delta * speed);
    }

    private static void tick() {
        if (overlay == null || !npvResumed) return;
        if (!"samsung".equals(pkg)) {
            try {
                if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            tickCount++;
            if (!anchored && tickCount % 4 == 0) tryReanchor();
            if (tickCount % 10 == 0) plusVisible = checkPlusNpvVisible();
            if (plusVisible) {
                if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
                return;
            }
            if (lines.isEmpty() || !trackUri.equals(linesForUri)) {
                if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
                return;
            }
            if ("Static".equals(linesType)) {
                setLineText(lines.get(0).text);
                if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
                return;
            }
            long pos = currentPos();
            LyricLine current = null;
            for (int i = 0; i < lines.size(); i++) {
                LyricLine line = lines.get(i);
                if (line.startMs <= pos + 300) {
                    current = line;
                } else {
                    break;
                }
            }
            if (current == null || pos > current.endMs + 1500) {
                if (overlay.getVisibility() != View.GONE) overlay.setVisibility(View.GONE);
                return;
            }
            setAnimatedLine(current, pos);
            if (overlay.getVisibility() != View.VISIBLE) overlay.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tick error: " + t);
        }
    }

    private static void setLineText(String text) {
        if (text == null) return;
        CharSequence old = overlay.getText();
        if (old == null || !text.equals(old.toString())) {
            overlay.setText(text);
        }
    }

    private static LyricLine lastAnimatedLine = null;
    private static int lastLitCount = -1;

    // Word-by-word highlight: sung words solid white, upcoming words dimmed.
    private static void setAnimatedLine(LyricLine line, long pos) {
        try {
            if (line.parts == null || line.parts.size() <= 1) {
                lastAnimatedLine = null;
                lastLitCount = -1;
                setLineText(line.text);
                return;
            }
            int litCount = 0;
            for (int i = 0; i < line.parts.size(); i++) {
                if (pos >= line.parts.get(i).startMs) litCount++;
            }
            if (line == lastAnimatedLine && litCount == lastLitCount) return;
            lastAnimatedLine = line;
            lastLitCount = litCount;
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            for (int i = 0; i < line.parts.size(); i++) {
                LyricPart part = line.parts.get(i);
                int start = sb.length();
                sb.append(part.text);
                int color = pos >= part.startMs ? 0xFFFFFFFF : 0x66FFFFFF;
                sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                        start, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            overlay.setText(sb);
        } catch (Throwable t) {
            setLineText(line.text);
        }
    }

    private static boolean isPartOfWord(String sylObj) {
        try {
            int keyIndex = sylObj.indexOf("\"IsPartOfWord\"");
            if (keyIndex < 0) return false;
            int colon = sylObj.indexOf(':', keyIndex);
            if (colon < 0) return false;
            String rest = sylObj.substring(colon + 1).trim();
            return rest.startsWith("true");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sortLines(List<LyricLine> list) {
        for (int i = 1; i < list.size(); i++) {
            LyricLine key = list.get(i);
            int j = i - 1;
            while (j >= 0 && list.get(j).startMs > key.startMs) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, key);
        }
    }

    private static void attachOverlay(Activity activity) {
        try {
            if (overlay != null && overlay.getParent() != null
                    && overlayForUri.equals(trackUri) && npvActivity == activity) {
                return;
            }
            removeOverlay();
            TextView view = new TextView(activity);
            view.setTag("lyricfix");
            view.setTextColor(Color.WHITE);
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            view.setMarqueeRepeatLimit(-1);
            view.setSelected(true);
            view.setShadowLayer(4.0f, 0.0f, 2.0f, Color.BLACK);
            view.setPadding(16, 8, 16, 8);
            view.setVisibility(View.GONE);
            View titleView = findTitleView(activity.getWindow().getDecorView());
            boolean placed = false;
            if (titleView != null && titleView.getParent() instanceof ViewGroup) {
                try {
                    ViewGroup parent = (ViewGroup) titleView.getParent();
                    int index = parent.indexOfChild(titleView);
                    parent.addView(view, index);
                    placed = true;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " anchor insert failed, fallback: " + t);
                }
            }
            if (!placed && !"samsung".equals(pkg)) {
                try {
                    activity.addContentView(view,
                            new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                    placed = true;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " overlay attach failed: " + t);
                    return;
                }
            }
            if (!placed) {
                if (!trackUri.equals(anchorLogUri)) {
                    anchorLogUri = trackUri;
                    XposedBridge.log(TAG + " no title anchor, overlay skipped");
                }
                return;
            }
            overlay = view;
            overlayForUri = trackUri;
            anchored = titleView != null;
            if (anchored) {
                XposedBridge.log(TAG + " small lyric anchored above title");
            } else if (!trackUri.equals(anchorLogUri)) {
                anchorLogUri = trackUri;
                XposedBridge.log(TAG + " anchor miss for title=" + trackTitle);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " attach error: " + t);
        }
    }

    private static void removeOverlay() {
        try {
            if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " remove error: " + t);
        } finally {
            overlay = null;
            overlayForUri = "";
            anchored = false;
        }
    }

    // Retry moving a fallback-placed overlay above the song title once it appears.
    private static void tryReanchor() {
        if (anchored || overlay == null || npvActivity == null || !npvResumed) return;
        try {
            View titleView = findTitleView(npvActivity.getWindow().getDecorView());
            if (titleView == null || !(titleView.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) titleView.getParent();
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            parent.addView(overlay, parent.indexOfChild(titleView));
            anchored = true;
            XposedBridge.log(TAG + " small lyric re-anchored above title");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " re-anchor failed: " + t);
        }
    }

    // True if Plus's own NPV lyric views are on screen: yield to the original.
    private static boolean checkPlusNpvVisible() {
        try {
            Activity activity = npvActivity;
            if (activity == null) return false;
            View decor = activity.getWindow().getDecorView();
            if (decor == null) return false;
            boolean found = findPlusNpvView(decor);
            if (found != plusVisible) {
                XposedBridge.log(TAG + " Plus NPV visible=" + found);
            }
            return found;
        } catch (Throwable t) {
            return plusVisible;
        }
    }

    private static boolean findPlusNpvView(View root) {
        try {
            String name = root.getClass().getName();
            if (name.contains("spotifyplus")
                    && (name.contains("GradientTextView") || name.contains("LiveText")
                            || name.contains("AnimatedLetter") || name.contains("AnimatedSyllable"))) {
                if (root instanceof TextView) {
                    CharSequence text = ((TextView) root).getText();
                    if (text != null && text.length() > 0) return true;
                    return false;
                }
                return true;
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (findPlusNpvView(group.getChildAt(i))) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String normalize(String s) {        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        String lower = s.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_') {
                out.append(' ');
            }
        }
        String collapsed = out.toString().trim();
        while (collapsed.contains("  ")) {
            collapsed = collapsed.replace("  ", " ");
        }
        return collapsed;
    }

    private static View findTitleView(View root) {
        try {
            String want = normalize(trackTitle);
            if (want.length() < 3) return null;
            return findTitleViewIn(root, want);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " find title error: " + t);
        }
        return null;
    }

    private static View findTitleViewIn(View root, String want) {
        try {
            if (root instanceof TextView) {
                Object tag = root.getTag();
                if ("lyricfix".equals(tag)) return null;
                CharSequence text = ((TextView) root).getText();
                if (text != null) {
                    String got = normalize(text.toString());
                    if (got.length() >= 3
                            && (got.equals(want) || got.contains(want) || want.contains(got))) {
                        return root;
                    }
                }
                return null;
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findTitleViewIn(group.getChildAt(i), want);
                    if (found != null) return found;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " find title error: " + t);
        }
        return null;
    }

    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() > 2000000) break;
            }
            reader.close();
            return out.toString();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " download error: " + t);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // Synced lyrics for local/Samsung tracks via LRCLIB (title + artist search).
    private static Parsed fetchLrclib(String title, String artist) {
        try {
            String query = (artist.length() > 0 ? artist + " " : "") + title;
            String content = download("https://lrclib.net/api/search?q=" + encode(query));
            if (content == null) return null;
            String body = content.trim();
            if (body.startsWith("[")) body = body.substring(1);
            List<String> items = MiniJson.splitTopLevel(body);
            String synced = null;
            String plain = null;
            for (int i = 0; i < items.size(); i++) {
                String item = items.get(i);
                String s = MiniJson.optString(item, "syncedLyrics");
                String p = MiniJson.optString(item, "plainLyrics");
                if (s != null && s.length() > 10) {
                    synced = s;
                    break;
                }
                if (plain == null && p != null && p.length() > 10) plain = p;
                if (i >= 4) break;
            }
            Parsed parsed = new Parsed();
            if (synced != null) {
                parsed.type = "LRC";
                parsed.lines = parseLrc(synced);
                if (parsed.lines.isEmpty()) return null;
                sortLines(parsed.lines);
                return parsed;
            }
            if (plain != null) {
                parsed.type = "Static";
                String[] rawLines = plain.split("\n");
                for (int i = 0; i < rawLines.length; i++) {
                    String t = rawLines[i].trim();
                    if (t.length() == 0) continue;
                    parsed.lines.add(new LyricLine(-1, -1, t));
                }
                if (parsed.lines.isEmpty()) return null;
                return parsed;
            }
            XposedBridge.log(TAG + " lrclib no lyrics for " + title);
            return null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " lrclib error: " + t);
            return null;
        }
    }

    // Parses [mm:ss.xx] synced lines. Returns timed lines (end = next start).
    private static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> out = new ArrayList<LyricLine>();
        try {
            String[] rawLines = lrc.split("\n");
            for (int i = 0; i < rawLines.length; i++) {
                String raw = rawLines[i].trim();
                if (raw.length() == 0 || !raw.startsWith("[")) continue;
                int pos = 0;
                List<Long> times = new ArrayList<Long>();
                while (pos < raw.length() && raw.charAt(pos) == '[') {
                    int close = raw.indexOf(']', pos);
                    if (close < 0) break;
                    Long ms = parseLrcTime(raw.substring(pos + 1, close));
                    if (ms == null) break;
                    times.add(ms);
                    pos = close + 1;
                }
                String text = raw.substring(pos).trim();
                if (times.isEmpty() || text.length() == 0) continue;
                for (int j = 0; j < times.size(); j++) {
                    out.add(new LyricLine(times.get(j).longValue(), -1, text));
                }
            }
            sortLines(out);
            for (int i = 0; i < out.size(); i++) {
                LyricLine line = out.get(i);
                long end = (i + 1 < out.size()) ? out.get(i + 1).startMs : line.startMs + 4000;
                out.set(i, new LyricLine(line.startMs, end, line.text));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " lrc parse error: " + t);
        }
        return out;
    }

    private static Long parseLrcTime(String s) {
        try {
            s = s.trim();
            int colon = s.indexOf(':');
            if (colon < 0) return null;
            int minutes = Integer.parseInt(s.substring(0, colon));
            double seconds = Double.parseDouble(s.substring(colon + 1));
            if (minutes < 0 || minutes > 999 || seconds < 0 || seconds >= 60) return null;
            return Long.valueOf(minutes * 60000L + (long) (seconds * 1000.0d));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Parsed parse(String content) {
        try {
            Parsed parsed = new Parsed();
            String type = MiniJson.optString(content, "Type");
            if (type == null) return null;
            parsed.type = type;
            if ("Syllable".equals(type)) {
                String body = MiniJson.optArrayBody(content, "Content");
                List<String> items = MiniJson.splitTopLevel(body);
                for (int i = 0; i < items.size(); i++) {
                    String item = items.get(i);
                    String lead = MiniJson.optObject(item, "Lead");
                    String vocal = lead != null ? lead : item;
                    String sylBody = MiniJson.optArrayBody(vocal, "Syllables");
                    List<String> syls = MiniJson.splitTopLevel(sylBody);
                    StringBuilder text = new StringBuilder();
                    List<LyricPart> parts = new ArrayList<LyricPart>();
                    long start = Long.MAX_VALUE;
                    long end = Long.MIN_VALUE;
                    for (int j = 0; j < syls.size(); j++) {
                        String syl = syls.get(j);
                        String t = MiniJson.optString(syl, "Text");
                        Double s = MiniJson.optDouble(syl, "StartTime");
                        Double e = MiniJson.optDouble(syl, "EndTime");
                        if (t == null || s == null || e == null) continue;
                        long sMs = (long) (s.doubleValue() * 1000.0d);
                        long eMs = (long) (e.doubleValue() * 1000.0d);
                        String display = t;
                        if (text.length() > 0 && !isPartOfWord(syl)) {
                            display = " " + t;
                            text.append(' ');
                        }
                        text.append(t);
                        parts.add(new LyricPart(sMs, eMs, display));
                        if (sMs < start) start = sMs;
                        if (eMs > end) end = eMs;
                    }
                    if (text.length() > 0 && start != Long.MAX_VALUE) {
                        parsed.lines.add(new LyricLine(start, end, text.toString(), parts));
                    }
                }
            } else if ("Line".equals(type)) {
                String body = MiniJson.optArrayBody(content, "Content");
                List<String> items = MiniJson.splitTopLevel(body);
                for (int i = 0; i < items.size(); i++) {
                    String item = items.get(i);
                    String t = MiniJson.optString(item, "Text");
                    Double s = MiniJson.optDouble(item, "StartTime");
                    Double e = MiniJson.optDouble(item, "EndTime");
                    if (t == null) {
                        String lead = MiniJson.optObject(item, "Lead");
                        if (lead == null) continue;
                        t = MiniJson.optString(lead, "Text");
                        s = MiniJson.optDouble(lead, "StartTime");
                        e = MiniJson.optDouble(lead, "EndTime");
                    }
                    if (t == null || s == null || e == null) continue;
                    parsed.lines.add(new LyricLine(
                            (long) (s.doubleValue() * 1000.0d),
                            (long) (e.doubleValue() * 1000.0d), t));
                }
            } else if ("Static".equals(type)) {
                String body = MiniJson.optArrayBody(content, "Lines");
                List<String> items = MiniJson.splitTopLevel(body);
                for (int i = 0; i < items.size(); i++) {
                    String t = MiniJson.optString(items.get(i), "Text");
                    if (t == null || t.trim().length() == 0) continue;
                    parsed.lines.add(new LyricLine(-1, -1, t));
                }
            } else {
                return null;
            }
            sortLines(parsed.lines);
            return parsed;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " parse error: " + t);
            return null;
        }
    }
}
