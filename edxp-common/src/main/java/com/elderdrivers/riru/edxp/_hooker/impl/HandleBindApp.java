package com.elderdrivers.riru.edxp._hooker.impl;

import android.app.ActivityThread;
import android.app.ContextImpl;
import android.app.LoadedApk;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.XResources;

import com.elderdrivers.riru.edxp.config.ConfigManager;
import com.elderdrivers.riru.edxp.util.Hookers;
import com.elderdrivers.riru.edxp.util.Utils;
import com.jaredrummler.apkparser.ApkParser;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;

// normal process initialization (for new Activity, Service, BroadcastReceiver etc.)
public class HandleBindApp extends XC_MethodHook {

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Hookers.logD("ActivityThread#handleBindApplication() starts");
            ActivityThread activityThread = (ActivityThread) param.thisObject;
            Object bindData = param.args[0];
            final ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(bindData, "appInfo");
            // save app process name here for later use
            ConfigManager.appProcessName = (String) XposedHelpers.getObjectField(bindData, "processName");
            String reportedPackageName = appInfo.packageName.equals("android") ? "system" : appInfo.packageName;
            Utils.logD("processName=" + ConfigManager.appProcessName +
                    ", packageName=" + reportedPackageName + ", appDataDir=" + ConfigManager.appDataDir);

            ComponentName instrumentationName = (ComponentName) XposedHelpers.getObjectField(bindData, "instrumentationName");
            if (instrumentationName != null) {
                Hookers.logD("Instrumentation detected, disabling framework for");
                XposedBridge.disableHooks = true;
                return;
            }
            CompatibilityInfo compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(bindData, "compatInfo");
            if (appInfo.sourceDir == null) {
                return;
            }
            XposedHelpers.setObjectField(activityThread, "mBoundApplication", bindData);
            XposedInit.loadedPackagesInProcess.add(reportedPackageName);
            LoadedApk loadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

            XResources.setPackageNameForResDir(appInfo.packageName, loadedApk.getResDir());

            String processName = (String) XposedHelpers.getObjectField(bindData, "processName");


            boolean isModule = false;
            int xposedminversion = -1;
            try {
                ApkParser ap = ApkParser.create(new File(appInfo.sourceDir));
                isModule = ap.getApkMeta().metaData.containsKey("xposedmodule");
                if(isModule)
                    xposedminversion = Integer.parseInt(ap.getApkMeta().metaData.get("xposedminversion"));
            } catch (NumberFormatException | IOException e) {
                Hookers.logE("ApkParser fails", e);
            }

            if (isModule && xposedminversion > 92) {
                Utils.logW("New modules detected, hook preferences");
                XposedHelpers.findAndHookMethod(ContextImpl.class, "getSharedPreferences", File.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String fileName = ((File) param.args[0]).getName();
                        File file = new File(ConfigManager.getPrefsPath(appInfo.packageName), fileName);
                        file.createNewFile();
                        file.setReadable(true, false);
                        param.args[0] = file;
                    }
                });
            }
            LoadedApkGetCL hook = new LoadedApkGetCL(loadedApk, reportedPackageName,
                    processName, true);
            hook.setUnhook(XposedHelpers.findAndHookMethod(
                    LoadedApk.class, "getClassLoader", hook));

        } catch (Throwable t) {
            Hookers.logE("error when hooking bindApp", t);
        }
    }
}
