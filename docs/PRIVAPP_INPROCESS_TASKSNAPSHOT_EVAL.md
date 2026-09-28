# 评估：把启动器装成 privapp，在 App 进程内直调 `ITaskSnapshotManager`

> 状态：**评估完成，结论为「不建议按原提案实施」**。本文不含任何代码改动。
> 核查设备：Redmi K40 / LineageOS 23.2 官方 nightly（`23.2-20260923-NIGHTLY-alioth`）/ Android 16 / Magisk 30.7 / `adb root`。
> 文中每条结论都标注了来源：**【实测】**＝本次在目标设备上跑出来；**【源码推导】**＝依据 AOSP 授权逻辑推断，未在设备上端到端验证。

---

## 0. 结论（先看这里）

原提案是：

> 通过 Magisk 模块叠加 `/system_ext/priv-app` + privapp-permissions XML，让启动器自己持有 `MANAGE_ACTIVITY_TASKS` + `READ_FRAME_BUFFER`，从而在 App 进程内直接调用 `ITaskSnapshotManager`。

**该提案的两个关键件（priv-app + 权限白名单 XML）不足以达成目标。**

原因是一句很短的话：**这两个权限根本没有 `privileged` 保护标志**，而 privapp 之所以能免平台签名拿到签名级权限，靠的恰恰是 `privileged` 标志。

**【实测】保护级别：**

| 权限 | protectionLevel | 有 `privileged` 吗 |
| :--- | :--- | :--- |
| `MANAGE_ACTIVITY_TASKS` | `signature\|recents` | ❌ **没有** |
| `READ_FRAME_BUFFER` | `signature\|recents\|role` | ❌ **没有** |
| （对照）`STATUS_BAR` | `signature\|privileged\|recents` | ✅ 有 |

对照项说明这不是我读错了：同族的 `STATUS_BAR` 明确带 `privileged`，而这两个偏偏不带 —— 这是 AOSP 的刻意设计：**这两个权限只给「recents 应用」**，而不是「任意特权应用」。

因此：

- ❌ **privapp + privapp-permissions XML ≠ 拿到权限**（白名单只对带 `privileged` 的权限生效）
- ❌ **平台签名这条路也走不通**：本机是官方 nightly，`ro.build.tags=release-keys`，平台私钥不在我们手上
- ✅ 唯一剩下的放宽机制是 **`recents` 标志**，它要求同时满足「**是系统应用**」+「**是系统配置的 recents 组件**」

结论：提案要成立，需要的是**三件套**（其中最关键的一件——覆盖框架资源配置——原提案没有提到），而它的代价远超收益。

**建议**：不实施。若要降低延迟/复杂度，改用 §6 的「免编码像素直传」，可获得大部分收益且**不需要系统应用**。

---

## 1. 动机：这个方案本可以带来什么

值得承认，这个方向确实有吸引力：

| 现状（root 守护链） | 若 App 内直调 |
| :--- | :--- |
| 常驻 root 服务 `TaskSnapService`（849 行 Java） | 不需要 |
| `meego_daemon.sh` 守护 + FIFO 协议 + 文件轮询 | 不需要 |
| `_sys.jpg` 落盘（~40ms 编码 + 闪存写）→ App 再解码 | `wrapHardwareBuffer` 零拷贝直接绘制 |
| 单帧 ~54ms（取帧 11~42ms + 编码落盘 ~40ms） | 单帧 ~11~42ms |
| 帧龄受轮询节奏限制（~0.3–1s） | 按需即取 |

也就是说，它省掉的是**整条特权管线的复杂度**，而不只是几十毫秒。这正是 LineageOS 里 `com.android.launcher3` 的形态 —— 它就是这么拿到快照的。

所以问题不是「值不值得想」，而是「**在这台设备上能不能做到、代价多大**」。

---

## 2. 前提核查

### 2.1 前提一：privapp + 白名单 XML 足以拿到这两个权限 → ❌ **不成立**

AOSP 的授权逻辑（**【源码推导】**，`PermissionManagerServiceImpl`）大致是：

```java
// 1) 先判断这个签名级权限是否"该给"这个包
if (permission.isSignature() && (shouldGrantPermissionBySignature(pkg, permission)
        || shouldGrantPermissionByProtectionFlags(pkg, ps, permission, ...))) {
    shouldGrantSignaturePermission.add(permissionName);
}
```

- `shouldGrantPermissionBySignature` —— 要求与 `android` 包**同一签名证书**。我们是自签名 APK，**不满足**。
- `shouldGrantPermissionByProtectionFlags` —— 逐条检查放宽标志，其中与本题相关的只有一条：

