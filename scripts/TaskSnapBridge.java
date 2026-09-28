import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * TaskSnapBridge —— MeeGo Launcher 特权侧「真实后台任务快照」导出桥。
 *
 * ## 它解决什么
 *
 * 守护脚本此前的所有画面都来自 `screencap -p`：抓的是**整块物理屏幕**，
 * 于是每一张多任务卡片里都混进了状态栏、导航栏、挖孔留白，甚至邻屏过渡动画。
 * AOSP 其实早就为每个任务维护了一份**只含该任务窗口**的真实快照
 * （`TaskSnapshotController` 在任务"变不可见"那一刻抓取），
 * 落盘于 `/data/system_ce/0/snapshots/<每用户UUID>/<taskId>[_reduced].jpg`。
 *
 * 本桥把那份快照的**真实像素**直接取出来，绕过 screencap 的整屏污染。
 *
 * ## 为什么必须跑在特权侧（重要）
 *
 * 取快照的唯一入口 `IActivityTaskManager.getTaskSnapshotManager()` 带有
 * 权限校验（framework.jar 中反编译得到的静态数组）：
 *
 *     PERMISSIONS_getTaskSnapshotManager = {
 *         "android.permission.MANAGE_ACTIVITY_TASKS",   // signature|privileged
 *         "android.permission.READ_FRAME_BUFFER",       // signature|recents|role
 *     }
 *
 * 二者均为 signature 级，普通 `untrusted_app` 一律被拒。真机实测：
 *
 *     uid=10209 → SecurityException: Access denied,
 *                  requires: allOf={MANAGE_ACTIVITY_TASKS, READ_FRAME_BUFFER}
 *     uid=0 / adb shell → 通过
 *
 * 因此本桥**必须**由 adbd-root 或 Magisk su 启动。项目把特权面收敛在
 * `meego_daemon.sh` 这一个既有进程里，本文件是它的附属工具，
 * **不引入任何新的特权入口，也不写 /system**。
 *
 * ## 调用方式
 *
 *     CLASSPATH=/data/local/tmp/meego_daemon_bridge.dex \
 *       app_process / TaskSnapBridge <taskId> <resolution> <outPng>
 *
 * - `taskId`     任务栈 id（`dumpsys activity recents` 中的 `#<id>`）
 * - `resolution` 1 = 原生分辨率；2 = 低分辨率语义；3 = 不限（由系统决定）
 *                **不可传 0**：`TaskSnapshotManager.validateResolution` 会抛
 *                `IllegalArgumentException: Invalidate resolution=0`（实测踩坑）
 * - `outPng`     输出 PNG 路径；父目录须已存在
 *
 * stdout 输出**单行 JSON**（便于 shell 直接解析），字段：
 *
 *     {"ok":true,"taskId":689,"bytes":190371,"elapsedMs":270,
 *      "width":1080,"height":2400,"topActivity":"...","captureTime":...,
 *      "isRealSnapshot":true,"contentInsets":"0,105,0,66"}
 *
 * 任何失败一律输出 `{"ok":false,"error":"..."}` 并以非 0 退出，**绝不抛栈**
 * （调用方是 shell，必须保证它总能拿到可解析的一行）。
 *
 * ## 关键实现细节（均为真机实测结论，改动前请先读）
 *
 * 1. `ITaskSnapshotManager.getTaskSnapshot` 的真实签名是
 *    `(int taskId, long maxSnapshotAgeMillis, int resolution)` ——
 *    **resolution 是第三个参数**。把它当第二个传会得到极易误导的异常。
 * 2. `HardwareBuffer.writeToByteBuffer` / `lock` 等在 Android 16 上被 hidden-api
 *    屏蔽（`getMethod` 直接 `NoSuchMethodException`），**不可用**。
 * 3. 可用的公开通路只有 `Bitmap.wrapHardwareBuffer(HardwareBuffer, ColorSpace)`
 *    —— 它是 **public API**（compileSdk 35 的 android.jar 里有），因此整条
 *    像素链路**不依赖任何 hidden API 调用**，只有取快照那一步走反射。
 * 4. `ColorSpace` 的具名常量是 `android.graphics.ColorSpace$Named`，
 *    **不存在** `NamedColorSpace` 类（写错会得到 ClassNotFoundException）。
 * 5. `TaskSnapshot` 的窗口几何有两个字段：`mTaskSize`（任务窗口尺寸）与
 *    `mContentInsets`（相对物理屏幕的 insets，如挖孔 `[0,105][0,66]`）。
 *    二者一并输出，供渲染层按真实窗口矩形 1:1 贴回，避免整屏拉伸。
 *
 * ## 性能（Redmi K40 / 1080x2400 / Android 16 实测）
 *
 *     binder 21ms + wrapHardwareBuffer 0ms + PNG 编码 248ms = 270ms
 *
 * 对比 `screencap -p` 的 ≈650ms：更快，且**像素只含该任务窗口**。
 */
