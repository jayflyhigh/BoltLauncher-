package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import androidx.core.content.ContextCompat

class MyAccessibilityService : AccessibilityService() {

    private val powerMenuReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_POWER_MENU && performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) {
                resultCode = Activity.RESULT_OK
            }
        }
    }

    private var powerMenuReceiverRegistered = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!powerMenuReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, powerMenuReceiver, IntentFilter(ACTION_POWER_MENU),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            powerMenuReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        if (powerMenuReceiverRegistered) unregisterReceiver(powerMenuReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }

    companion object {
        private const val ACTION_POWER_MENU = "app.olauncher.action.POWER_MENU"

        fun requestPowerMenu(context: Context) {
            context.applicationContext.sendOrderedBroadcast(
                Intent(ACTION_POWER_MENU).setPackage(context.packageName),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK) {
                            context.showToast(R.string.bolt_power_menu_unavailable)
                        }
                    }
                },
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        }
    }
}
