package com.mtkrawbridge.hook;

import android.hardware.camera2.CameraCharacteristics;
import android.graphics.ImageFormat;
import android.util.Log;

import java.util.Arrays;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MTK RAW Bridge - CameraMetadataHook
 * Low-level native setup tailored for LineageOS / Android 16 Marshaling Bypasses.
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
        boolean isSystemServer = lpparam.packageName.equals("android");
        boolean isCameraApp    = lpparam.packageName.contains("camera")
                              || lpparam.packageName.equals("com.google.android.GoogleCamera")
                              || lpparam.packageName.equals("com.android.camera2")
                              || lpparam.packageName.equals("org.lineageos.aperture")
                              || lpparam.packageName.equals("com.android.MGC");

        if (!isSystemServer && !isCameraApp) return;

        XposedBridge.log(TAG + ": Injecting framework marshaling bypass into: " + lpparam.packageName);

        hookBaseMetadataArrayParser(lpparam.classLoader);
    }

    /**
     * Targets the direct primitive arrays returned straight from native memory layers
     * before type conversion/marshaling can reject them.
     */
    private void hookBaseMetadataArrayParser(ClassLoader cl) {
        try {
            Class<?> nativeClass = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraMetadataNative", cl);

            // Hook the underlying getBaseValues method which fetches pure primitive arrays
            XposedHelpers.findAndHookMethod(nativeClass,
                    "getBaseValues",
                    int.class, // Tag ID identifier
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            int tagId = (int) param.args[0];

                            // 1. Tag ID for android.info.supportedHardwareLevel
                            if (tagId == 0x10003 || tagId == 65539) {
                                byte[] forcedLevel = new byte[]{(byte) HARDWARE_LEVEL_FULL};
                                param.setResult(forcedLevel);
                                return;
                            }

                            // 2. Tag ID for android.request.availableCapabilities
                            if (tagId == 0x30002 || tagId == 196610) {
                                Object originalResult = param.getResult();
                                if (originalResult instanceof int[]) {
                                    int[] caps = (int[]) originalResult;
                                    if (!containsValue(caps, CAPABILITY_RAW)) caps = appendInt(caps, CAPABILITY_RAW);
                                    if (!containsValue(caps, CAPABILITY_MANUAL_SENSOR)) caps = appendInt(caps, CAPABILITY_MANUAL_SENSOR);
                                    if (!containsValue(caps, CAPABILITY_BURST_CAPTURE)) caps = appendInt(caps, CAPABILITY_BURST_CAPTURE);
                                    param.setResult(caps);
                                }
                                return;
                            }

                            // 3. Tag ID for android.scaler.availableStreamConfigurations
                            if (tagId == 0x4000a || tagId == 262154) {
                                Object originalResult = param.getResult();
                                int[] existing = (originalResult instanceof int[]) ? (int[]) originalResult : new int[0];
                                
                                boolean alreadyHasRaw = false;
                                for (int i = 0; i < existing.length; i += STREAM_CONFIG_STRIDE) {
                                    if (i < existing.length && existing[i] == HAL_PIXEL_FORMAT_RAW16) {
                                        alreadyHasRaw = true;
                                        break;
                                    }
                                }

                                if (!alreadyHasRaw) {
                                    int[] rawEntries = {
                                        HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0,
                                        HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 0,
                                        HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0,
                                        HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 0,
                                        HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  1
                                    };
                                    int[] merged = new int[existing.length + rawEntries.length];
                                    System.arraycopy(existing, 0, merged, 0, existing.length);
                                    System.arraycopy(rawEntries, 0, merged, existing.length, rawEntries.length);
                                    param.setResult(merged);
                                }
                                return;
                            }

                            // 4. Tag ID for android.scaler.availableMinFrameDurations
                            if (tagId == 0x4000b || tagId == 262155) {
                                Object originalResult = param.getResult();
                                long[] existing = (originalResult instanceof long[]) ? (long[]) originalResult : new long[0];
                                long[] rawDurations = {
                                    HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  33333333L,
                                    HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 33333333L,
                                    HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  33333333L
                                };
                                long[] merged = new long[existing.length + rawDurations.length];
                                System.arraycopy(existing, 0, merged, 0, existing.length);
                                System.arraycopy(rawDurations, 0, merged, existing.length, rawDurations.length);
                                param.setResult(merged);
                                return;
                            }

                            // 5. Tag ID for android.scaler.availableStallDurations
                            if (tagId == 0x4000c || tagId == 262156) {
                                Object originalResult = param.getResult();
                                long[] existing = (originalResult instanceof long[]) ? (long[]) originalResult : new long[0];
                                long[] stallDurations = {
                                    HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0L,
                                    HAL_PIXEL_FORMAT_RAW16, RAW_WIDTH_QBIN, RAW_HEIGHT_QBIN, 0L,
                                    HAL_PIXEL_FORMAT_RAW10, RAW_WIDTH_BIN,  RAW_HEIGHT_BIN,  0L
                                };
                                long[] merged = new long[existing.length + stallDurations.length];
                                System.arraycopy(existing, 0, merged, 0, existing.length);
                                System.arraycopy(stallDurations, 0, merged, existing.length, stallDurations.length);
                                param.setResult(merged);
                                return;
                            }
                        }
                    });

            XposedBridge.log(TAG + ": Deep JNI-level array interception fully established.");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Array interception failure fallback: " + t.getMessage());
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