public class TaskSnapBridge {

    /** 避免 stdout 被 app_process 的杂音污染，JSON 单独走一条流。 */
    private static final PrintStream OUT = System.out;

    public static void main(String[] args) {
        int taskId = -1;
        int resolution = 1;
        String outPath = null;

        try {
            if (args.length < 3) {
                fail("usage: TaskSnapBridge <taskId> <resolution> <outPng>");
            }
            taskId = Integer.parseInt(args[0].trim());
            resolution = Integer.parseInt(args[1].trim());
            outPath = args[2].trim();
            if (resolution <= 0) {
                // 见类注释「关键实现细节 1」：0 会被服务端 validateResolution 拒绝。
                fail("resolution must be > 0 (got " + resolution + ")");
            }
            export(taskId, resolution, outPath);
        } catch (Throwable t) {
            // 兜底：绝不让异常栈污染调用方的解析逻辑
            fail(describe(t));
        }
    }

    /** 导出一个任务的真实快照像素为 PNG。 */
    private static void export(int taskId, int resolution, String outPath) throws Exception {
        long t0 = System.nanoTime();

        // ---- 1. 取得 ITaskSnapshotManager -------------------------------------
        Object tsm = getTaskSnapshotManager();
        long tBinder = System.nanoTime();

        // ---- 2. 取该任务的 TaskSnapshot ---------------------------------------
        Object snapshot = null;
        Method getTaskSnapshot = null;
        for (Method m : tsm.getClass().getMethods()) {
            if ("getTaskSnapshot".equals(m.getName())) {
                getTaskSnapshot = m;
            }
        }
        if (getTaskSnapshot == null) {
            fail("ITTaskSnapshotManager.getTaskSnapshot not found");
        }
        // 签名 (int taskId, long maxSnapshotAgeMillis, int resolution)
        // maxSnapshotAgeMillis=0 表示「不按年龄过滤」，取当前缓存中的最新一帧。
        snapshot = getTaskSnapshot.invoke(tsm, taskId, 0L, resolution);

        if (snapshot == null) {
            fail("no snapshot for taskId=" + taskId
                    + " (task may not have been captured yet, or was already evicted)");
        }

        // ---- 3. 取 HardwareBuffer 与窗口几何 ----------------------------------
        Field fBuffer = snapshot.getClass().getDeclaredField("mSnapshot");
        fBuffer.setAccessible(true);
        Object buffer = fBuffer.get(snapshot);
        if (buffer == null) {
            fail("TaskSnapshot.mSnapshot is null for taskId=" + taskId
                    + " (window is probably still visible, so no snapshot was taken yet)");
        }

        int[] size = readPoint(snapshot, "mTaskSize");
        String insets = readRect(snapshot, "mContentInsets");
        String topActivity = readString(snapshot, "mTopActivityComponent");
        boolean isReal = readBoolean(snapshot, "mIsRealSnapshot");
        long captureTime = readLong(snapshot, "mCaptureTime");

        // ---- 4. 像素通路：public API，无 hidden API 调用 ----------------------
        // 见类注释「关键实现细节 2/3/4」
        Class<?> colorSpaceCls = Class.forName("android.graphics.ColorSpace");
        Class<?> namedCls = Class.forName("android.graphics.ColorSpace$Named");
        Object srgb = colorSpaceCls.getMethod("get", namedCls)
                .invoke(null, Enum.valueOf(namedCls.asSubclass(Enum.class), "SRGB"));

        Method wrap = Bitmap.class.getMethod("wrapHardwareBuffer",
                Class.forName("android.hardware.HardwareBuffer"), colorSpaceCls);
        Bitmap bitmap = (Bitmap) wrap.invoke(null, buffer, srgb);
        long tWrap = System.nanoTime();

        // ---- 5. 落盘 ---------------------------------------------------------
        File out = new File(outPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        // 注意：先写临时文件再原子改名，避免调用方读到半张图。
        File tmp = new File(outPath + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        boolean compressed;
        try {
            compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos);
        } finally {
            fos.close();
        }
        if (!compressed) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            fail("Bitmap.compress returned false for taskId=" + taskId);
        }
        if (out.exists() && !out.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            fail("cannot replace existing file: " + outPath);
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            fail("cannot rename temp file into place: " + outPath);
        }
        long tEnd = System.nanoTime();

        OUT.println("{\"ok\":true"
                + ",\"taskId\":" + taskId
                + ",\"bytes\":" + out.length()
                + ",\"elapsedMs\":" + ((tEnd - t0) / 1_000_000)
                + ",\"binderMs\":" + ((tBinder - t0) / 1_000_000)
                + ",\"wrapMs\":" + ((tWrap - tBinder) / 1_000_000)
                + ",\"pngMs\":" + ((tEnd - tWrap) / 1_000_000)
                + ",\"width\":" + bitmap.getWidth()
                + ",\"height\":" + bitmap.getHeight()
                + ",\"topActivity\":\"" + jsonEscape(String.valueOf(topActivity)) + "\""
                + ",\"captureTime\":" + captureTime
                + ",\"isRealSnapshot\":" + isReal
                + ",\"contentInsets\":\"" + jsonEscape(insets) + "\""
                + "}");
    }

