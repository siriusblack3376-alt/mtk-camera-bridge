package com.mtkrawbridge.hook;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MTK RAW Bridge - CameraMetadataHook Optimized for LineageOS/AOSP Frameworks
 */
public class CameraMetadataHook implements IXposedHookLoadPackage {

    private static final String TAG = "MTK_RAW_BRIDGE";

    private static final int HAL_PIXEL_FORMAT_RAW16  = 0x20;   // = ImageFormat.RAW_SENSOR = 32
    private static final int HAL_PIXEL_FORMAT_RAW10  = 0x25;   // = ImageFormat.RAW10 = 37
    
    private static final int STREAM_CONFIG_STRIDE = 4;

    private static final int RAW_WIDTH_BIN   = 4624;
    private static final int RAW_HEIGHT_BIN  = 3472;
    private static final int RAW_WIDTH_QBIN  = 2312;
    private static final int RAW_HEIGHT_QBIN = 1736;

    private static final int CAPABILITY_RAW              = 7;   // CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
    private static final int CAPABILITY_MANUAL_SENSOR    = 2;
    private static final int CAPABILITY_BURST_CAPTURE    = 6;
    private static final int HARDWARE_LEVEL_FULL         = 1;   // CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // LineageOS matches
        boolean isSystemServer = lpparam.packageName.equals("android");
        boolean isCameraApp    = lpparam.packageName.contains("camera")
                              || lpparam.packageName.equals("com.google.android.GoogleCamera")
                              || lpparam.packageName.equals("com.android.camera2")
                              || lpparam.packageName.equals("org.lineageos.aperture");

        if (!isSystemServer && !isCameraApp) return;

        XposedBridge.log(TAG + ": Loading clean AOSP hooks in " + lpparam.packageName);

        // Target the low-level metadata parser directly to completely bypass high-level manager discrepancies
        hookCameraMetadataNativeGet(lpparam.classLoader);
    }

    // =========================================================================
    // Hook 2: CameraMetadataNative.get()
    // Intercepts the low-level data extraction stream directly.
    // =========================================================================
    private void hookCameraMetadataNativeGet(ClassLoader cl) {
        try {
            Class<?> nativeClass = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraMetadataNative", cl);

            XposedHelpers.findAndHookMethod(nativeClass,
                    "get",
                    XposedHelpers.findClass("android.hardware.camera2.impl.CameraMetadataNative$Key", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object key = param.args[0];
                            if (key == null) return;

                            String keyName = getKeyName(key);
                            if (keyName == null) return;

                            // Force Hardware Level to FULL directly inside metadata reads
                            if (keyName.contains("android.info.supportedHardwareLevel") 
                                    || keyName.equals("INFO_SUPPORTED_HARDWARE_LEVEL")) {
                                param.setResult(HARDWARE_LEVEL_FULL);
                                return;
                            }

                            // Intercept capabilities array read — dynamically append RAW capabilities
                            if (keyName.contains("android.request.availableCapabilities")
                                    || keyName.equals("REQUEST_AVAILABLE_CAPABILITIES")) {
                                Object result = param.getResult();
                                param.setResult(injectRawCapabilitiesArray(result));
                                return;
                            }

                            // Intercept stream configs array read — inject RAW16/RAW10 resolutions
                            if (keyName.contains("android.scaler.availableStreamConfigurations")
                                    && !keyName.contains("Maximum")) {
                                Object result = param.getResult();
                                param.setResult(injectRawStreamConfigs(result));
                                return;
                            }
                            
                            // Intercept Min Frame Durations for RAW configs
                            if (keyName.contains("android.scaler.availableMinFrameDurations")) {
                                Object result = param.getResult();
                                param.setResult(injectRawDurations(result, 33333333L)); // 30fps
                                return;
                            }

                            // Intercept Stall Durations for RAW configs
                            if (keyName.contains("android.scaler.availableStallDurations")) {
                                Object result = param.getResult();
                                param.setResult(injectRawDurations(result, 0L)); // No stall
                                return;
                            }
                        }
                    });

            XposedBridge.log(TAG + ": Low-level CameraMetadataNative Hook fully active.");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Metadata Hook failed installation: " + t.getMessage());
        }
    }

    // =========================================================================
    // Dynamic Injections Arrays
    // =========================================================================
    private int[] injectRawCapabilitiesArray(Object result) {
        int[] caps;
        if (result instanceof int[]) {
            caps = (int[]) result;
        } else {
            caps = new int[]{0}; // Baseline fallback
        }

        if (!containsValue(caps, CAPABILITY_RAW)) {
            caps = appendInt(caps, CAPABILITY_RAW);
        }
        if (!containsValue(caps, CAPABILITY_MANUAL_SENSOR)) {
            caps = appendInt(caps, CAPABILITY_MANUAL_SENSOR);
        }
        if (!containsValue(caps, CAPABILITY_BURST_CAPTURE)) {
            caps = appendInt(caps, CAPABILITY_BURST_CAPTURE);
        }
        return caps;
    }

    private int[] injectRawStreamConfigs(Object result) {
        int[] existing = (result instanceof int[]) ? (int[]) result : new int[0];

        // Check if already present
        for (int i = 0; i < existing.length; i += STREAM_CONFIG_STRIDE) {
            if (i + STREAM_CONFIG_STRIDE <= existing.length) {
                if (existing[i] == HAL_PIXEL_FORMAT_RAW16) return existing;
            }
        }

        int[] rawEntries = {
            HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0,
            HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 0,
            HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0,
            HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 0,
            HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  1 // Reprocess input
        };

        int[] merged = new int[existing.length + rawEntries.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(rawEntries, 0, merged, existing.length, rawEntries.length);
        return merged;
    }

    private long[] injectRawDurations(Object result, long defaultValue) {
        long[] existing = (result instanceof long[]) ? (long[]) result : new long[0];
        
        // Stride for frame/stall durations array is 4 entries: {format, width, height, duration}
        long[] rawDurations = {
            HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  defaultValue,
            HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, defaultValue,
            HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  defaultValue
        };

        long[] merged = new long[existing.length + rawDurations.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(rawDurations, 0, merged, existing.length, rawDurations.length);
        return merged;
    }

    private String getKeyName(Object key) {
        try {
            Method getNameMethod = key.getClass().getMethod("getName");
            return (String) getNameMethod.invoke(key);
        } catch (Throwable t) {
            try {
                Field nameField = key.getClass().getDeclaredField("mName");
                nameField.setAccessible(true);
                return (String) nameField.get(key);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private boolean containsValue(int[] arr, int val) {
        if (arr == null) return false;
        for (int v : arr) if (v == val) return true;
        return false;
    }

    private int[] appendInt(int[] arr, int val) {
        int[] newArr = Arrays.copyOf(arr, arr.length + 1);
        newArr[arr.length] = val;
        return newArr;
    }
}
