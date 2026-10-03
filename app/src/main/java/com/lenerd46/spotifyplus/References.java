package com.lenerd46.spotifyplus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import com.lenerd46.spotifyplus.beautifullyrics.entities.PlayerStateUpdatedListener;
import com.lenerd46.spotifyplus.beautifullyrics.entities.TrackStateChangedListener;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class References {
    public static Activity currentActivity = null;
    public static WeakReference<Object> playerState = new WeakReference<>(null);
    public static WeakReference<Object> playerStateWrapper = new WeakReference<>(null);
    public static String accessToken = "";
    public static String clientToken = "";
    public static String spotifyUsername = "";
    public static ClassLoader spotifyClassLoader;
    public static WeakReference<Typeface> beautifulFont = new WeakReference<>(null);
    public static WeakReference<Pair<String, String>> contextMenuTrack = new WeakReference<>(null);
    public static XModuleResources modResources = null;
    public static XResources xresources = null;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static Method hasTrackMethod;
    private static Method getContextTrack;
    private static volatile Class<?> playbackPositionStateClass;
    private static volatile Field playbackPositionField;

    // 9.1.88+: MediaSession metadata fallback so lyrics still resolve when the
    // player-model method names change. Updated from NowPlayingLyricsGradientHook.
    public static volatile String lastMediaUri = "";
    public static volatile String lastMediaTitle = "";

    public static void updateMediaTrack(String uri, String title) {
        if (uri != null) lastMediaUri = uri;
        if (title != null) lastMediaTitle = title;
    }

    private static SpotifyTrack mediaFallbackTrack() {
        String uri = lastMediaUri;
        if (uri == null || !uri.startsWith("spotify:track:")) return null;
        String title = lastMediaTitle == null ? "" : lastMediaTitle;
        XposedBridge.log("[SpotifyPlus] Using MediaSession fallback track: " + uri);
        return new SpotifyTrack(title, "", "", uri, 0, "", System.currentTimeMillis(), "", 0, false);
    }

    private static Method resolveHasTrackMethod(XC_LoadPackage.LoadPackageParam lpparam, DexKitBridge bridge, Object wrapper) {
        if (hasTrackMethod != null) return hasTrackMethod;
        String className = wrapper.getClass().getName();
        // Exact (pre-9.1.88)
        try {
            var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
            hasTrackMethod = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).returnType(boolean.class).paramCount(0))).get(0).getMethodInstance(lpparam.classLoader);
            return hasTrackMethod;
        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus] hasTrack exact fingerprint failed on " + className + ", trying behavior scan");
        }
        // Behavior scan: any no-arg boolean method (take first; verified by call below)
        for (Method m : wrapper.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                m.setAccessible(true);
                hasTrackMethod = m;
                XposedBridge.log("[SpotifyPlus] hasTrack behavior fallback: " + m.getName());
                return hasTrackMethod;
            }
        }
        return null;
    }

    private static Method resolveContextTrackMethod(XC_LoadPackage.LoadPackageParam lpparam, DexKitBridge bridge, Object wrapper, Class<?> contextClass) {
        if (getContextTrack != null) return getContextTrack;
        String className = wrapper.getClass().getName();
        // Exact (pre-9.1.88)
        try {
            var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
            getContextTrack = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).paramCount(0).returnType(Object.class))).get(0).getMethodInstance(lpparam.classLoader);
            return getContextTrack;
        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus] contextTrack exact fingerprint failed on " + className + ", trying behavior scan");
        }
        // Behavior scan: call 0-arg object-returning methods, keep the one yielding ContextTrack
        for (Method m : wrapper.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType().isPrimitive()
                    || m.getReturnType() == String.class || m.getReturnType() == void.class) continue;
            try {
                m.setAccessible(true);
                Object probe = m.invoke(wrapper);
                if (probe != null && contextClass.isInstance(probe)) {
                    getContextTrack = m;
                    XposedBridge.log("[SpotifyPlus] contextTrack behavior fallback: " + m.getName());
                    return getContextTrack;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static SpotifyTrack getTrackTitle(XC_LoadPackage.LoadPackageParam lpparam, DexKitBridge bridge) {
        if(playerState == null || playerState.get() == null) {
            XposedBridge.log("[SpotifyPlus] playerState is null, trying MediaSession fallback");
            return mediaFallbackTrack();
        }

        Object state = playerState.get();

        try {
            Object wrapper = XposedHelpers.callMethod(state, "track");

            Class<?> contextClass;
            try {
                contextClass = XposedHelpers.findClass("com.spotify.player.model.ContextTrack", lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("[SpotifyPlus] ContextTrack class missing, trying MediaSession fallback");
                SpotifyTrack fallback = mediaFallbackTrack();
                return fallback != null ? fallback : null;
            }
            Method hasTrack = resolveHasTrackMethod(lpparam, bridge, wrapper);
            if (hasTrack == null) {
                XposedBridge.log("[SpotifyPlus] hasTrack unresolvable, trying MediaSession fallback");
                return mediaFallbackTrack();
            }

            boolean hasTrackResult = (Boolean) hasTrack.invoke(wrapper);
            if(hasTrackResult) {
                Method contextTrackMethod = resolveContextTrackMethod(lpparam, bridge, wrapper, contextClass);
                if (contextTrackMethod == null) {
                    XposedBridge.log("[SpotifyPlus] contextTrack unresolvable, trying MediaSession fallback");
                    return mediaFallbackTrack();
                }

                Object ct = contextTrackMethod.invoke(wrapper);
                if(contextClass.isInstance(ct)) {
                    Object track = contextClass.cast(ct);

                    String uri = (String) XposedHelpers.callMethod(track, "uri");

                    @SuppressWarnings("unchecked")
                    Map<String, String> md = (Map<String, String>) XposedHelpers.callMethod(track, "metadata");

                    String title = md.get("title");
                    String artist = md.get("artist_name");
                    String album = md.get("album_title");
                    String color = md.get("extracted_color");
                    String imageId = md.get("image_large_url");
                    long position = 0;
                    long timestamp = 0;

                    Object posOpt = XposedHelpers.callMethod(state, "positionAsOfTimestamp");
                    Matcher m = DIGITS.matcher(posOpt.toString());
                    if(m.find()) {
                        long basePos = Long.parseLong(m.group());
                        timestamp = (Long) XposedHelpers.callMethod(state, "timestamp");
                        position = basePos + (System.currentTimeMillis() - timestamp);
                    }

                    Map<?, ?> metadata = (Map<?, ?>) XposedHelpers.getObjectField(track, "metadata");
                    boolean saved = false;

                    if(metadata.containsKey("collection.in_collection")) {
                        String savedValue = (String) metadata.get("collection.in_collection");
                        saved = Boolean.parseBoolean(savedValue);
                    }

//                    long duration = (Long) XposedHelpers.callMethod(state, "duration");

                    return new SpotifyTrack(title, artist, album, uri, position, color, timestamp, imageId, 0, saved);
                } else {
                    XposedBridge.log("[SpotifyPlus] ContextTrack not found, trying MediaSession fallback");
                    return mediaFallbackTrack();
                }
            } else {
                XposedBridge.log("[SpotifyPlus] No track found");
                return null;
            }
        } catch(Exception e) {
            Log.e("SpotifyPlus", "Error getting track information", e);
            SpotifyTrack fallback = mediaFallbackTrack();
            return fallback != null ? fallback : null;
        }
    }

    private static long previousMs;
    public static long getCurrentPlaybackPosition(DexKitBridge bridge, XC_LoadPackage.LoadPackageParam lpparam) {
        Object wrapper = References.playerStateWrapper == null ? null : References.playerStateWrapper.get();
        if (wrapper == null) return -1;

        Object state;
        try {
            state = XposedHelpers.callMethod(wrapper, "getState");

            if (state == null) return -1;
        } catch (Throwable t) {
            return -1;
        }

        try {
            Field field = playbackPositionField;
            if(field == null || playbackPositionStateClass != state.getClass()) {
                synchronized(References.class) {
                    field = playbackPositionField;
                    if(field == null || playbackPositionStateClass != state.getClass()) {
                        var progressList = bridge.findField(FindField.create().searchInClass(Arrays.asList(bridge.getClassData(state.getClass()))).matcher(FieldMatcher.create().type(long.class)));
                        if(progressList.isEmpty()) {
                            XposedBridge.log("[SpotifyPlus] Failed to get progress: " + state.getClass().getName());
                            return -1;
                        }
                        field = progressList.get(0).getFieldInstance(lpparam.classLoader);
                        playbackPositionStateClass = state.getClass();
                        playbackPositionField = field;
                    }
                }
            }
            return field.getLong(state);
        } catch(Exception e) {
            XposedBridge.log(e);
        }

        return -1;
    }

    public static SharedPreferences getPreferences() {
        Activity activity = currentActivity;
        if(activity == null) return null;

        return activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
    }

    public static String getString(int resId) {
        if(modResources == null) return null;

        try {
            return modResources.getString(resId);
        } catch(Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }

    public static String getString(int resId, Object... formatArgs) {
        if(modResources == null) return null;

        try {
            return modResources.getString(resId, formatArgs);
        } catch(Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }

    public static String getQuantityString(int resId, int quantity, Object... formatArgs) {
        if (modResources == null) return null;

        try {
            return modResources.getQuantityString(resId, quantity, formatArgs);
        } catch (Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }

    public static SharedPreferences getScriptPreferences(String name, Context activity) {
        if(activity == null) {
            XposedBridge.log("[SpotifyPlus] No activity found");
            return null;
        }

        return activity.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static final List<PlayerStateUpdatedListener> listeners = new ArrayList<>();
    private static final List<TrackStateChangedListener> trackListeners = new ArrayList<>();

    public static void registerPlayerStateListener(PlayerStateUpdatedListener listener) {
        listeners.add(listener);
    }

    public static void unregisterPlayerStateListener(PlayerStateUpdatedListener listener) {
        listeners.remove(listener);
    }

    public static void notifyPlayerStateChanged(Object playerState) {
        for(PlayerStateUpdatedListener listener : listeners) {
            listener.onPlayerStateUpdated(playerState);
        }
    }

    public static void notifyTrackStateChanged(Object track) {
        for(TrackStateChangedListener listener : trackListeners) {
            listener.onTrackStateChanged(track);
        }
    }
}