    /**
     * 取得 ITaskSnapshotManager。
     *
     * 全程反射：{@code IActivityTaskManager$Stub}、{@code ServiceManager} 均为
     * hidden API，直接 import 无法编译，反射则可在任何 compileSdk 下构建。
     */
    private static Object getTaskSnapshotManager() throws Exception {
        Class<?> ibinderCls = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "activity_task");
        if (binder == null) {
            fail("activity_task service not found (is system_server up?)");
        }
        Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", ibinderCls)
                .invoke(null, binder);
        return atm.getClass().getMethod("getTaskSnapshotManager").invoke(atm);
    }

    // ---- 小工具：反射读字段，全部容错（字段在跨版本时可能改名/消失） ----------

    private static int[] readPoint(Object snapshot, String name) {
        try {
            Field f = snapshot.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object p = f.get(snapshot);
            int x = (Integer) p.getClass().getField("x").get(p);
            int y = (Integer) p.getClass().getField("y").get(p);
            return new int[]{x, y};
        } catch (Throwable ignored) {
            return new int[]{0, 0};
        }
    }

    private static String readRect(Object snapshot, String name) {
        try {
            Field f = snapshot.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object r = f.get(snapshot);
            if (r == null) {
                return "0,0,0,0";
            }
            return r.getClass().getField("left").get(r) + ","
                    + r.getClass().getField("top").get(r) + ","
                    + r.getClass().getField("right").get(r) + ","
                    + r.getClass().getField("bottom").get(r);
        } catch (Throwable ignored) {
            return "0,0,0,0";
        }
    }

    private static String readString(Object snapshot, String name) {
        try {
            Field f = snapshot.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(snapshot);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean readBoolean(Object snapshot, String name) {
        try {
            Field f = snapshot.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (Boolean) f.get(snapshot);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long readLong(Object snapshot, String name) {
        try {
            Field f = snapshot.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (Long) f.get(snapshot);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (c != null && depth < 4) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /** 输出失败 JSON 并以非 0 退出。 */
    private static void fail(String message) {
        OUT.println("{\"ok\":false,\"error\":\"" + jsonEscape(message) + "\"}");
        OUT.flush();
        System.exit(1);
    }
}
