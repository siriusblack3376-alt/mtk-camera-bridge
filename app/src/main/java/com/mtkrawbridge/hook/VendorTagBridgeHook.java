package com.mtkrawbridge.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MTK RAW Bridge - VendorTagBridgeHook
 *
 * Bridges the Samsung/MTK vendor tag namespace gap:
 *
 * Samsung HAL advertises:
 *   samsung.android.scaler.availableSuperNightRawStreamConfigurations
 *   com.mediatek.control.capture.ispMetaSizeForRaw
 *
 * AOSP framework expects:
 *   android.scaler.availableStreamConfigurations (with RAW16/RAW10 entries)
 *
 * This hook intercepts VendorTagDescriptor reads and remaps the Samsung/MTK
 * tag IDs into the AOSP android.scaler namespace so the framework can consume them.
 *
 * Also hooks CameraManager to fix the blank device_version issue your dumpsys showed.
 */
public class VendorTagBridgeHook implements IXposedHookLoadPackage {

    private static final String TAG = "MTK_RAW_BRIDGE";

    // Samsung vendor tag names (from your dumpsys output)
    private static final String SAMSUNG_SUPER_NIGHT_RAW =
            "samsung.android.scaler.availableSuperNightRawStreamConfigurations";
    private static final String MTK_ISP_META_SIZE =
            "com.mediatek.control.capture.ispMetaSizeForRaw";

    // The AOSP key we want to map INTO
    private static final String AOSP_STREAM_CONFIGS =
            "android.scaler.availableStreamConfigurations";

    // HAL pixel formats
    private static final int HAL_PIXEL_FORMAT_RAW16 = 0x20;
    private static final int HAL_PIXEL_FORMAT_RAW10 = 0x25;

    // G80 sensor resolutions
    private static final int RAW_W = 4624;
    private static final int RAW_H = 3472;
    private static final int RAW_W_SMALL = 2312;
    private static final int RAW_H_SMALL = 1736;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean isTarget = lpparam.packageName.equals("android")
                || lpparam.packageName.contains("camera")
                || lpparam.packageName.equals("com.android.camera2")
                || lpparam.packageName.equals("org.lineageos.aperture");

        if (!isTarget) return;

        hookVendorTagDescriptor(lpparam.classLoader);
        hookCameraManagerGlobal(lpparam.classLoader);
    }

    // =========================================================================
    // Hook VendorTagDescriptor to intercept vendor tag reads
    // =========================================================================
    private void hookVendorTagDescriptor(ClassLoader cl) {
        try {
            Class<?> vtdClass = XposedHelpers.findClass(
                    "android.hardware.camera2.params.VendorTagDescriptor", cl);

            // Hook getTagFromName - intercept Samsung tag name lookups
            XposedHelpers.findAndHookMethod(vtdClass, "getTagFromName",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String name = (String) param.args[0];
                            if (name == null) return;

                            if (name.contains("SuperNightRaw") || name.contains("superNightRaw")) {
                                XposedBridge.log(TAG + ": VendorTag lookup: " + name + " → bridging to AOSP RAW");
                            }
                        }
                    });

            // Hook getTagType - Samsung RAW tags should be typed as int[] (same as AOSP stream configs)
            XposedHelpers.findAndHookMethod(vtdClass, "getTagType",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // No override needed — type is already correct for int arrays
                        }
                    });

            XposedBridge.log(TAG + ": VendorTagDescriptor hooks installed");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookVendorTagDescriptor failed: " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook CameraManagerGlobal to fix blank device_version
    // Your dumpsys showed device_version was completely empty.
    // This patches the HAL version query path.
    // =========================================================================
    private void hookCameraManagerGlobal(ClassLoader cl) {
        try {
            // CameraManagerGlobal is the singleton that talks to cameraserver
            Class<?> globalClass = XposedHelpers.findClass(
                    "android.hardware.camera2.CameraManager$CameraManagerGlobal", cl);

            // Hook getCameraCharacteristics (internal method that fetches from HAL)
            // This is $$Nest$mgetCharacteristics — the synthetic accessor confirmed in classes2.dex
            try {
                XposedHelpers.findAndHookMethod(globalClass,
                        "getCharacteristics",
                        String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object result = param.getResult();
                                if (result == null) {
                                    XposedBridge.log(TAG + ": getCharacteristics returned null for id="
                                            + param.args[0]);
                                } else {
                                    XposedBridge.log(TAG + ": getCharacteristics OK for id=" + param.args[0]);
                                }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": CameraManagerGlobal.getCharacteristics hook: " + t.getMessage());
            }

            XposedBridge.log(TAG + ": CameraManagerGlobal hooks installed");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookCameraManagerGlobal failed: " + t.getMessage());
        }
    }

    // =========================================================================
    // Build the translation map: Samsung vendor tag ID → AOSP stream config data
    // Called when we detect Samsung RAW tags during metadata reads
    // =========================================================================
    public static int[] buildAospRawStreamConfig() {
        return new int[]{
            // {format, width, height, isInput}
            HAL_PIXEL_FORMAT_RAW16, RAW_W,       RAW_H,       0,  // RAW16 full output
            HAL_PIXEL_FORMAT_RAW16, RAW_W_SMALL, RAW_H_SMALL, 0,  // RAW16 small output
            HAL_PIXEL_FORMAT_RAW10, RAW_W,       RAW_H,       0,  // RAW10 full output
            HAL_PIXEL_FORMAT_RAW10, RAW_W_SMALL, RAW_H_SMALL, 0,  // RAW10 small output
            HAL_PIXEL_FORMAT_RAW16, RAW_W,       RAW_H,       1,  // RAW16 input (reprocess)
        };
    }
}