```java
if (!allowed && bp.isRecents()
        && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                KnownPackages.PACKAGE_RECENTS, UserHandle.USER_SYSTEM),
        pkg.getPackageName())) {
    allowed = true;   // "Special permission for the recents app."
}
```

- 而 privapp 相关的白名单分支，其入口条件是权限**带 `privileged` 标志**。这两个权限不带，所以白名单**不参与**这条授权。

**推论**：privapp 身份与白名单 XML 对这两个权限**不起作用**。把 APK 放进 `priv-app` 只解决「算不算系统应用」，不解决「该不该授予」。

**这一点有一条非常直接的旁证 —— 实测，不依赖任何源码推导**：

```
$ adb shell 'grep -rl "MANAGE_ACTIVITY_TASKS\|READ_FRAME_BUFFER" \
    /system/etc/permissions/ /system_ext/etc/permissions/ /product/etc/permissions/'
（无输出）
```

即：**这两个权限未出现在本 ROM 的任何一个 privapp 白名单里**，而 `com.android.launcher3` **确实持有它们**。

launcher3 自己的白名单条目只有两项，且都不相关：

```xml
<privapp-permissions package="com.android.launcher3">
    <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
    <permission name="android.permission.READ_SYSTEM_GRAMMATICAL_GENDER"/>
</privapp-permissions>
```

**如果这两个权限是靠 privapp 白名单授予的，launcher3 必须出现在名单里。它不在，却拿到了 —— 说明授予走的是另一条路（签名 / recents），白名单 XML 在这件事上是无效件。**

这比源码推导更有说服力：它是**该 ROM 自身的行为事实**。

> 补充一个反直觉点：按同一套逻辑，**非 priv 的系统应用**（放 `/system_ext/app/`）只要满足 recents 条件，同样可能拿到——因为 `!bp.isPrivileged()` 为真时白名单检查被跳过。也就是说**「priv-app」这个位置本身可能都不是必需的**，关键在 recents 身份。此点为【源码推导】，未实测。

### 2.2 前提二：现在可以改 `/system` 了 → ✅ **成立（但要走对路子）**

**【实测】** 设备确实具备改动 `/system` 内容的能力，但**不是**直接写真实分区：

```
/dev/block/dm-1 / ext4 ro,...        ← 真实分区当前只读挂载
su -c id  →  uid=0(root)  context=u:r:magisk:s0   ← Magisk 30.7 提供 root
/data/adb/modules/  →  空            ← 目前没有装任何模块
```

正确机制是 **Magisk 模块叠加（magic mount）**：在挂载命名空间内叠加文件，不写真实分区。
所以「能不能改」已经解决；本节的结论是**「该不该改」**。

### 2.3 一个原提案没提到的关键前提：必须成为 recents 组件

这是整件事的真正难点。

**【实测】** 当前配置：

```
config_recentsComponentName = com.android.launcher3/com.android.quickstep.RecentsActivity
mRecentsComponent            = com.android.launcher3/...RecentsActivity
```

要拿到 `recents` 标志，必须让 `getKnownPackageNames(PACKAGE_RECENTS)` 返回**我们的包名**，即把 `config_recentsComponentName` 改成指向本应用。这需要覆盖 **framework-res 的资源配置**。

**好消息（推翻了我最初的判断）**：这一步**技术上可行**。

最初的判断依据是 `aapt2 dump overlayable framework-res.apk` **无任何输出**，我一度以为该资源不可覆盖。但随后查到：

```
$ cmd overlay list android
[x] android.overlay.common          ← 目标 android，已启用
    mTargetPackageName.....: android
    mTargetOverlayableName.: null   ← 无 overlayable 策略约束
```

**【实测】** framework-res **可以被自由覆盖**。原因是 AOSP 的 `<overlayable>` 是**选择性加入**机制：目标不声明 `<overlayable>` 时就**没有策略限制**，`mTargetOverlayableName` 为 `null` 正说明这一点。（我最初的推断方向错了，此处已纠正并保留记录。）

**坏消息**：虽然能改，但改的代价见 §4。

---

## 3. 修正后的可行路径（三件套）

如果一定要做，最小可行集合是：

| # | 组件 | 作用 | 原提案是否有 |
| :-- | :--- | :--- | :--- |
| 1 | APK 放进 `/system_ext/priv-app/`（或 `/system_ext/app/`） | 让 `filterOnlySystemPackages()` **不把我们的包丢掉** | ✅ 有 |
| 2 | **静态 RRO**（放 `/product/overlay/`）覆盖 `config_recentsComponentName` → 本应用 | 让 `isRecents()` 成立 —— **这是真正的授权开关** | ❌ **原提案没有** |
| 3 | privapp-permissions XML | 对这两个权限**不起作用**；仅当还想拿别的 `signature\|privileged` 权限时才需要 | ✅ 有，但**打错了靶** |

