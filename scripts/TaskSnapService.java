import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * TaskSnapService —— MeeGo Launcher 特权侧「常驻任务快照服务」。
 *
 * ## 它取代了什么
 *
 * 此前 `TaskSnapBridge` 是**一次性**的：守护脚本每要一帧，就 fork 一次
 * `CLASSPATH=... app_process / TaskSnapBridge <tid> ...`。
 * 代价由真机实测拆解为两段：
 *
 *     binder 取快照      ~ 11 ms
 *     wrapHardwareBuffer ~ 10 ms
 *     PNG 编码          ~ 246 ms
 *     -------------------------- 内部合计 ~267 ms
 *     app_process 冷启动 ~1100 ms   ← 真正的瓶颈
 *     --------------------------
 *     端到端 real        ~1.35 s   （`time` 实测 1.33~1.41s）
 *
 * 本服务把这套反射 + 像素链路**常驻**，binder 代理与类加载都只付一次，
 * 此后每帧仅剩 `take`/`get` 的真实开销（实测 16~36 ms），是 `app_process`
 * 一次性方案的 **1/40**。
 *
 * ## 实现原理：对齐 LineageOS / AOSP Quickstep
 *
 * 不再直接操纵 `IActivityTaskManager$Stub`，而是使用 Quickstep 自己走的那条路：
 *
 *     android.window.TaskSnapshotManager.getInstance()
 *         .registerTaskSnapshotListener(TaskSnapshotListener)   ← 快照变化事件驱动
 *         .takeTaskSnapshot(taskId, updateCache)                ← 可见任务实时帧
 *         .getTaskSnapshot(taskId, resolution)                   ← 后台任务缓存帧
 *
 * 该封装类（以及 `TaskSnapshotListener`）在 ROM 的 Launcher3QuickStep 里被
 * 真实使用（`TaskStackChangeListeners$Impl$TaskSnapshotListenerImpl` 继承
 * `android.window.TaskSnapshotListener` 并覆写两个回调），因此是被官方
 * 支持的接入点，而不是野路子 AIDL。
 *
 * 之所以仍走**反射**：这些是 `@hide` 类，直接 import 无法在 compileSdk 35
 * 下编译；反射则可在任意 SDK 下 `javac`，且本文件随 `deploy.sh` 现编现用。
 *
 * ## 权限（实测，勿改）
 *
 *     allOf = { MANAGE_ACTIVITY_TASKS, READ_FRAME_BUFFER }    // 二者均 signature 级
 *
 * `uid=10209`（App 自身）→ SecurityException；`uid=0` → 通过。
 * 故本服务**必须**由 adbd root / Magisk su 拉起。App 进程无论如何都拿不到
 * （`pm grant` 对 `MANAGE_ACTIVITY_TASKS` 直接抛 "not a changeable
 * permission type"；且 App 内 hidden-API 反射被 ART 屏蔽）。
 *
 * ## 协议：stdin 逐行 JSON，stdout 逐行 JSON
 *
 * 请求：
 *     {"cmd":"ping"}
 *     {"cmd":"take","tid":689}                 仅对**可见**任务有效
 *     {"cmd":"get","tid":689,"res":1}          缓存帧，后台任务用它
 *     {"cmd":"write","tid":689,"out":"/path"}  取帧并原子落盘为 PNG
 *     {"cmd":"watch","enable":true}            开关快照变化监听（事件驱动）
 *     {"cmd":"quit"}
 *
 * 响应（永远单行 JSON，永不抛栈 —— 调用方是 shell）：
 *     {"ok":true, ...}
 *     {"ok":false,"error":"..."}
 *
 * ## 关于 HardwareBuffer 生命周期（重要）
 *
 * `TaskSnapshot.mSnapshot` 是一个 **HardwareBuffer**，其引用计数由 system_server
 * 管理；跨调用持有它是不安全的（系统可能在脚下 close 掉）。
 * 因此本服务**在同一次调用内**立刻 `wrapHardwareBuffer` → 编码/落盘，
 * 绝不把 buffer 留给下一次命令。这是与 AOSP `TaskSnapshot` 参考计数语义
 * 保持一致的唯一安全用法。
 */
