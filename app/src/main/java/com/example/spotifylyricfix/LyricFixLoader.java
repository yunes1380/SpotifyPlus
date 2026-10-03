package com.example.spotifylyricfix;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.List;

public class LyricFixLoader implements IXposedHookLoadPackage {
    private static final String TAG = "[LyricFix]";

    static class ResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                Activity activity = (Activity) param.thisObject;
                String name = activity.getClass().getName();
                String lower = name.toLowerCase();
                XposedBridge.log(TAG + " activity resumed: " + name);
                if ("com.spotify.nowplaying.musicinstallation.NowPlayingActivity".equals(name)
                        || lower.contains("nowplaying") || lower.contains("npv")
                        || lower.contains("player")) {
                    SmallLyric.onNpvResumed(activity);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " onResume hook error: " + t);
            }
        }
    }

    static class PauseHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                Activity activity = (Activity) param.thisObject;
                SmallLyric.onNpvPaused(activity);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " onPause hook error: " + t);
            }
        }
    }

    static class MetadataHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                android.media.MediaMetadata metadata =
                        (android.media.MediaMetadata) param.args[0];
                if (metadata == null) return;
                String uri = metadata.getString(
                        android.media.MediaMetadata.METADATA_KEY_MEDIA_ID);
                CharSequence title = metadata.getText(
                        android.media.MediaMetadata.METADATA_KEY_TITLE);
                CharSequence artist = metadata.getText(
                        android.media.MediaMetadata.METADATA_KEY_ARTIST);
                SmallLyric.onMetadata(uri,
                        title == null ? "" : title.toString(),
                        artist == null ? "" : artist.toString());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " metadata hook error: " + t);
            }
        }
    }

    static class PlayStateHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                android.media.session.PlaybackState state =
                        (android.media.session.PlaybackState) param.args[0];
                if (state == null) return;
                int st = state.getState();
                boolean playing = st == android.media.session.PlaybackState.STATE_PLAYING
                        || st == android.media.session.PlaybackState.STATE_FAST_FORWARDING
                        || st == android.media.session.PlaybackState.STATE_REWINDING
                        || st == android.media.session.PlaybackState.STATE_BUFFERING;
                SmallLyric.onState(state.getPosition(), state.getPlaybackSpeed(),
                        playing, state.getLastPositionUpdateTime());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " playstate hook error: " + t);
            }
        }
    }

    static class PlaybackListener extends AudioManager.AudioPlaybackCallback {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            try {
                XposedBridge.log(TAG + " playback configs=" + configs.size());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " playback log error: " + t);
            }
        }
    }

    static class AttachHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Context context = (Context) param.args[0];
            XposedBridge.log(TAG + " Spotify attach, pkg=" + context.getPackageName());
            try {
                SharedPreferences prefs = context.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
                XposedBridge.log(TAG + " SpotifyPlus prefs keys=" + prefs.getAll().keySet());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " prefs check failed: " + t);
            }
            try {
                AudioManager audioManager =
                        (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    audioManager.registerAudioPlaybackCallback(new PlaybackListener(),
                            new Handler(Looper.getMainLooper()));
                    XposedBridge.log(TAG + " audio playback callback registered");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " audio callback failed: " + t);
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        boolean isSpotify = "com.spotify.music".equals(lpparam.packageName);
        boolean isSamsung = "com.sec.android.app.music".equals(lpparam.packageName);
        if (!isSpotify && !isSamsung) {
            return;
        }
        SmallLyric.setPkg(isSamsung ? "samsung" : "spotify");
        XposedBridge.log(TAG + " loaded in " + lpparam.packageName + ", process=" + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new ResumeHook());
            XposedBridge.log(TAG + " Activity.onResume hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to hook onResume: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new AttachHook());
            XposedBridge.log(TAG + " Application.attach hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to hook attach: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new PauseHook());
            XposedHelpers.findAndHookMethod(Activity.class, "onStop", new PauseHook());
            XposedBridge.log(TAG + " Activity pause/stop hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to hook pause: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.media.session.MediaSession",
                    lpparam.classLoader, "setMetadata", android.media.MediaMetadata.class,
                    new MetadataHook());
            XposedHelpers.findAndHookMethod("android.media.session.MediaSession",
                    lpparam.classLoader, "setPlaybackState",
                    android.media.session.PlaybackState.class, new PlayStateHook());
            XposedBridge.log(TAG + " MediaSession hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to hook MediaSession: " + t);
        }
    }
}
