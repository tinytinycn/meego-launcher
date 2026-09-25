package com.meego.launcher.data

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meego.launcher.model.RecentTask
import com.meego.launcher.model.TaskSnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class RootTaskProvider(private val context: Context) {
    companion object {
        private const val TAG = "RootTaskProvider"
        private const val TMP_TASKS_JSON_PATH = "/data/local/tmp/recent_tasks.json"
        private const val FIFO_PATH = "/data/local/tmp/meego_cmd"
        private const val CACHE_FILE_NAME = "recent_tasks_cache.json"
        private const val APP_TASKS_JSON_NAME = "recent_tasks.json"
        private const val CMD_QUEUE_DIR_NAME = "cmd_queue"
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val appTasksJsonFile = File(context.filesDir, APP_TASKS_JSON_NAME)
    private val cmdQueueDir = File(context.filesDir, CMD_QUEUE_DIR_NAME)
    private val cmdCounter = AtomicInteger(0)

    suspend fun getRecentTasks(): List<RecentTask> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<RecentTask>()

        // 1. 优先读取守护进程直接写入应用私有目录的 JSON（无 SELinux 权限限制）
        val jsonSource = when {
            appTasksJsonFile.exists() && appTasksJsonFile.canRead() && appTasksJsonFile.length() > 2 -> appTasksJsonFile
            File(TMP_TASKS_JSON_PATH).exists() && File(TMP_TASKS_JSON_PATH).canRead() && File(TMP_TASKS_JSON_PATH).length() > 2 -> File(TMP_TASKS_JSON_PATH)
            else -> null
        }

        if (jsonSource != null) {
            try {
                val content = jsonSource.readText().trim()
                if (content.isNotEmpty() && content != "[]") {
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

                        // 1.1 优先读取系统为该 taskId 生成的官方真实快照文件 (支持私有目录与 tmp 目录)
                        val candidateFiles = listOfNotNull(
                            File(context.filesDir, "snapshots/${taskId}_live.png"),
                            File(context.filesDir, "snapshots/${taskId}_reduced.jpg"),
                            if (snapPath.isNotEmpty()) File(snapPath) else null,
                            File("/data/local/tmp/snapshots/${taskId}_live.png"),
                            File("/data/local/tmp/snapshots/${taskId}_reduced.jpg")
                        )

                        for (file in candidateFiles) {
                            if (file.exists() && file.canRead()) {
                                try {
                                    snapshotBitmap = BitmapFactory.decodeFile(file.absolutePath)
                                    if (snapshotBitmap != null) {
                                        TaskSnapshotCache.put(taskId, snapshotBitmap)
                                        break
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                        }

                        // 1.2 次选：从内存缓存获取该 taskId 的专属快照
                        if (snapshotBitmap == null) {
                            snapshotBitmap = TaskSnapshotCache.get(taskId)
                        }

                        // 1.3 严格回退：手势刚滑出的任务专属快照
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing tasks JSON from ${jsonSource.absolutePath}", e)
            }
        }

        // 关键保序：若有最新手势滑出的任务，确保其绝对处于首位（杜绝守护进程落盘短暂延迟导致任务逆向重排弹跳）
        val swipedTaskId = TaskSnapshotCache.lastSwipedTaskId
        if (swipedTaskId != null) {
            val idx = tasks.indexOfFirst { it.taskId == swipedTaskId }
            if (idx > 0) {
                val swipedItem = tasks.removeAt(idx)
                tasks.add(0, swipedItem)
            }
        }

        // 若成功从守护进程拿到任务，立即持久化到应用私有目录，确保重启后可用
        if (tasks.isNotEmpty()) {
            saveToLocalCache(tasks)
            return@withContext tasks
        }

        // 2. 守护进程尚未就绪或开机初期：从本地磁盘持久化缓存读取
        val cachedTasks = loadFromLocalCache().toMutableList()
        if (cachedTasks.isNotEmpty()) {
            if (swipedTaskId != null) {
                val idx = cachedTasks.indexOfFirst { it.taskId == swipedTaskId }
                if (idx > 0) {
                    val swipedItem = cachedTasks.removeAt(idx)
                    cachedTasks.add(0, swipedItem)
                }
            }
            Log.d(TAG, "Loaded ${cachedTasks.size} tasks from persistent local cache")
            return@withContext cachedTasks
        }

        // 3. 本地缓存亦为空（如刚清除数据）：从 UsageStatsManager 读取最近使用应用
        val usageTasks = loadFromUsageStats()
        if (usageTasks.isNotEmpty()) {
            Log.d(TAG, "Loaded ${usageTasks.size} tasks from UsageStatsManager fallback")
            saveToLocalCache(usageTasks)
            return@withContext usageTasks
        }

        // 4. 最终兜底：从系统 AppTasks 获取
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

        if (tasks.isNotEmpty()) {
            saveToLocalCache(tasks)
        }

        tasks
    }

    private fun saveToLocalCache(tasks: List<RecentTask>) {
        if (tasks.isEmpty()) return
        try {
            val array = JSONArray()
            for (task in tasks) {
                val obj = JSONObject()
                obj.put("taskId", task.taskId)
                obj.put("packageName", task.packageName)
                obj.put("label", task.label)
                array.put(obj)
            }
            cacheFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tasks to local cache", e)
        }
    }

    private fun loadFromLocalCache(): List<RecentTask> {
        val tasks = mutableListOf<RecentTask>()
        if (!cacheFile.exists() || !cacheFile.canRead()) return tasks
        try {
            val content = cacheFile.readText().trim()
            if (content.isEmpty() || content == "[]") return tasks
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val taskId = obj.optInt("taskId", -1)
                val pkgName = obj.optString("packageName", "")
                if (pkgName.isEmpty() || pkgName == context.packageName) continue

                val label = try {
                    val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    obj.optString("label", pkgName)
                }

                val icon = try {
                    packageManager.getApplicationIcon(pkgName)
                } catch (e: Exception) {
                    null
                }

                // 尝试从持久化快照目录中读取快照
                var snapshotBitmap = TaskSnapshotCache.get(taskId)
                if (snapshotBitmap == null && taskId > 0) {
                    val candidateFiles = listOf(
                        File(context.filesDir, "snapshots/${taskId}_live.png"),
                        File(context.filesDir, "snapshots/${taskId}_reduced.jpg"),
                        File("/data/local/tmp/snapshots/${taskId}_live.png"),
                        File("/data/local/tmp/snapshots/${taskId}_reduced.jpg"),
                        File("/data/local/tmp/snapshots/${taskId}.jpg")
                    )
                    for (file in candidateFiles) {
                        if (file.exists() && file.canRead()) {
                            try {
                                snapshotBitmap = BitmapFactory.decodeFile(file.absolutePath)
                                if (snapshotBitmap != null) {
                                    TaskSnapshotCache.put(taskId, snapshotBitmap)
                                    break
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
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
            Log.w(TAG, "Failed to load from local cache", e)
        }
        return tasks
    }

    private fun removeFromLocalCache(taskId: Int, packageName: String) {
        try {
            if (!cacheFile.exists() || !cacheFile.canRead()) return
            val content = cacheFile.readText().trim()
            if (content.isEmpty()) return
            val array = JSONArray(content)
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tid = obj.optInt("taskId", -1)
                val pkg = obj.optString("packageName", "")
                if (tid != taskId && pkg != packageName) {
                    newArray.put(obj)
                }
            }
            cacheFile.writeText(newArray.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove from local cache", e)
        }
    }

    private fun loadFromUsageStats(): List<RecentTask> {
        val tasks = mutableListOf<RecentTask>()
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return tasks
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 1000L * 60 * 60 * 24 * 7 // 最近7天
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            if (!stats.isNullOrEmpty()) {
                val ownPkg = context.packageName
                val sorted = stats
                    .filter {
                        it.lastTimeUsed > 0 &&
                                it.packageName != ownPkg &&
                                it.packageName != "com.android.launcher3" &&
                                it.packageName != "com.android.systemui" &&
                                packageManager.getLaunchIntentForPackage(it.packageName) != null
                    }
                    .sortedByDescending { it.lastTimeUsed }
                    .distinctBy { it.packageName }
                    .take(20)

                var syntheticId = 20000
                for (stat in sorted) {
                    val pkgName = stat.packageName
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
                            taskId = syntheticId++,
                            packageName = pkgName,
                            label = label,
                            icon = icon,
                            snapshot = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UsageStats fallback error", e)
        }
        return tasks
    }

    suspend fun removeTask(taskId: Int, packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "removeTask requested for taskId=$taskId, pkg=$packageName")
            TaskSnapshotCache.remove(taskId)
            File("/data/local/tmp/snapshots/${taskId}_live.png").delete()
            File(context.filesDir, "snapshots/${taskId}_live.png").delete()

            // 1. 优先通过应用私有目录命令队列向特权守护发送彻底杀死命令 (无 SELinux 阻碍，零阻塞)
            enqueueCommand("REMOVE $taskId $packageName")

            // 2. 备选 FIFO 发送
            sendCommand("REMOVE $taskId $packageName")

            // 3. 应用层通知 ActivityManager 清理后台进程
            activityManager.killBackgroundProcesses(packageName)

            // 4. 即时更新本地 JSON 与缓存
            removeTaskFromJsonFile(taskId)
            removeFromLocalCache(taskId, packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove task $taskId", e)
            false
        }
    }

    suspend fun removeAllTasks(tasks: List<RecentTask>): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "removeAllTasks requested for ${tasks.size} tasks")
            TaskSnapshotCache.clear()
            enqueueCommand("CLEAR_ALL")
            sendCommand("CLEAR_ALL")
            for (task in tasks) {
                activityManager.killBackgroundProcesses(task.packageName)
            }
            try {
                if (appTasksJsonFile.exists()) {
                    appTasksJsonFile.writeText("[]")
                }
                val tmpFile = File(TMP_TASKS_JSON_PATH)
                if (tmpFile.exists() && tmpFile.canWrite()) {
                    tmpFile.writeText("[]")
                }
            } catch (e: Exception) {
                // Ignore
            }
            try {
                if (cacheFile.exists()) {
                    cacheFile.writeText("[]")
                }
            } catch (e: Exception) {
                // Ignore
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove all tasks", e)
            false
        }
    }

    private fun removeTaskFromJsonFile(taskId: Int) {
        val targets = listOf(appTasksJsonFile, File(TMP_TASKS_JSON_PATH))
        for (jsonFile in targets) {
            try {
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
                Log.w(TAG, "Failed to update ${jsonFile.name} after removing task $taskId", e)
            }
        }
    }

    fun switchToTask(taskId: Int, packageName: String) {
        try {
            enqueueCommand("SWITCH $taskId")
            sendCommand("SWITCH $taskId")
            // 真实系统任务且在标准 ID 范围内尝试 moveTaskToFront
            if (taskId > 0 && taskId < 20000) {
                try {
                    activityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "moveTaskToFront failed ($taskId), fallback to intent", e)
                }
            }
            // 回退到 Launch Intent 唤起
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to task $packageName", e)
        }
    }

    /**
     * 将指令写入 App 私有目录下的 cmd_queue 队列。
     * 由 Root 守护脚本监听并执行真实进程杀死 (am stack remove + am force-stop + pkill)。
     * 完全不受 Android SELinux 对 shell_data_file 的写入屏蔽限制，且零阻塞。
     */
    private fun enqueueCommand(cmd: String) {
        try {
            if (!cmdQueueDir.exists()) {
                cmdQueueDir.mkdirs()
            }
            val id = cmdCounter.incrementAndGet()
            val file = File(cmdQueueDir, "cmd_${System.currentTimeMillis()}_$id.txt")
            file.writeText(cmd.trim() + "\n")
            Log.d(TAG, "Enqueued command: ${cmd.trim()} -> ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue command: $cmd", e)
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            val fifo = File(FIFO_PATH)
            if (fifo.exists()) {
                val writerThread = Thread {
                    try {
                        FileOutputStream(fifo).use { fos ->
                            fos.write((cmd + "\n").toByteArray())
                            fos.flush()
                        }
                    } catch (e: Exception) {
                        // ignore SELinux EACCES
                    }
                }
                writerThread.start()
                writerThread.join(100) // 最大等待 100ms
                if (writerThread.isAlive) {
                    writerThread.interrupt()
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
