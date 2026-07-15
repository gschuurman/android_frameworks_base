/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.policy;

import android.util.Slog;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * VIM3 head unit power-button / touch-wake behaviour.
 *
 * <p>The onboard power button (gpio-keys-polled) and an injected
 * {@code input keyevent 26} both arrive as KEYCODE_POWER, but the injected one is
 * flagged as such. The injected keyevent is used for a burn-in screen-off where
 * tap-to-wake must keep working, while a physical power-button press should be a
 * "deep off" in which the touchscreen is inhibited so a tap cannot wake it and
 * only the button wakes it again.
 *
 * <p>All of the behaviour lives here so that {@link PhoneWindowManager} only needs a
 * few one-line hooks, keeping the merge-conflict surface against AOSP/LineageOS
 * upstream as small as possible.
 *
 * <p>The touchscreen is disabled via the kernel input "inhibited" attribute, looked
 * up by device name (the inputN index is not stable across boots). A ueventd rule
 * in the device tree makes that node writable by the system uid.
 */
class Vim3TouchWakePolicy {
    private static final String TAG = "Vim3TouchWakePolicy";

    /** Name prefix of the head-unit touchscreen input device. */
    private static final String TOUCH_NAME_PREFIX = "WaveShare";

    private volatile boolean mLastPowerKeyInjected;
    private volatile boolean mDeepOff;
    private String mInhibitPath;

    /**
     * Hook for KEYCODE_POWER ACTION_DOWN. Records whether the press was injected so the
     * sleep path can decide whether to inhibit touch, and while in a physical-button
     * deep-off reports that injected power should be ignored (button-only wake).
     *
     * @param isInjected whether the power key was injected (e.g. {@code input keyevent 26})
     * @return {@code true} if the caller should swallow the event (don't wake)
     */
    boolean onPowerKeyDown(boolean isInjected) {
        mLastPowerKeyInjected = isInjected;
        return mDeepOff && isInjected;
    }

    /** Hook for when the power button is putting the default display to sleep. */
    void onSleepFromPowerButton() {
        if (!mLastPowerKeyInjected) {
            // Physical button: deep off, disable tap-to-wake.
            setTouchInhibited(true);
            mDeepOff = true;
        } else {
            // Injected keyevent 26 (burn-in): leave touch active so a tap still wakes.
            setTouchInhibited(false);
            mDeepOff = false;
        }
    }

    /** Hook for any genuine wake; restores the touchscreen and clears the deep-off state. */
    void onWokeUp() {
        if (mDeepOff) {
            setTouchInhibited(false);
            mDeepOff = false;
        }
    }

    private void setTouchInhibited(boolean inhibit) {
        try {
            if (mInhibitPath == null) {
                File[] inputs = new File("/sys/class/input").listFiles();
                if (inputs != null) {
                    for (File in : inputs) {
                        File nameFile = new File(in, "name");
                        File inhFile = new File(in, "inhibited");
                        if (!nameFile.exists() || !inhFile.exists()) {
                            continue;
                        }
                        String name = "";
                        try (FileReader fr = new FileReader(nameFile)) {
                            char[] buf = new char[64];
                            int n = fr.read(buf);
                            if (n > 0) {
                                name = new String(buf, 0, n).trim();
                            }
                        }
                        if (name.startsWith(TOUCH_NAME_PREFIX)) {
                            mInhibitPath = inhFile.getAbsolutePath();
                            break;
                        }
                    }
                }
            }
            if (mInhibitPath != null) {
                try (FileWriter w = new FileWriter(mInhibitPath)) {
                    w.write(inhibit ? "1" : "0");
                }
                Slog.i(TAG, "touchscreen " + (inhibit ? "inhibited" : "enabled"));
            } else {
                Slog.w(TAG, "touch inhibit sysfs node not found");
            }
        } catch (Exception e) {
            Slog.w(TAG, "failed to set touch inhibited=" + inhibit, e);
        }
    }
}