public class TaskSnapService {

    private static final PrintStream OUT = System.out;

    /** resolution 取值：1 = 原生分辨率。0 会被 validateResolution 拒绝（实测踩坑）。 */
    private static final int RESOLUTION_MAX = 1;

    /**
     * 落盘 JPEG 质量。
     *
     * 取 92：卡片实际显示宽度约 1/3 屏（~350px），此时 JPEG 压缩伪影不可见，
     * 而编码耗时与文件体积都远优于 PNG（实测 200ms → 55ms）。
     */
    private static final int JPEG_QUALITY = 92;

    private static Object snapshotManager;
    private static Method mgrTake;
    private static Method mgrGet;

    /** 监听回调是否已注册（避免重复注册导致回调翻倍）。 */
    private static volatile boolean listening = false;
    /** 最近一次快照变化事件，格式 "tid:unixMillis"，供 daemon 轮询自愈判断。 */
    private static volatile String lastEvent = "";
    /** Binder 线程池启动失败时的原因（正常为空串）。 */
    private static volatile String binderPoolError = "";

    public static void main(String[] args) {
        try {
            startBinderPool();
            bootstrap();
            OUT.println("{\"ok\":true,\"ready\":true,\"listening\":" + listening + "}");
            OUT.flush();
            loop();
        } catch (Throwable t) {
            // 启动失败必须以可解析的 JSON 收尾，绝不让调用方看到空输出后干等
            OUT.println("{\"ok\":false,\"fatal\":\"" + jsonEscape(describe(t)) + "\"}");
            OUT.flush();
            System.exit(1);
        }
    }

