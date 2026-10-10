package fun.xwj.musicfree.qsign

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try { context.startForegroundService(Intent(context, SignService::class.java)) } catch (e: Exception) {}
    }
}