**【源码推导】** 三者齐备后，包会经「系统应用 + recents 组件」路径获得 `recents` 放宽，从而 `shouldGrantSignaturePermission` 命中，而白名单检查因权限非 `privileged` 而被跳过，最终授予。

> ⚠️ **这条链路是源码推导，尚未在本设备端到端验证。** 见 §7 的廉价验证方案 —— 建议**先花 30 分钟验证，再决定是否投入**。

**另外还需要**：`config_recentsComponentName` 期望一个能处理 recents 的 **Activity**。本项目的多任务屏是 Launcher 内部的第三个 Pager 页，**没有独立 Activity**，因此还要新增一个 `RecentsActivity` 并让它与现有三屏逻辑共存 —— 这是原提案完全未涉及的工作量。

---

## 4. 成本与风险

| 维度 | 影响 |
| :--- | :--- |
| **迭代方式** | 变成系统应用后，**不能再 `adb install` 迭代**：每次改代码都要重建 Magisk 模块（或至少重刷 APK 到 `/system_ext`）并重启。对本项目「改一行→跑一遍」的开发节奏是**实质性倒退** |
| **框架覆盖** | 覆盖 `config_recentsComponentName` 意味着**本应用成为系统级 recents 提供者**。系统手势、SystemUI 的 recents 调用都会路由过来，行为面显著扩大 |
| **ROM 升级** | 静态 RRO 与 priv-app 都要随 ROM 版本维护；官方 nightly 频繁更新，模块可能需要反复适配 |
| **模块维护** | 需要 `module.prop` / 版本 / 更新元数据一整套（现有 `service.d` 钩子刻意规避了这些） |
| **安全面** | 从「一个 root 脚本 + 一个只做取帧的常驻服务」扩大到「把启动器变成系统组件」。当前架构的特权面收敛得很好，这一步会明显放大它 |
| **不可逆性** | 一旦走这条路，回退要拆模块 + 恢复 recents 配置，且期间设备可能处于 recents 不可用状态 |

---

## 5. 收益复核：值不值这个价

回到 §1 的收益表，逐条对照现实：

| 声称的收益 | 现实 |
| :--- | :--- |
| 省掉整条守护链 | ✅ 成立，但当前守护链**已经工作稳定**（8 步部署全绿、自愈 ~2s、诊断五项齐全） |
| 单帧 54ms → 11~42ms | ⚠️ **大部分可以用更便宜的方式拿到**（见 §6），无需系统应用 |
| 帧龄不受轮询限制 | ✅ 成立，但当前 ~1s 的帧龄对「划出时看到刚看过的画面」这个体验目标**已经够用**；且划出瞬间走的是 `getTaskSnapshot`（系统在任务变不可见那刻抓的最终帧），并不受轮询节奏影响 |

**核心判断**：这条路的收益主要是**架构优雅**，而不是**用户体验**。用一个「不能再 `adb install`、要维护框架覆盖、特权面放大」的代价去换架构优雅，对当前阶段（launcher 功能仍在快速演进）不划算。

---

## 6. 更划算的替代方案：免编码像素直传

如果真正的痛点是「54ms 里有 40ms 花在 JPEG 编码 + 文件往返」，有一条**不需要系统应用、不需要动 `/system`** 的路：

```
现状：  取帧 11~42ms → Bitmap → JPEG 编码 ~40ms → 写盘 → App 解码 ~15ms
替代：  取帧 11~42ms → 像素拷进共享内存（memfd/ashmem，~5~10ms）→ 传 FD → App mmap
```

- 守护进程用 `Os.memfd_create`（API 30+ 公开 API）建一块共享内存，把 `wrapHardwareBuffer` 得到的像素 `copyPixelsFromBuffer` 写进去，通过已有的 FIFO/命令通道把 **FD** 交给 App（Unix domain socket 的 `SCM_RIGHTS`）
- App 侧 `mmap` 后 `Bitmap.copyPixelsFromBuffer`，或直接以 `ByteBuffer` 构造位图
- 预期把落盘那一段从 ~55ms 降到 ~10ms，**且完全消除每次取帧的闪存写**（顺带解决 §4.10.14 记录的写入量取舍）

**风险点**（需先验证）：App（`untrusted_app`）能否接收 root 进程传来的 FD，受 SELinux 约束；可能需要把通道放在 App 私有目录下用文件而非 FD。这是一次**局部 spike**，不触碰系统分区。

