package com.meego.launcher.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meego.launcher.model.LaunchOriginTracker
import com.meego.launcher.model.RecentTask
import com.meego.launcher.model.TaskSnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class RootTaskProvider(private val context: Context) {
    companion object {
        private const val TAG = "RootTaskProvider"
        private const val TASKS_JSON_PATH = "/data/local/tmp/recent_tasks.json"
        private const val FIFO_PATH = "/data/local/tmp/meego_cmd"
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager

    suspend fun getRecentTasks(): List<RecentTask> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<RecentTask>()
        val jsonFile = File(TASKS_JSON_PATH)

        if (jsonFile.exists() && jsonFile.canRead()) {
            try {
                val content = jsonFile.readText()
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val taskId = obj.getInt("taskId")
                    var pkgName = obj.getString("packageName").replace("}", "").replace("{", "").trim()
                    if (pkgName.startsWith(".")) {
                        pkgName = "org.lineageos.aperture"
                    }
                    if (pkgName == "android.task.contacts") {
                        pkgName = "com.android.contacts"
                    }

                    val snapPath = obj.optString("snapshotPath", "")
                    var snapshotBitmap: Bitmap? = null

                    // 1. 优先读取系统为该 taskId 生成的官方真实快照文件
                    if (snapPath.isNotEmpty()) {
                        val file = File(snapPath)
                        if (file.exists() && file.canRead()) {
                            try {
                                snapshotBitmap = BitmapFactory.decodeFile(snapPath)
                                if (snapshotBitmap != null) {
                                    TaskSnapshotCache.put(taskId, snapshotBitmap)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error decoding snapshot $snapPath", e)
                            }
                        }
                    }

                    // 2. 次选：从内存缓存获取该 taskId 的专属快照
                    if (snapshotBitmap == null) {
                        snapshotBitmap = TaskSnapshotCache.get(taskId)
                    }

                    // 3. 严格回退：仅当该任务是手势刚滑出的任务（taskId 与 pkgName 严格双重匹配）时，取用刚滑出的临时快照
                    if (snapshotBitmap == null && taskId == TaskSnapshotCache.lastSwipedTaskId && pkgName == TaskSnapshotCache.lastSwipedPackageName) {
                        snapshotBitmap = TaskSnapshotCache.lastSwipedBitmap
                        if (snapshotBitmap != null) {
                            TaskSnapshotCache.put(taskId, snapshotBitmap)
                        }
                    }

                    val label = try {
                        val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        pkgName
                    }

                    val icon = try {
                        packageManager.getApplicationIcon(pkgName)
                    } catch (e: Exception) {
                        null
                    }

                    tasks.add(
                        RecentTask(
                            taskId = taskId,
                            packageName = pkgName,
                            label = label,
                            icon = icon,
                            snapshot = snapshotBitmap
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing recent_tasks.json", e)
            }
        }

        // 保底方案: 如果特权 JSON 为空，则从系统 AppTasks 获取
        if (tasks.isEmpty()) {
            try {
                val appTasks = activityManager.appTasks
                val ownPkg = context.packageName
                for (task in appTasks) {
                    val taskInfo = task.taskInfo
                    val baseIntent = taskInfo.baseIntent ?: continue
                    val component = baseIntent.component ?: continue
                    val pkgName = component.packageName
                    if (pkgName == ownPkg) continue

                    val label = try {
                        val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        pkgName
                    }

                    val icon = try {
                        packageManager.getApplicationIcon(pkgName)
                    } catch (e: Exception) {
                        null
                    }

                    tasks.add(
                        RecentTask(
                            taskId = taskInfo.taskId,
                            packageName = pkgName,
                            label = label,
                            icon = icon,
                            snapshot = null
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fallback appTasks", e)
            }
        }

        tasks
    }

    suspend fun removeTask(taskId: Int, packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            TaskSnapshotCache.remove(taskId)
            File("/data/local/tmp/snapshots/${taskId}_live.png").delete()
            // 通过特权 FIFO 发送指令 (传递 taskId 与 packageName)
            sendCommand("REMOVE $taskId $packageName")
            activityManager.killBackgroundProcesses(packageName)
            removeTaskFromJsonFile(taskId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove task $taskId", e)
            false
        }
    }

    suspend fun removeAllTasks(tasks: List<RecentTask>): Boolean = withContext(Dispatchers.IO) {
        TaskSnapshotCache.clear()
        sendCommand("CLEAR_ALL")
        for (task in tasks) {
            activityManager.killBackgroundProcesses(task.packageName)
        }
        try {
            val jsonFile = File(TASKS_JSON_PATH)
            if (jsonFile.exists() && jsonFile.canWrite()) {
                jsonFile.writeText("[]")
            }
        } catch (e: Exception) {
            // Ignore
        }
        true
    }

    private fun removeTaskFromJsonFile(taskId: Int) {
        try {
            val jsonFile = File(TASKS_JSON_PATH)
            if (jsonFile.exists() && jsonFile.canRead() && jsonFile.canWrite()) {
                val content = jsonFile.readText().trim()
                if (content.isNotEmpty()) {
                    val jsonArray = JSONArray(content)
                    val newArray = JSONArray()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        if (obj.getInt("taskId") != taskId) {
                            newArray.put(obj)
                        }
                    }
                    jsonFile.writeText(newArray.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update JSON after removing task $taskId", e)
        }
    }

    private fun readCurrentTaskId(): Int? {
        return try {
            val file = File("/data/local/tmp/current_task_id.txt")
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun switchToTask(taskId: Int, packageName: String) {
        try {
            sendCommand("SWITCH $taskId")
            try {
                activityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                return
            } catch (e: Exception) {
                Log.w(TAG, "moveTaskToFront failed, fallback to launch intent", e)
            }
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to task $packageName", e)
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            val fifo = File(FIFO_PATH)
            if (fifo.exists()) {
                FileOutputStream(fifo).use { fos ->
                    fos.write((cmd + "\n").toByteArray())
                    fos.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing command to FIFO: $cmd", e)
        }
    }
}
