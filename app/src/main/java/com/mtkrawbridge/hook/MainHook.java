package com.mtkrawbridge.hook;

import android.util.Log;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MTK RAW Bridge - Main LSPosed Entry Point
 *
 * Registered in assets/xposed_init
 * Scope: System Framework (android) + Camera apps
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "MTK_RAW_BRIDGE";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // Called very early, before any package is loaded
        // Good place for global state init
        XposedBridge.log(TAG + ": initZygote called - MTK RAW Bridge v1.0 loading");
        XposedBridge.log(TAG + ": Module path: " + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + ": handleLoadPackage: " + lpparam.packageName);

        // Delegate to the camera metadata hook
        new CameraMetadataHook().handleLoadPackage(lpparam);

        // Delegate to the vendor tag bridge hook
        new VendorTagBridgeHook().handleLoadPackage(lpparam);
    }
}
