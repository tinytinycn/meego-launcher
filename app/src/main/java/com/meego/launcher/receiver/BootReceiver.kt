package com.meego.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meego.launcher.data.RootTaskProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MeeGoBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // 开机自启动预热：在后台线程预热本地持久化任务缓存，确保首次打开多任务屏有数据展示
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val provider = RootTaskProvider(context)
                    val tasks = provider.getRecentTasks()
                    Log.d(TAG, "Boot warm-up loaded ${tasks.size} tasks")
                } catch (e: Exception) {
                    Log.e(TAG, "Error warming up tasks on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
