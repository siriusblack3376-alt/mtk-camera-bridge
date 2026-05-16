package com.mtkrawbridge.hook;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MTK RAW Bridge - Container-level Hooking for Android 16/LineageOS
 * Intercepts public object queries instead of low-level primitive streams.
 */
public class CameraMetadataHook implements IXposedHookLoadPackage {

    private static final String TAG = "MTK_RAW_BRIDGE";

    private static final int HAL_PIXEL_FORMAT_RAW16  = 0x20; 
    private static final int HAL_PIXEL_FORMAT_RAW10  = 0x25; 
    private static final int STREAM_CONFIG_STRIDE    = 4;

    private static final int RAW_WIDTH_BIN   = 4624;
    private static final int RAW_HEIGHT_BIN  = 3472;
    private static final int RAW_WIDTH_QBIN  = 2312;
    private static final int RAW_HEIGHT_QBIN = 1736;

    private static final int CAPABILITY_RAW              = 7; 
    private static final int CAPABILITY_MANUAL_SENSOR    = 2;
    private static final int CAPABILITY_BURST_CAPTURE    = 6;
    private static final int HARDWARE_LEVEL_FULL         = 1; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("android") && 
            !lpparam.packageName.contains("camera") && 
            !lpparam.packageName.equals("com.android.MGC")) return;

        XposedBridge.log(TAG + ": Initializing container-level interceptor in " + lpparam.packageName);

        hookCameraCharacteristicsGet(lpparam.classLoader);
    }

    private void hookCameraCharacteristicsGet(ClassLoader cl) {
        try {
            Class<?> charClass = XposedHelpers.findClass("android.hardware.camera2.CameraCharacteristics", cl);

            XposedHelpers.findAndHookMethod(charClass, "get", CameraCharacteristics.Key.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    CameraCharacteristics.Key<?> key = (CameraCharacteristics.Key<?>) param.args[0];
                    if (key == null) return;

                    String name = key.getName();

                    // 1. Intercept Hardware Level Request
                    if (name.equals(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL.getName())) {
                        param.setResult(HARDWARE_LEVEL_FULL);
                        return;
                    }

                    // 2. Intercept Capabilities Request
                    if (name.equals(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES.getName())) {
                        int[] original = (int[]) param.getResult();
                        if (original == null) original = new int[]{0};
                        
                        if (!containsValue(original, CAPABILITY_RAW)) {
                            original = appendInt(original, CAPABILITY_RAW);
                            original = appendInt(original, CAPABILITY_MANUAL_SENSOR);
                            original = appendInt(original, CAPABILITY_BURST_CAPTURE);
                        }
                        param.setResult(original);
                        return;
                    }

                    // 3. Intercept Configuration Map Request
                    if (name.equals(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.getName())) {
                        StreamConfigurationMap originalMap = (StreamConfigurationMap) param.getResult();
                        if (originalMap != null) {
                            // Check if RAW is already present via public APIs
                            int[] formats = originalMap.getOutputFormats();
                            if (formats != null) {
                                for (int f : formats) {
                                    if (f == ImageFormat.RAW_SENSOR) return; // Already handles RAW, don't double inject
                                }
                            }
                        }

                        // Inject resolutions via alternative container generation if supported
                        param.setResult(originalMap);
                    }
                }
            });

            XposedBridge.log(TAG + ": Container-level Key interceptor active.");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Container hook installation skipped: " + t.getMessage());
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
