package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Zalo's local-DB backup ({@code BackupRestorableDbWorker}) is scheduled by a WorkManager
 * PeriodicWorkRequest built with a fixed 24-hour interval (confirmed by decompiling the live
 * APK: {@code new PeriodicWorkRequest.Builder(BackupRestorableDbWorker.class, 24, HOURS)}). This
 * hooks that builder's constructor and, only for that specific worker class, substitutes a
 * shorter interval, so a device crash or DB corruption can't lose more than a few hours of
 * messages instead of up to a day.
 */
public final class BackupFrequencyFeature extends Feature {
    private static final String FEATURE = "messages.backup_frequency";
    private static final String WORKER_CLASS_FALLBACK =
            "com.zing.zalo.db.backup.BackupRestorableDbWorker";
    private static final String BUILDER_CLASS_FALLBACK = "c7.l0";
    private static final long NEW_INTERVAL_HOURS = 4L;

    public BackupFrequencyFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "BackupFrequency";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_INCREASE_BACKUP_FREQUENCY)) {
            SelfCheckRegistry.markDisabled(FEATURE, "backup sync interval");
            return;
        }
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
        String workerClassName = schema.string("symbols.chat.backup_worker_class", WORKER_CLASS_FALLBACK);
        String builderClassName = schema.string("symbols.chat.backup_periodic_builder_class",
                BUILDER_CLASS_FALLBACK);

        Class<?> workerClass = XposedHelpers.findClassIfExists(workerClassName, classLoader);
        Class<?> builderClass = XposedHelpers.findClassIfExists(builderClassName, classLoader);
        if (workerClass == null || builderClass == null) {
            SelfCheckRegistry.markStale(FEATURE, "symbols.chat",
                    "backup worker/builder class missing; run chat rendering trace first");
            return;
        }

        Constructor<?> constructor = findConstructor(builderClass);
        if (constructor == null) {
            SelfCheckRegistry.markStale(FEATURE, "symbols.chat",
                    "periodic builder constructor(Class,long,TimeUnit) not found on " + builderClassName);
            return;
        }

        try {
            constructor.setAccessible(true);
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!workerClass.equals(param.args[0])) {
                        return;
                    }
                    param.args[1] = NEW_INTERVAL_HOURS;
                    param.args[2] = TimeUnit.HOURS;
                    SelfCheckRegistry.markSuppressed(FEATURE, workerClassName,
                            "rescheduled to every " + NEW_INTERVAL_HOURS + "h (was 24h)");
                }
            });
            SelfCheckRegistry.markInstalled(FEATURE, builderClassName + "#<init>", 1);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, builderClassName + "#<init>", throwable);
        }
    }

    private static Constructor<?> findConstructor(Class<?> builderClass) {
        for (Constructor<?> candidate : builderClass.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 3 && Class.class.equals(params[0])
                    && long.class.equals(params[1]) && TimeUnit.class.equals(params[2])) {
                return candidate;
            }
        }
        return null;
    }
}