**建议优先级**：`免编码像素直传` > `维持现状` >> `privapp 直调`。

---

## 7. 若仍想推进：先做廉价验证（30 分钟）

在投入 Magisk 模块之前，可以先花很小的代价验证 §3 的核心假设 —— **「系统应用 + recents 组件」能否真的拿到这两个权限**。步骤：

1. 用 `aapt2` + `d8` + `apksigner` 造一个最小 APK（仅声明两个权限 + 打印 `checkSelfPermission` 结果），与本次评估中验证权限链时用的是同一套路
2. 造一个静态 RRO APK，`config_recentsComponentName` 指向该测试 APK 的组件
3. 用一个只含这两项的 Magisk 模块把它们放进 `/system_ext/priv-app/` 与 `/product/overlay/`
4. 重启，读日志确认权限是否真的 granted

**判定**：
- 若 granted → §3 的链路成立，再评估是否值得为它付 §4 的代价
- 若未 granted → 说明还有未识别的门槛，**整个方向可以干脆放弃**，避免白做

> 这一步的产出只有「能/不能」一个比特，却决定后面是否要投入模块开发与框架覆盖，**性价比极高**。

---

## 8. 最终建议

1. **不按原提案实施**。原提案的两件（priv-app + 白名单 XML）对这两个权限不起作用，是打错了靶；真正的开关是覆盖 `config_recentsComponentName`，而它的代价（系统应用 + 框架覆盖 + 失去 `adb install` 迭代）远超收益。
2. **若要降延迟**，改做 §6 的免编码像素直传。收益接近、风险低一个量级、不动系统分区、保留快速迭代。
3. **若对 privapp 直调仍有执念**，先做 §7 的廉价验证，用 30 分钟换一个确定结论，再决定是否投入。
4. **保留本文档作为留档**：§2.1 的「这两个权限不带 `privileged`」是本项目后续任何人重提此方案时的第一道拦阻，值得长期保留。

---

## 附录 A：本次核查用到的命令与原始输出

```bash
# 1) 权限保护级别（结论的关键）
adb shell dumpsys package permissions | grep -A3 'Permission \[android.permission.MANAGE_ACTIVITY_TASKS\]'
#   prot=signature|recents                      ← 无 privileged
adb shell dumpsys package permissions | grep -A3 'Permission \[android.permission.READ_FRAME_BUFFER\]'
#   prot=signature|recents|role                 ← 无 privileged
adb shell dumpsys package permissions | grep -A3 'Permission \[android.permission.STATUS_BAR\]'
#   prot=signature|privileged|recents           ← 对照组，有 privileged

# 2) ROM 身份与签名（平台签名路线的可行性）
adb shell getprop ro.build.flavor            # lineage_alioth-userdebug
adb shell getprop ro.lineage.version         # 23.2-20260923-NIGHTLY-alioth
adb shell getprop ro.build.tags              # release-keys  ← 平台私钥不可得

# 3) root 与模块现状
adb shell 'magisk -V'                        # 30700
adb shell 'su -c id'                         # uid=0(root) context=u:r:magisk:s0
adb shell 'ls /data/adb/modules/'            # 空

# 4) 真实分区挂载状态
adb shell 'head -3 /proc/mounts'             # /dev/block/dm-1 / ext4 ro,...

# 5) recents 组件配置
adb shell 'cmd overlay lookup android android:string/config_recentsComponentName'
#   com.android.launcher3/com.android.quickstep.RecentsActivity

# 6) framework-res 是否可覆盖（最初判断错误的纠正点）
aapt2 dump overlayable framework-res.apk     # 无输出 → 目标未声明 <overlayable>
adb shell 'dumpsys overlay | grep -A4 "android.overlay.common"'
#   mTargetPackageName.....: android
#   mTargetOverlayableName.: null            ← 无策略约束 ⇒ 可自由覆盖

# 7) 现有持有人（全部为平台签名系统应用）
for p in $(adb shell pm list packages -s | sed 's/package://'); do ...; done
#   com.android.shell / com.android.systemui / com.android.launcher3
```

## 附录 B：本文档中「未验证」的部分

诚实标注，避免被当作既成结论：

- §2.1 的授权链路是**源码推导**。设备上只有 3 个平台签名系统应用持有这两个权限，无法据此隔离出 `recents` 放宽机制的独立作用。
- §2.1 末尾「非 priv 系统应用也可能拿到」为**同源推导**，未实测。
- §3 的三件套链路**未端到端验证** —— 这正是 §7 建议先做廉价验证的原因。
