package app.orchard.tv.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppCatalogCacheTest {
    @Test fun offlineLibraryChoiceInvalidatesCachedArtwork() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        IconLibrary.init(context)
        val pm = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply {
            packageName = "com.ktcp.video"
            icon = android.R.drawable.sym_def_app_icon
        }
        val activity = ActivityInfo().apply {
            packageName = app.packageName
            name = "${app.packageName}.MainActivity"
            applicationInfo = app
            exported = true
            enabled = true
        }
        pm.installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            activities = arrayOf(activity)
        })
        pm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply { activityInfo = activity },
        )
        try {
            IconLibrary.choose(app.packageName, IconLibrary.Pick.Library)
            val bundled = AppCatalog.load(context).single { it.packageName == app.packageName }.art
            org.junit.Assert.assertSame(bundled, AppCatalog.load(context).single { it.packageName == app.packageName }.art)
            IconLibrary.choose(app.packageName, IconLibrary.Pick.Own)
            val own = AppCatalog.load(context).single { it.packageName == app.packageName }.art
            org.junit.Assert.assertNotSame(bundled, own)
            org.junit.Assert.assertSame(own, AppCatalog.load(context).single { it.packageName == app.packageName }.art)
        } finally {
            IconLibrary.choose(app.packageName, null)
        }
    }

    @Test fun localeChangeInvalidatesInMemoryLabelsWithoutPackageUpdate() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val pm = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply {
            packageName = "example.cache.locale"
            nonLocalizedLabel = "English title"
            icon = android.R.drawable.sym_def_app_icon
        }
        val activity = ActivityInfo().apply {
            packageName = app.packageName
            name = "${app.packageName}.MainActivity"
            applicationInfo = app
            exported = true
            enabled = true
            nonLocalizedLabel = "English title"
        }
        pm.installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            activities = arrayOf(activity)
            lastUpdateTime = 1234L
        })
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.addResolveInfoForIntent(intent, ResolveInfo().apply { activityInfo = activity })
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("English title", AppCatalog.load(context).single { it.packageName == app.packageName }.label)
            activity.nonLocalizedLabel = "中文名称"
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("中文名称", AppCatalog.load(context).single { it.packageName == app.packageName }.label)
        } finally {
            Locale.setDefault(original)
        }
    }
}
