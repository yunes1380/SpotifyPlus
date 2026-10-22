package com.lenerd46.spotifyplus.hooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.UsingFieldMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;

public class PrivateSessionHook extends SpotifyHook {
    private static final long REFRESH_DELAY = 5L * 60L * 60L * 1000L + 45L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private Object privateSessionKey;
    private Object settingsRepository;
    private Runnable refreshPrivateSession;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    public PrivateSessionHook(Context context) {
        prefs = context.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
    }

    @Override
    protected void hook() {
        hookSharedGuards();
        try {
            hookLegacyRxRepository();
            XposedBridge.log("[SpotifyPlus][PrivateSessionHook] Legacy RxSettings repository path installed");
        } catch (Throwable legacyError) {
            try {
                hookEsperantoPrefsRepository();
                XposedBridge.log("[SpotifyPlus][PrivateSessionHook] Esperanto prefs repository path installed (9.1.92+)");
            } catch (Throwable esperantoError) {
                XposedBridge.log("[SpotifyPlus][PrivateSessionHook] No settings repository path available");
                XposedBridge.log(legacyError);
                XposedBridge.log(esperantoError);
            }
        }
    }

    /**
     * Hooks independent of which settings repository an app version uses:
     * forcing the proto getter to report true and re-arming after the
     * automatic incognito session ends.
     */
    private void hookSharedGuards() {
        try {
            Class<?> settingsStateClass = XposedHelpers.findClass("com.spotify.settings.esperanto.proto.SettingsOuterClass$SettingsState", lpparm.classLoader);
            Method checkPrivateSessionMethod = bridge.findMethod(FindMethod.create().searchInClass(Collections.singletonList(bridge.getClassData(settingsStateClass))).matcher(MethodMatcher.create()
                    .returnType(boolean.class)
                    .paramCount(0)
                    .usingFields(Collections.singletonList(UsingFieldMatcher.create().field(FieldMatcher.create().declaredClass(settingsStateClass).name("privateSession_").type(boolean.class)).usingType(UsingType.Read)))
            )).single().getMethodInstance(lpparm.classLoader);

            XposedBridge.hookMethod(checkPrivateSessionMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!prefs.getBoolean("private_session", false)) return;

                    if (Boolean.FALSE.equals(param.getResult()) && refreshPrivateSession != null) {
                        handler.removeCallbacks(refreshPrivateSession);
                        handler.postDelayed(refreshPrivateSession, 750L);
                    }
                    param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Spotify <= 9.1.88: typed settings-key registry plus Completable(RxSessionKey, Object). */
    private void hookLegacyRxRepository() throws Throwable {
        var settingKeyClasses = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().interfaceCount(0).fieldCount(4, 64).addField(FieldMatcher.create().modifiers(Modifier.STATIC)).addMethod(MethodMatcher.create().name("<clinit>").usingStrings("offline_mode", "play_explicit_content", "private_session", "download_over_3g"))));
        if (settingKeyClasses.size() != 1) throw new IllegalStateException("[PrivateSessionHook/DexKit] Expected one static settings-key registry but found " + settingKeyClasses);
        Class<?> settingKeysClass = settingKeyClasses.get(0).getInstance(lpparm.classLoader);

        privateSessionKey = findSettingKey(settingKeysClass, "private_session");
        // 9.1.82 moved the RPC strings into a shared ClientBase implementation.
        // Identify the settings repository by its router and typed mutation API.
        var setters = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .returnType("io.reactivex.rxjava3.core.Completable")
                .paramTypes((String) null, Object.class.getName())
                .declaredClass(ClassMatcher.create().addFieldForType("com.spotify.cosmos.rxrouter.RxRouter"))));
        java.util.List<Method> settingsMethods = new java.util.ArrayList<>();
        for (var setter : setters) {
            Method method = setter.getMethodInstance(lpparm.classLoader);
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterTypes()[0].isInstance(privateSessionKey)) settingsMethods.add(method);
        }
        if (settingsMethods.size() != 1) throw new IllegalStateException("Expected one settings mutation API, found " + settingsMethods);
        Method settingsMethod = settingsMethods.get(0);
        Class<?> settingsClass = settingsMethod.getDeclaringClass();

        Method onIncognitoModeDisabledByTimerMethod = hookRearmAfterIncognitoTimer();

        refreshPrivateSession = () -> {
            handler.removeCallbacks(refreshPrivateSession);

            if (!prefs.getBoolean("private_session", false) || settingsRepository == null || privateSessionKey == null) {
                return;
            }

            try {
                Object update = settingsMethod.invoke(settingsRepository, privateSessionKey, Boolean.TRUE);
                XposedHelpers.callMethod(update, "subscribe");

                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
            } catch (Throwable t) {
                XposedBridge.log(t);
                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, 60_000L);
            }
        };

        registerPreferenceListener();

        XposedBridge.hookAllConstructors(settingsClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                settingsRepository = param.thisObject;
                handler.post(refreshPrivateSession);
            }
        });

        XposedBridge.hookMethod(settingsMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!prefs.getBoolean("private_session", false) || param.args[0] != privateSessionKey || !Boolean.FALSE.equals(param.args[1])) {
                    return;
                }

                param.args[1] = Boolean.TRUE;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!prefs.getBoolean("private_session", false) || param.args[0] != privateSessionKey || !Boolean.TRUE.equals(param.args[1])) {
                    return;
                }

                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
            }
        });

        hookRearmAfterIncognitoTimerMethod(onIncognitoModeDisabledByTimerMethod);
    }

    /**
     * Spotify 9.1.92+: the typed settings-key registry is gone (its strings now
     * live in a generated protobuf descriptor class) and values moved to the
     * esperanto prefs API: a repository exposing
     * Completable(String, EsPrefs$Value) that forwards SetParams to a shared
     * ClientBase over the "spotify.prefs.esperanto.proto.Prefs" route with the
     * Set method. Setting keys are plain strings and values are protobuf
     * one-ofs where the boolean branch is value case 2.
     */
    private void hookEsperantoPrefsRepository() throws Throwable {
        Method settingsMethod = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .returnType("io.reactivex.rxjava3.core.Completable")
                .paramTypes("java.lang.String", "com.spotify.prefs.esperanto.proto.EsPrefs$Value")
                .usingStrings("spotify.prefs.esperanto.proto.Prefs", "Set")
        )).single().getMethodInstance(lpparm.classLoader);
        Class<?> settingsClass = settingsMethod.getDeclaringClass();
        Class<?> valueClass = XposedHelpers.findClass("com.spotify.prefs.esperanto.proto.EsPrefs$Value", lpparm.classLoader);

        Method onIncognitoModeDisabledByTimerMethod = hookRearmAfterIncognitoTimer();

        refreshPrivateSession = () -> {
            handler.removeCallbacks(refreshPrivateSession);

            if (!prefs.getBoolean("private_session", false) || settingsRepository == null) {
                return;
            }

            try {
                Object update = settingsMethod.invoke(settingsRepository, "private_session", buildBooleanValue(valueClass, true));
                XposedHelpers.callMethod(update, "subscribe");

                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
            } catch (Throwable t) {
                XposedBridge.log(t);
                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, 60_000L);
            }
        };

        registerPreferenceListener();

        XposedBridge.hookAllConstructors(settingsClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                settingsRepository = param.thisObject;
                handler.post(refreshPrivateSession);
            }
        });

        XposedBridge.hookMethod(settingsMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!prefs.getBoolean("private_session", false) || !"private_session".equals(param.args[0])) {
                    return;
                }

                boolean currentlyTrue;
                try {
                    currentlyTrue = (boolean) XposedHelpers.callMethod(param.args[1], "n");
                } catch (Throwable t) {
                    // Getter renamed: assume any write for our key while the
                    // mod is enabled should be a true value.
                    currentlyTrue = false;
                }
                if (!currentlyTrue) param.args[1] = buildBooleanValue(valueClass, true);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!prefs.getBoolean("private_session", false) || !"private_session".equals(param.args[0])) {
                    return;
                }

                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
            }
        });

        hookRearmAfterIncognitoTimerMethod(onIncognitoModeDisabledByTimerMethod);
    }

    private void registerPreferenceListener() {
        preferenceListener = (sharedPreferences, key) -> {
            if (!"private_session".equals(key)) return;

            handler.removeCallbacks(refreshPrivateSession);
            if (sharedPreferences.getBoolean(key, false)) {
                handler.post(refreshPrivateSession);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    private Method hookRearmAfterIncognitoTimer() throws Throwable {
        Method method = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .name("onIncognitoModeDisabledByTimer")
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType(void.class)
                .paramCount(0)
        )).single().getMethodInstance(lpparm.classLoader);
        return method;
    }

    private void hookRearmAfterIncognitoTimerMethod(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!prefs.getBoolean("private_session", false)) return;

                handler.removeCallbacks(refreshPrivateSession);
                handler.postDelayed(refreshPrivateSession, 250L);
            }
        });
    }

    /**
     * Builds a one-of EsPrefs$Value carrying the boolean branch:
     * static factory -> protobuf builder, boolean setter, build(). Resolved by
     * scanning signatures so R8 renames do not break it.
     */
    private Object buildBooleanValue(Class<?> valueClass, boolean value) throws Throwable {
        for (Method factory : valueClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 0) continue;
            Class<?> builder = factory.getReturnType();
            if (builder == valueClass || builder.isInterface()) continue;

            Object builderInstance;
            try {
                builderInstance = factory.invoke(null);
            } catch (Throwable ignored) {
                continue;
            }

            for (Method boolSetter : builder.getDeclaredMethods()) {
                Class<?>[] params = boolSetter.getParameterTypes();
                if (params.length != 1 || params[0] != boolean.class || boolSetter.getReturnType() != void.class) continue;

                Method finish = null;
                for (Method buildMethod : builder.getDeclaredMethods()) {
                    if (buildMethod.getParameterCount() == 0 && buildMethod.getName().equals("build")) {
                        finish = buildMethod;
                        break;
                    }
                }
                if (finish == null) continue;

                try {
                    boolSetter.invoke(builderInstance, value);
                    Object built = finish.invoke(builderInstance);
                    if (valueClass.isInstance(built)) return built;
                } catch (Throwable ignored) {
                }
            }
        }
        throw new IllegalStateException("Could not build a boolean " + valueClass.getSimpleName() + " value");
    }

    private Object findSettingKey(Class<?> settingKeysClass, String keyName) throws Throwable {
        for (java.lang.reflect.Field staticField : settingKeysClass.getDeclaredFields()) {
            if (!Modifier.isStatic(staticField.getModifiers())) continue;
            staticField.setAccessible(true);
            Object candidate = staticField.get(null);
            if (candidate == null) continue;
            for (java.lang.reflect.Field field : candidate.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                field.setAccessible(true);
                if (keyName.equals(field.get(candidate))) return candidate;
            }
        }
        throw new IllegalStateException("Could not identify the " + keyName + " setting key");
    }
}