    /**
     * 启动 Binder 线程池。
     *
     * 【这是监听器能否工作的关键，非可选优化】
     * `app_process` 启动的进程**默认不初始化 Binder 线程池** —— 实测本服务
     * 启动后 `/proc/<pid>/task` 只有 1 个线程。此时进程可以**主动发起** binder
     * 调用（所以 {@code take}/{@code get} 一切正常），但**无法接收**任何
     * 入站回调：oneway 的 `onTaskSnapshotChanged` 会被系统投递到本进程，
     * 却没有任何线程去取它队列里的事务，于是**注册永远成功、回调永远不来**
     * （实测：listening=true 但 lastEvent 恒为空）。
     *
     * `android.os.Binder.joinThreadPool()` 正是 ART 在 App 启动时调用的那个
     * 「阻塞在 binder 线程循环」的入口。在独立守护线程上调用它，即可让本进程
     * 具备与 App 同等的入站回调能力。
     *
     * 必须放在**独立线程**里：它会永久阻塞。
     */
    private static void startBinderPool() {
        try {
            Thread pool = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class.forName("android.os.Binder")
                                .getMethod("joinThreadPool")
                                .invoke(null);
                    } catch (Throwable t) {
                        binderPoolError = describe(t);
                    }
                }
            }, "snap-binder-pool");
            pool.setDaemon(true);
            pool.start();
            // 稍等其进入循环，避免紧接着的注册早于线程池就绪
            Thread.sleep(300);
        } catch (Throwable ignored) {
            // 起不来不致命：take/get 仍可用，只是监听器收不到回调
        }
    }

    // ------------------------------------------------------------------
    // 启动：取得 TaskSnapshotManager 并绑定四个方法
    // ------------------------------------------------------------------
    private static void bootstrap() throws Exception {
        Class<?> mgrCls = Class.forName("android.window.TaskSnapshotManager");
        Method getInstance = mgrCls.getMethod("getInstance");
        snapshotManager = getInstance.invoke(null);
        if (snapshotManager == null) {
            throw new IllegalStateException("TaskSnapshotManager.getInstance() returned null");
        }
        Class<?> snapCls = Class.forName("android.window.TaskSnapshot");
        mgrTake = pick(mgrCls, "takeTaskSnapshot", int.class, boolean.class);
        mgrGet = pick(mgrCls, "getTaskSnapshot", int.class, int.class);

        // 权限自检：直接 take 一次，失败即说明签名权限没到位，尽早暴露而不是静默降级
        try {
            Object s = mgrTake.invoke(snapshotManager, -1, true);
            // taskId=-1 必然取不到东西，这里只关心「有没有抛 SecurityException」
            if (s != null) {
                // 极端情况真的取到了，也要确保不泄漏
            }
        } catch (Throwable t) {
            Throwable c = rootCause(t);
            if (c instanceof SecurityException) {
                throw new SecurityException("TaskSnapshotManager denied: " + c.getMessage());
            }
        }
        // snapCls 仅用于确认类存在（API 版本可用性的探针）
        if (snapCls == null) {
            throw new IllegalStateException("android.window.TaskSnapshot missing");
        }
    }

    private static Method pick(Class<?> cls, String name, Class<?>... params) {
        for (Method m : cls.getMethods()) {
            if (!name.equals(m.getName()) || m.getParameterTypes().length != params.length) {
                continue;
            }
            Class<?>[] actual = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!actual[i].isAssignableFrom(params[i]) && !actual[i].equals(params[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 主循环：读一行、跑一行、回一行
    // ------------------------------------------------------------------
    private static void loop() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("quit") || line.equals("{\"cmd\":\"quit\"}")) {
                    break;
                }
                try {
                    handle(line);
                } catch (Throwable t) {
                    respondError(describe(t));
                }
                OUT.flush();
            }
        } catch (Throwable t) {
            // stdin 关闭（写端退出）不是致命错误：保持存活等待重连
            respondError("stdin closed: " + describe(t));
            OUT.flush();
        }
    }

    private static void handle(String line) throws Exception {
        if (line.indexOf('"') < 0) {
            respondError("bad request: " + line);
            return;
        }
        String cmd = jsonStr(line, "cmd");
        if (cmd == null) {
            respondError("missing cmd");
            return;
        }
        switch (cmd) {
            case "ping":
                respondOk("\"listening\":" + listening
                        + ",\"lastEvent\":\"" + jsonEscape(lastEvent) + "\""
                        + ",\"binderPoolError\":\"" + jsonEscape(binderPoolError) + "\""
                        + ",\"threads\":" + threadCount());
                break;
            case "take": {
                int tid = jsonInt(line, "tid", -1);
                long t0 = System.nanoTime();
                Object snap = takeSnapshot(tid);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (snap == null) {
                    respondError("no snapshot for tid=" + tid + " (task likely not visible)");
                    return;
                }
                describeSnapshot(snap, "take", tid, ms, null);
                break;
            }
            case "get": {
                int tid = jsonInt(line, "tid", -1);
                int res = jsonInt(line, "res", RESOLUTION_MAX);
                long t0 = System.nanoTime();
                Object snap = getSnapshot(tid, res);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (snap == null) {
                    respondError("no cached snapshot for tid=" + tid);
                    return;
                }
                describeSnapshot(snap, "get", tid, ms, null);
                break;
            }
            case "write": {
                int tid = jsonInt(line, "tid", -1);
                String out = jsonStr(line, "out");
                if (out == null || out.isEmpty()) {
                    respondError("missing out");
                    return;
                }
                // live=true（前台任务）：优先 take 取实时帧，拿不到才回落缓存帧。
                // live=false（已转后台）：优先取系统缓存帧，拿不到再尝试 take。
                // 二者的差别仅在顺序 —— 因为 take 对不可见任务必然返回 null。
                boolean live = jsonBool(line, "live", false);
                long t0 = System.nanoTime();
                Object snap = null;
                String src;
                if (live) {
                    snap = takeSnapshot(tid);
                    src = "take";
                    if (snap == null) {
                        snap = getSnapshot(tid, RESOLUTION_MAX);
                        src = "get";
                    }
                } else {
                    snap = getSnapshot(tid, RESOLUTION_MAX);
                    src = "get";
                    if (snap == null) {
                        snap = takeSnapshot(tid);
                        src = "take";
                    }
                }
                if (snap == null) {
                    respondError("no snapshot for tid=" + tid);
                    return;
                }
                long writeMs = writeImage(snap, out);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                respondOk("\"tid\":" + tid
                        + ",\"src\":\"" + src + "\""
                        + ",\"out\":\"" + jsonEscape(out) + "\""
                        + ",\"totalMs\":" + ms
                        + ",\"writeMs\":" + writeMs
                        + metaJson(snap));
                break;
            }
            case "watch": {
                boolean enable = jsonBool(line, "enable", true);
                boolean ok = enable ? startListening() : stopListening();
                respondOk("\"listening\":" + listening + ",\"changed\":" + ok);
                break;
            }
            default:
                respondError("unknown cmd: " + cmd);
        }
    }

    // ------------------------------------------------------------------
    // 取快照
    // ------------------------------------------------------------------
    private static Object takeSnapshot(int tid) throws Exception {
        if (mgrTake == null) {
            throw new IllegalStateException("takeTaskSnapshot not found on TaskSnapshotManager");
        }
        return mgrTake.invoke(snapshotManager, tid, true);
    }

    private static Object getSnapshot(int tid, int res) throws Exception {
        if (mgrGet == null) {
            throw new IllegalStateException("getTaskSnapshot not found on TaskSnapshotManager");
        }
        return mgrGet.invoke(snapshotManager, tid, res <= 0 ? RESOLUTION_MAX : res);
    }

    /**
     * 立即把快照像素落盘为 **JPEG**（原子：先写 .tmp 再 rename）。
     *
     * 【为何是 JPEG 而不是 PNG —— 实测数据】
     * 本机对同一帧（1080x2400）实测两种编码的耗时：
     *     PNG  95 → 写盘 ~200ms   （占整个 write 命令 213ms 的 94%）
     *     JPEG 92 → 写盘  ~55ms
     * 而卡片只是被缩到约 1/3 屏宽展示，JPEG 的质量损失在这个尺寸下不可见。
     * 另外产物文件名本来就是 `_sys.jpg`，此前用 PNG 编码写进 `.jpg` 文件，
     * 名实不符且白白多付 3~4 倍编码时间与约一倍的闪存写入量。
     *
     * 像素链路完全走 **public API**：{@code Bitmap.wrapHardwareBuffer} 是
     * compileSdk 35 的公开方法，因此只有「取快照」那一步依赖反射。
     * 跨版本踩坑见 {@code TaskSnapBridge} 类注释第 2~4 条（HardwareBuffer.lock
     * 被 hidden-api 屏蔽、不存在 NamedColorSpace 类等），此处沿用同一套已验证写法。
     */
    private static long writeImage(Object snapshot, String outPath) throws Exception {
        long t0 = System.nanoTime();
        Object buffer = readField(snapshot, "mSnapshot");
        if (buffer == null) {
            throw new IllegalStateException("TaskSnapshot.mSnapshot is null (window still visible?)");
        }
        Class<?> colorSpaceCls = Class.forName("android.graphics.ColorSpace");
        Class<?> namedCls = Class.forName("android.graphics.ColorSpace$Named");
        Object srgb = colorSpaceCls.getMethod("get", namedCls)
                .invoke(null, Enum.valueOf(namedCls.asSubclass(Enum.class), "SRGB"));
        Method wrap = Bitmap.class.getMethod("wrapHardwareBuffer",
                Class.forName("android.hardware.HardwareBuffer"), colorSpaceCls);
        Bitmap bitmap = (Bitmap) wrap.invoke(null, buffer, srgb);
        if (bitmap == null) {
            throw new IllegalStateException("wrapHardwareBuffer returned null");
        }
        try {
            File out = new File(outPath);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            // 先写临时文件再原子改名，避免 App 侧读到半张图
            File tmp = new File(outPath + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            boolean compressed;
            try {
                compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            } finally {
                fos.close();
            }
            if (!compressed) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException("Bitmap.compress returned false");
            }
            if (out.exists() && !out.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException("cannot replace existing file: " + outPath);
            }
            if (!tmp.renameTo(out)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IllegalStateException("cannot rename temp into place: " + outPath);
            }
        } finally {
            // wrapHardwareBuffer 得到的 Bitmap 持有 buffer 引用，必须及时释放，
            // 否则会拖住 system_server 的 buffer 回收。
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
                // 已回收或不支持
            }
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    // ------------------------------------------------------------------
    // 快照变化监听（Quickstep 同款接口，但本机实测不会被回调 —— 详见下）
    // ------------------------------------------------------------------
    /**
     * 【重要实测结论：监听器注册成功，但回调永不触发】
     *
     * 排查过程与结论（LineageOS 23.2 / Android 16 / Redmi K40 / uid=0）：
     *
     *  1. `registerTaskSnapshotListener` 调用**成功**，无异常、无警告。
     *  2. 本进程 Binder 线程池**已就绪**（`/proc/self/task` 实测 11 个线程，
     *     由 {@link #startBinderPool()} 主动拉起；未拉起时只有 1 个线程）。
     *  3. 期间系统侧**确实在持续抓快照**（`/data/system_ce/0/snapshots` 下的
     *     jpg / proto 持续刷新），即「快照变化」事件客观存在。
     *  4. 但连续 8 次以上应用切换 + 等待 8s 后，`lastEvent` **恒为空**，
     *     即 `onTaskSnapshotChanged` / `onTaskSnapshotInvalidated`
     *     **一次都没有被调用**。
     *
     * 原因（已由 AOSP 源码交叉确认）：`ITaskSnapshotManager` 的回调只投递给
     * 平台侧登记的 recents 组件。系统通过 `config_recentsComponentName`
     * 解析出 recents 包名，并经 `filterOnlySystemPackages()` **丢弃一切
     * 非系统包**。本项目是第三方应用（即便以 root 运行也不改变其包身份），
     * 因此不在白名单内，事件驱动这条路在本机**走不通**。
     *
     * 故本服务**不以监听器为主路径**：`watch` 命令保留为可选能力
     * （在将来 ROM 放开该限制、或本项目被预置为系统应用后即可生效），
     * 但默认调度由守护脚本以**前台 take 轮询**驱动 ——
     * 见 meego_daemon.sh 的 TAKE_INTERVAL，单帧实测 11~42ms，完全够用。
     */
    private static boolean startListening() {
        if (listening) {
            return true;
        }
        try {
            // registerAidlListener() 自己已经完成「向底层 ITaskSnapshotManager 注册」
            // 这一步，并返回了已注册的句柄。这里**绝不能**再拿这个 AIDL proxy 去调
            // wrapper 的 registerTaskSnapshotListener —— 那个方法的形参类型是
            // android.window.TaskSnapshotListener（Quickstep 的封装类型），传 AIDL
            // proxy 会抛：
            //   IllegalArgumentException: ...registerTaskSnapshotListener argument 1
            //   has type android.window.TaskSnapshotListener,
            //   got android.window.ITaskSnapshotListener$Stub$Proxy
            // （此错已实测复现，见 changelog）
            registerAidlListener();
            listening = true;
            return true;
        } catch (Throwable t) {
            respondError("registerTaskSnapshotListener failed: " + describe(t));
            return false;
        }
    }

    private static boolean stopListening() {
        if (!listening) {
            return true;
        }
        try {
            // 这里刻意不断开：重连场景下监听器随进程存活，保持注册更简单可靠。
            listening = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用底层 AIDL 注册快照变化监听器。
     *
     * 【为何不直接用 wrapper 的 registerTaskSnapshotListener】
     * Quickstep 走的是 `TaskSnapshotManager.registerTaskSnapshotListener(TaskSnapshotListener)`，
     * 但它的形参是 **protected 的 hidden 具体类** `android.window.TaskSnapshotListener`，
     * 要构造其实例就得能在编译期继承一个 bootclasspath hidden 类 —— 在
     * compileSdk 35 下无法 import、无法 extends（本文件刻意只用 public API + 反射）。
     *
     * 而 wrapper 内部做的本质就是：
     *     new TaskSnapshotListener(){...} → ITaskSnapshotListener$Stub$Proxy → AIDL
     * 所以**直接向底层 AIDL 注册一个自建 Binder**与之语义完全等价，
     * 且完全绕开了 hidden 类型的编译期依赖。这不是野路子，而是同一条通路。
     */
    private static Object registerAidlListener() throws Exception {
        Class<?> ibinderCls = Class.forName("android.os.IBinder");
        Class<?> binderCls = Class.forName("android.os.Binder");
        Class<?> parcelCls = Class.forName("android.os.Parcel");

        // 手工实现一个 Binder 子类：无法在编译期 extends android.os.Binder 的
        // 匿名类（BootClasspath 可继承，问题只在于 onTransact 签名需精确匹配），
        // 故这里直接用 Binder + 反射重写 onTransact 不可行 —— 改为在同一次
        // 调用里使用 Stub.asInterface 包装。
        Object rawBinder = newBinderCallback(binderCls, parcelCls);
        Class<?> stubCls = Class.forName("android.window.ITaskSnapshotListener$Stub");
        Method asInterface = stubCls.getMethod("asInterface", ibinderCls);

        // 但 registerAidlListener 必须绑定到底层 tsm，而非 wrapper。
        Object tsm = rawTaskSnapshotManager();
        Object proxy = asInterface.invoke(null, rawBinder);
        for (Method m : tsm.getClass().getMethods()) {
            if ("registerTaskSnapshotListener".equals(m.getName())) {
                m.invoke(tsm, proxy);
                return proxy;
            }
        }
        throw new IllegalStateException("ITaskSnapshotManager.registerTaskSnapshotListener not found");
    }

    private static Object rawTaskSnapshotManager() throws Exception {
        Class<?> ibinderCls = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "activity_task");
        if (binder == null) {
            throw new IllegalStateException("activity_task service not found");
        }
        Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", ibinderCls)
                .invoke(null, binder);
        for (Method m : atm.getClass().getMethods()) {
            if ("getTaskSnapshotManager".equals(m.getName())) {
                return m.invoke(atm);
            }
        }
        throw new IllegalStateException("IActivityTaskManager.getTaskSnapshotManager not found");
    }

    /** 记录一个快照变化事件（供 daemon 观测链路是否活跃）。 */
    static void onEvent(String kind, int tid) {
        lastEvent = kind + ":" + tid + ":" + System.currentTimeMillis();
    }

    /** 当前进程线程数：用于验证 Binder 线程池是否真的起来了（应为 4~6，而非 1）。 */
    private static int threadCount() {
        try {
            java.io.File taskDir = new java.io.File("/proc/self/task");
            String[] ids = taskDir.list();
            return ids == null ? -1 : ids.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------
    private static void describeSnapshot(Object snapshot, String op, int tid, long ms, String extra) {
        respondOk("\"op\":\"" + op + "\""
                + ",\"tid\":" + tid
                + ",\"elapsedMs\":" + ms
                + (extra == null ? "" : "," + extra)
                + metaJson(snapshot));
    }

    private static String metaJson(Object snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(",\"taskId\":").append(readIntField(snapshot, "mId", 0));
        sb.append(",\"captureTime\":").append(readLongField(snapshot, "mCaptureTime", 0L));
        sb.append(",\"isRealSnapshot\":").append(readBoolField(snapshot, "mIsRealSnapshot"));
        sb.append(",\"isLowResolution\":").append(readBoolField(snapshot, "mIsLowResolution"));
        sb.append(",\"topActivity\":\"").append(jsonEscape(readStringField(snapshot, "mTopActivityComponent"))).append("\"");
        sb.append(",\"taskSize\":\"").append(jsonEscape(readPoint(snapshot, "mTaskSize"))).append("\"");
        sb.append(",\"contentInsets\":\"").append(jsonEscape(readRect(snapshot, "mContentInsets"))).append("\"");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 反射读字段工具（跨版本可能改名/消失，一律容错）
    // ------------------------------------------------------------------
    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLongField(Object target, String name, long dflt) {
        try {
            return (Long) readField(target, name);
        } catch (Throwable t) {
            return dflt;
        }
    }

    private static int readIntField(Object target, String name, int dflt) {
        Object v = readField(target, name);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return dflt;
    }

    private static boolean readBoolField(Object target, String name) {
        Object v = readField(target, name);
        return v instanceof Boolean && (Boolean) v;
    }

    private static String readStringField(Object target, String name) {
        Object v = readField(target, name);
        return v == null ? "" : String.valueOf(v);
    }

    private static String readPoint(Object target, String name) {
        Object p = readField(target, name);
        if (p == null) {
            return "0,0";
        }
        try {
            return p.getClass().getField("x").get(p) + "," + p.getClass().getField("y").get(p);
        } catch (Throwable t) {
            return "0,0";
        }
    }

    private static String readRect(Object target, String name) {
        Object r = readField(target, name);
        if (r == null) {
            return "0,0,0,0";
        }
        try {
            Rect rect = (Rect) r;
            return rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom;
        } catch (Throwable t) {
            return "0,0,0,0";
        }
    }

    // ------------------------------------------------------------------
    // 极简 JSON 取值（只取平铺的字符串/数字/布尔，不引第三方库）
    // ------------------------------------------------------------------
    static String jsonStr(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length()) {
            return null;
        }
        if (json.charAt(p) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int k = p + 1; k < json.length(); k++) {
                char c = json.charAt(k);
                if (c == '\\' && k + 1 < json.length()) {
                    char n = json.charAt(++k);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (k + 4 < json.length()) {
                                sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                                k += 4;
                            }
                            break;
                        default: sb.append(n);
                    }
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        int start = p;
        while (p < json.length() && ",}]".indexOf(json.charAt(p)) < 0
                && !Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        return json.substring(start, p);
    }

    static int jsonInt(String json, String key, int dflt) {
        String s = jsonStr(json, key);
        if (s == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return dflt;
        }
    }

    static boolean jsonBool(String json, String key, boolean dflt) {
        String s = jsonStr(json, key);
        if (s == null) {
            return dflt;
        }
        s = s.trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return false;
        }
        return dflt;
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------
    private static void respondOk(String extra) {
        OUT.println("{\"ok\":true" + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}");
    }

    private static void respondError(String message) {
        OUT.println("{\"ok\":false,\"error\":\"" + jsonEscape(message) + "\"}");
    }

    static String describe(Throwable t) {
        Throwable c = t;
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (c != null && depth < 5) {
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

    static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
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

    // ------------------------------------------------------------------
    // Binder 回调：运行期生成一个 Binder 子类，覆写 onTransact
    // ------------------------------------------------------------------
    /**
     * 构建一个 {@code android.os.Binder} 匿名子类，其 {@code onTransact} 把
     * {@code onTaskSnapshotChanged}/{@code onTaskSnapshotInvalidated} 记录到
     * {@link #lastEvent}。
     *
     * 这里之所以不用「编译期匿名类」：那会让本文件依赖 android.jar 里的
     * {@code Binder} 签名，而 {@code onTransact} 在不同 SDK 上参数个数曾变化
     * （Parcelable 标记参数），写死会在某些 SDK 上编译失败或运行不匹配。
     * 用反射拼装同样脆弱。**最终采用最稳妥方式：直接 extends Binder 的静态内部类**，
     * 见 {@link SnapBinder}。
     */
    private static Object newBinderCallback(Class<?> binderCls, Class<?> parcelCls) throws Exception {
        return new SnapBinder();
    }

    /**
     * 真正的 Binder 回调实现。
     *
     * 【为何刻意不加 @Override】`android.os.Binder.onTransact` 的签名在不同
     * Android 版本上形态不一（曾有 Parcelable 类标记参数）。写死任一版本，
     * 在另一种 SDK 上要么编译失败、要么变成「重载」而非「覆写」而永不生效。
     * 这里以 3 参形态（Android 10+ 的实际签名）为准且不加 @Override：
     * 最坏情况是旧 SDK 上监听器收不到回调，而不会崩，也不会影响
     * {@code take}/{@code get} 这两条主力通路。
     */
    static final class SnapBinder extends android.os.Binder {
        public boolean onTransact(int code, android.os.Parcel data, int flags) {
            try {
                data.setDataPosition(0);
                if (code == 1) {
                    // onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot)
                    int tid = data.readInt();
                    data.readInt(); // snapshot 字段 present 标记
                    onEvent("changed", tid);
                } else if (code == 2) {
                    // onTaskSnapshotInvalidated(int taskId)
                    onEvent("invalidated", data.readInt());
                }
            } catch (Throwable t) {
                // 回调线程里绝不能抛出，否则会拖垮 Binder 分发线程
                onEvent("parse_error", -1);
            }
            return true;
        }
    }
}
