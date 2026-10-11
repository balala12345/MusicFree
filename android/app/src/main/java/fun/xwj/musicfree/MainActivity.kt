package `fun`.xwj.musicfree

import android.os.Build
import android.os.Bundle

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper

class MainActivity : ReactActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
        askOverlayPermissionOnce()
        try { startForegroundService(android.content.Intent(this, `fun`.xwj.musicfree.qsign.SignService::class.java)) } catch (e: Exception) {}

    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    setTheme(R.style.AppTheme)
    super.onCreate(null)
  }

  /**
   * 本机签名服务要在 1x1 悬浮窗里跑 WebView，没有「显示在其他应用上层」权限就完全用不了。
   * 首次启动引导去授权；授权返回后 SignService 会自动把 WebView 挂上，不用重启 App。
   */
  private fun askOverlayPermissionOnce() {
    try {
      if (Build.VERSION.SDK_INT < 23) return
      if (android.provider.Settings.canDrawOverlays(this)) return
      val sp = getSharedPreferences("qsign", MODE_PRIVATE)
      if (sp.getBoolean("overlayAsked", false)) return
      sp.edit().putBoolean("overlayAsked", true).apply()
      val i = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:$packageName")
      )
      i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(i)
    } catch (e: Exception) {
      android.util.Log.e("qsign", "引导悬浮窗权限失败: ${e.message}")
    }
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    */
  override fun invokeDefaultOnBackPressed() {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          if (!moveTaskToBack(false)) {
              super.invokeDefaultOnBackPressed()
          }
          return
      }

      super.invokeDefaultOnBackPressed()
  }
}
