package com.snstimer.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class InstalledAppsRepository(private val context: Context) {

    fun defaultTargetPackages(apps: List<InstalledApp>): Set<String> =
        apps.asSequence()
            .filter { app ->
                app.packageName in DEFAULT_TARGET_PACKAGES ||
                    app.label.trim().equals("YouTube", ignoreCase = true) ||
                    app.label.trim().equals("유튜브", ignoreCase = true)
            }
            .map { it.packageName }
            .toSet()

    fun loadLaunchableApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return resolveInfos
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                InstalledApp(
                    packageName = packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    companion object {
        private val DEFAULT_TARGET_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.twitter.android",
            "com.snapchat.android",
            "com.pinterest",
            "com.reddit.frontpage",
            "com.kakao.talk",
            "com.nhn.android.band",
            "jp.naver.line.android",
            "com.tencent.mm",
        )
    }
}
