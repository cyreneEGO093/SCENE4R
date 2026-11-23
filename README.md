# SCENE4R 与双调度器适配项目 (Uperf-R / FAS-RS · Android 16+)

> [!CAUTION]
> **重要声明（Vibe Coding 开发 · 无担保 · 无维护承诺）**
>
> - **开发方式**：本项目**完全采用 Vibe Coding（AI 辅助编码）方式开发**，公开源码仅供有相同需求的用户与开发者参考和自用。
> - **无任何保证**：作者不对本项目的**可靠性、准确性、安全性、兼容性**或实际运行效果作任何明示或暗示的保证。
> - **不承诺维护与支持**：本项目**不承诺**后续更新、问题修复或回复 Issue / Pull Request。
> - **风险自担**：应用与模块涉及 Root 权限、系统调度器、进程/内存控制及 eBPF 挂载，存在系统异常、数据丢失或设备无法启动等风险；使用前请自行充分评估与备份，**所有使用风险与后果均由使用者自行承担**。
> - **验证与兼容边界**：任何文档或 Release 中涉及构建参数与兼容性的说明，均不构成普遍适用性、质量保证、兼容性承诺或维护义务。

本项目基于开源仓库 `helloklf/vtools`（`scene4` 分支）、`yc9559/uperf`（`dev-22.09.04`）、下游 `yinwanxi/Uperf-Game-Turbo` 以及 `shadow3aaa/fas-rs`（`master` 提交 `09ba876fcdf378004305f30243e979c7cea346dc`，GNU GPL v3.0）完成现代化改造、安全收敛、双调度器热切换与参数微调适配，应用显示名称统一为 **SCENE4R**，内置双调度引擎为 **Uperf-R** 与 **FAS-RS (SCENE4R modify)**。

## 目录与最终可安装产物

- `dist/uperf-r.zip` 与 `dist/SHA256SUMS.txt`：可直接在 Magisk / KernelSU / APatch 刷入的 Uperf-R 调度模块（1,933,161 bytes，144 个条目，`SHA-256: 0e25a9ffd1cf40d07f337a0b9d418832926313d0ded379f9a3776ce4303b8e2f`）。
- `dist/fas-rs-r.zip` 与 `dist/fas-rs-r.sha256`：基于 `shadow3aaa/fas-rs`（GNU GPL v3.0）源码编译并完成安全边界收敛与 Android 16 eBPF 适配的 **FAS-RS (SCENE4R modify)** `v4.9.1-r1` 模块（1,970,463 bytes，9 个条目，`SHA-256: 962c04440babc002fe8c26e249519f964b015065d498a141e599dadeb6652082`）；内含 stripped `aarch64` 可执行文件 `fas-rs-module/bin/fas-rs`（1,923,624 bytes，`SHA-256: 11decc0adf3bd0a0794c00d5052bfa31c50aa282301d935f1f9cd12883c535d9`，`ELF64 AArch64`，4 个 `PT_LOAD` 段均满足 `0x4000` / 16 KiB 页对齐，仅依赖 `libbinder_ndk.so` / `libdl.so` / `libc.so`）。
- `dist/scene-swap-controller.zip` 与 `dist/scene-swap-controller.sha256`：内置开源（GNU GPL v3.0）SWAP 控制器模块 `v4.0.0`（`versionCode=4000`，29,396 bytes，11 个条目，`SHA-256: 4505410dace3792d0756de923b318fd21a5c09ebd01d9916dde520252eaac449`），其中 `scene_swap_service.dex`（18,276 bytes，`SHA-256: e8d390c1c8f70d877f8f8cc9215d462c18c6b1dc419fdaddb9874d638f81046f`）由源码直接编译生成，适配 Android 16 / cgroup v2 且不覆写高通 `perfconfigstore.xml` 与 LMKD 参数。
- `dist/scene4r-debug.apk` 与 `dist/scene4r-debug.sha256`：使用 `compileSdk 36` / `targetSdk 36` 编译并通过 `zipalign -c -P 16 4` 与 `apksigner` 校验的 Debug 签名可安装 APK（17,976,105 bytes，`SHA-256: c7c4fa2b15a20c208ba7b642f39c64623d8a56d6d2043512e8a015a8a46af288`，内置同步上述 `assets/uperf/uperf-r.zip`、`assets/fas-rs/fas-rs-r.zip` 与 `assets/swap/scene-swap-controller.zip`）。
- `uperf-r-module/`：经过安全收敛与原厂温控保留改造的 Uperf-R 模块源码。
- `fas-rs-src/` 与 `fas-rs-module/`：安全收敛与 Android 16 适配后的 `fas-rs`（含 GPL-3.0 §5a 修改声明与 `vendor/frame-analyzer/` 源码）及打包模块目录。
- `swap-controller-module/`：改造后的 GPL-3.0 `scene_swap_controller` 源码工程（含 `src/Main.java` 与 Android 16 cgroup v2 适配脚本）。
- `build_module.py` / `build_fas_rs_module.py` / `build_swap_module.py`：可复现模块编译打包与摘要校验脚本（构建时分别自动同步至 `scene4-modern/app/src/main/assets/{uperf,fas-rs,swap}/`；`build_swap_module.py` 从 `JAVA_HOME`、`ANDROID_HOME` / `ANDROID_SDK_ROOT` 自动解析工具链）。
- `scene4-modern/`：面向 Android 16+（API 36）与双调度器集成的 SCENE4R 现代化源码工程。
- `LICENSE` 与 `THIRD_PARTY_NOTICES.md`：GNU GPL v3.0 协议全文与各上游组件开源许可、提交基线及二进制来源说明（Git 仓库通过 `.gitignore` 忽略本地 `ref/`、`upstream/`、`dist/*.apk` 与 `dist/*.zip`，二进制产物建议随 GitHub Releases 分发）。

## 核心改动说明

### 1. Android 16+ (API 36) 现代化适配 (`scene4-modern/`)
- **构建与工具链升级**：Gradle 8.11.1、AGP 8.9.1、Kotlin 2.0.21、`compileSdk 36` / `targetSdk 36`、JDK 17、CMake 3.22.1 / NDK `27.2.12479018`；开启 `aidl true` 与 `buildConfig true`，移除 `jcenter()`、`app/omoarea-test.jks`、硬编码 `local.properties` 及已废弃的 `kotlin-android-extensions`，全部 43 个文件迁移至显式类型化视图访问并补全 API 36 可空 `ApplicationInfo?` / `AccessibilityNodeInfo?` / `Intent?` 与标准 `Layout.Alignment`；Native JNI (`native-lib.cpp`) 修复内存泄漏，编译产出的 `libnative-lib.so`（`arm64-v8a` / `armeabi-v7a`）`PT_LOAD` 满足 `0x4000`（16 KiB）对齐；为确保 Android 10-16 `targetSdk 36` 下 BusyBox 可在非 Root 及 `su` 环境直接执行，启用 `packaging.jniLibs.useLegacyPackaging = true` 并将双架构静态 BusyBox 作为 `jniLibs/*/libbusybox.so` 打包（配置 `keepDebugSymbols += ['**/libbusybox.so']` 保留原始二进制与 SHA-256）；Release 签名由环境变量 `SCENE_KEYSTORE_PATH`、`SCENE_KEYSTORE_PASSWORD`、`SCENE_KEY_ALIAS`、`SCENE_KEY_PASSWORD` 提供。
- **Android 16 行为变更适配**：
  - `ActivityBase` 先于 `super.onCreate()` 应用主题，再通过 `enableEdgeToEdge(statusBarStyle, navigationBarStyle)`、`ThemeSwitch.applyWindowBackground()` 与 `WindowInsetsCompat`（含 `ime()` 底部安全边距并返回 `WindowInsetsCompat.CONSUMED`）统一定制状态栏与窗体背景，并为 `activity_addin_online.xml`、`activity_action_page.xml`、`activity_other_settings.xml`、`activity_app_details.xml`、`activity_app_xposed_details.xml` 等 9 个子页面关闭多余的 `android:fitsSystemWindows` 修复顶栏双重状态栏留白；`ActivityStartSplash` 使用 `WindowCompat.setDecorFitsSystemWindows(window, false)` 与刘海/挖孔 insets 处理；`ThemeSwitch` 使用 `WindowInsetsControllerCompat` 替代已废弃的 `systemUiVisibility`，移除 `RenderScript` 并捕获壁纸读取 `SecurityException` 安全降级。
  - 使用 `OnBackPressedDispatcher` 替代 `onBackPressed()` 覆写，并在 `AndroidManifest.xml` 启用 `android:enableOnBackInvokedCallback="true"`。
  - 所有组件显式声明 `android:exported`，支持大屏/折叠屏自由调整大小（`resizeableActivity="true"`，移除强制竖屏）。
  - 所有 `PendingIntent` 补充 `FLAG_IMMUTABLE`；`SceneTileService` 对 Android 14+/16（API 34+）改用 `PendingIntent` 版 `startActivityAndCollapse`；所有动态 `registerReceiver` 显式标记 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`；`AlarmManager` 补充 `canScheduleExactAlarms()` 降级判断。
  - `BootService`、`CompileService`、`SceneTaskIntentService`、`TriggerExecutorService` 迁移至 `Service` + `ServiceCompat.stopForeground` + `ContextCompat.startForegroundService()` + `foregroundServiceType="specialUse"`；`CompileService` 实测 `timeout` / `busybox timeout -t` 可用性并配合协程 `withTimeoutOrNull` 与 `keepShell.tryExit()`。
  - 针对 Android 10–16（`targetSdk 36`）禁止从 `app_data_file`（`files/`）无 Root 执行二进制、Root 被跳过/未授权或升级遗留旧版文件的兼容性问题：打包双架构静态 BusyBox 至 `jniLibs/<abi>/libbusybox.so`（均满足 `0x10000` 对齐且保留原始 SHA-256：`arm64-v8a` 为 `f03b1cda...`，`armeabi-v7a` 为 `29b80832...`）；`Scene.kt` 仅在 `isPrivateBusyboxReady()` 全量校验通过后才将 `files/toolkit` 写入 `ShellExecutor` 的 `PATH`，未通过校验前保持系统路径，待 `Busybox.forceInstall` 完成校验后再发布目录并重置 Root shell 会话；`Busybox.kt` 按 `Process.is64Bit()` 校验 `nativeLibraryDir/libbusybox.so` 摘要后，先在 `.toolkit_stage` 创建字面名称为 `busybox` 的软链探测 `--list`（确保 `argv[0]` 正确分派），校验并原子发布 `files/toolkit` 与 `files/busybox` 后再清理 `.toolkit_old`（`forceInstall` 在后台线程执行，普通探测上限 5 秒，Root 回退使用独立一次性 `ProcessBuilder("su")` 与 8 秒等待上限，超时立即强杀 `su` 子进程并不在调用线程等待 `reader` 锁）；`ToyboxIntaller.kt` 迁出至 `files/toybox/` 并校验常规文件及固定 SHA-256（`380f3464...` / `c299b85b...`，异常回落 `/system/bin/toybox`）；`FileWrite.kt` 使用独占临时文件写完后 `Os.rename` 替换（仅忽略 `ENOENT`），`KeepShell.kt` 先强杀进程再关流并设置锁定超时恢复，`install_busybox.sh` 补充严格失败退出。
- **交互界面与场景模式增强**：
  - **双电芯电流适配、整机实时功率与悬浮窗显示策略**：`DialogElectricityUnit` 与 `ElectricityUnit` 提供“双电芯适配（电流 ×2）”选项（持久化至 `SpfConfig.GLOBAL_SPF_DUAL_CELL`）及整机实时功率计算（`calcPowerWatts` / `formatPowerWatts`，兼容单节电压读数与 `>= 6000 mV` 双串总电压归一化并防止 `-0.00W` 显示），在「概览」电池卡片、电流校准实时预览、Android 16 常驻状态栏通知（`AlwaysNotification`）以及**迷你悬浮窗（`FloatMonitorMini`）**同步展示实时功率（`W`）；同时移除 `FloatMonitorMini` 的轮询电流（`mA`）交替显示，仅保留电池温度（`°C`）与功率（`W`）；按当前收敛策略，`FloatMonitor` 与 `FloatPowercfgSelector` 保持不显示功率。
  - **界面精简、进阶功能重组、双调度器切换与参数微调（SCENE4R）**：
    - 移除 `ActivityMain` 底部“打赏”标签，将完整「性能配置」面板置于「概览」最顶部，统一 Uperf 配置源名称为 `Uperf-R`，并移除对 Uperf-R 无效的「概览 -> 动态响应 - 严格模式」开关（`AppSwitchHandler` 仅在模式变化时写入切换）。
    - 将「功能 -> 进阶功能」的「杂项设置」合并进统一的「附加功能」（`进阶功能` 保留「应用管理」「附加功能」「Magisk助手」3 项入口；`ActivityAddin` 保留兼容跳转，并由 `ActionPage._openPage()` 直接分派 `native_dpi`、`native_fullscreen`、`native_wifi`、`native_mac_1`、`native_mac_2`、`native_deviceinfo`、`native_dex_compile` 原生对话框/功能页）；重组 `assets/kr-script/more.xml` 为 6 个主题分组子目录（`显示与界面`、`网络与设备`、`性能与存储`、`电池与电源`、`应用程序`、`开发者与工具`）及按设备条件显示的 `MIUI专属` / `MTK/天玑专属`，同时彻底移除 `应用程序` 中 QQ 相关脚本（`qq_style_fix.sh` / `qq_style_set.sh`）及菜单项。
    - 精简「功能」页布局，移除「交流反馈」（`nav_qq` / `nav_share`）、「辅助功能」（`nav_gesture` / `nav_filter`）、「进阶功能 -> 备份还原」（`nav_img`）及已禁用的「MIUI温控」（`nav_miui_thermal`）；同步移除因安全收敛不可用的入口（`settings_disable_selinux` 开关、`extreme_performance` / `ThermalDisguise` 伪装卡片、`ActivityCpuControl` 旧版 `msm_thermal` 开关及 `ActivityCpuModes` 旧版在线脚本跳转）。
    - **双调度器手动互斥切换与细微参数微调（`Uperf-R` ↔ `FAS-RS`）**：在 `dialog_powercfg_source.xml`、`ModeSwitcher`、`SchedulerUiHelper`、`FragmentHome` 与 `ActivityCpuModes` 集成 **Uperf-R** 与 **FAS-RS (SCENE4R modify)** 双卡片，严格校验 `/proc/$pid/exe`（含覆盖升级后内核附加 `(deleted)` 后缀的旧实例清理）及模块 `disable` 状态，支持免重启一键停用一方并启动另一方；新增 `ActivitySchedulerTuning` 图形调参页（可从配置源弹窗或长按概览模式卡 / 点击「CPU 控制」模式卡直达），支持：
      - **Uperf-R（`uperf.json`）**：编辑全局 `hintDuration` / `input` / `sfanalysis` 及各预设模式（`powersave` / `balance` / `performance` / `fast` / `crazy`）下的场景（`*` / `idle` / `touch` / `trigger` / `gesture` / `junk` / `switch`）数值与开关；锁定 `switchInode` / `perapp` / `sysfs.knob` 不可变边界；使用严格 UTF-8 规范序列化器（禁止 `\uXXXX`）并经过 `validate_config.awk` 校验后原子写入 `0600`（首次修改自动备份 `uperf.json.orig` 支持一键恢复默认并重启守护进程生效）。
      - **FAS-RS（`games.toml`）**：编辑 `[config]`（`keep_std`、`scene_game_list`）、各模式 `margin_fps` / `core_temp_thresh`（含 `disabled` 开关）及 `[game_list]`（支持从已安装应用列表选取或手动输入包名并兼容 `"auto"` / 固定 / 多挡帧率），保存前由 `fas-rs validate` 二进制校验并原子替换 `0600`，触发运行中的 `fas-rs` 通过 `inotify` 无感热重载，并支持一键恢复默认配置。
  - **夜间模式与跟随系统（`ThemeSwitch` / `ActivityOtherSettings`）**：新增 `SpfConfig.GLOBAL_SPF_NIGHT_MODE_POLICY` 三态策略（`跟随系统`、`浅色模式`、`夜间模式`），优先通过 `Resources.getSystem().configuration` 读取系统深色状态并在 `onResume` / `onConfigurationChanged` 自动重建对齐主题；将 `ThemeSwitch.syncDefaultNightMode` 移入 `Scene.onCreate()` 并补全深色模式下 `#000000` 窗口背景与浅/深色状态栏图标对比度。
  - **SWAP 数值直接输入与内置离线控制器模块（`ActivitySwap` / `SwapModuleUtils` / `swap-controller-module/`）**：
    - 为「创建 Swapfile」「调整 ZRAM」以及「VM 参数」（`swappiness`、`extra_free_kbytes`、`watermark_scale_factor`）全部滑块增加双向同步数字 `EditText`、`SeekBar` 联动与范围校验 Toast，并将 `swappiness` 支持范围扩展至 `0~200`。
    - 移除原外链 `https://vtools.oss-cn-beijing.aliyuncs.com/addin/swap-controller-3.3.3.zip` 下载依赖，改用随 APK 离线打包的开源 GPL-3.0 `scene_swap_controller v4.0.0`（`versionCode=4000`），通过 `ksud` / `apd` / `magisk` 一键直装或升级并识别“重启生效”过渡态。
    - `swap-controller-module` 重写改造要点：`Main.java` 使用 `javac --release 8` + `d8 --min-api 26` 编译为 `scene_swap_service.dex`，新增 Linux 5.x/6.x **cgroup v2（`/sys/fs/cgroup/apps/<uid>/<pid>/memory.reclaim`）** 后台回收后端（优先读 `oom_score_adj`，限定每次单进程 25% `[4 MiB..64 MiB]` 回收且不主动 `kill -9`，OOM 交由系统原生 PSI `lmkd`；并保留旧版 cgroup v1 `/proc/<pid>/reclaim` 路径及 `--self-test` 自检参数）；`scene_swap_module.sh` 与 `scene_swap_extra.sh` 移除 `/dev/stune` 无效写入、移除对 `system/vendor/etc/perf/perfconfigstore.xml` 与 LMKD/危险 VM 参数（如 `panic_on_oom=1`）的侵入式覆写、仅在 `zram=true` 时才重置 ZRAM、仅写入内核实际存在的 VM 节点（跳过不存在的 `extra_free_kbytes`），并统一将 `/data/swapfile` 与 `/data/swap_config.conf` 权限收敛为 `0600` (`0:0`)。
  - **内置 Xposed 高级设定集成**：移除已失效的外部插件 `com.omarea.vaddin` 下载提示与 AIDL 绑定，在 `AndroidManifest.xml` 启用 `xposedsharedprefs=true`（`xposedminversion=93`），由 `XposedExtension` 直接读写模块配置，并由 `XposedInterface`、`ViewConfig`、`ExcludeRecent`、`WebView`、`AppFreezeInjector` 向目标作用域提供独立 DPI、隐藏最近任务（兼容 Android 10+ `taskId`）、弹性滚动、WebView 调试与应用偏见自动解冻（清理旧版 `initZygote` / 微信 / 系统 UI 脆弱 hook，并在各 hook 点增加独立 `Throwable` 隔离）。
  - **Android 16 常驻通知布局修复**：`AlwaysNotification` 改用 `NotificationCompat.DecoratedCustomViewStyle()` 并同时提供 `customContentView` / `customBigContentView` / `customHeadsUpContentView` 及包含功率信息的摘要标题，确保在 Android 16 折叠与自动展开状态下均可完整显示。
  - **应用级“关闭自动亮度”**：新增 `SceneConfigInfo.disAutoLight`（`SceneConfigStore` `DB_VERSION = 7` 自动迁移 `dis_auto_light` 列），并在通知栏点击悬浮框（`FloatPowercfgSelector`）及应用详情页提供开关；进入/焦点回到目标应用时仅关闭自动亮度（`SCREEN_BRIGHTNESS_MODE = 0`）而不覆盖手动亮度滑块，切出、息屏或切入黑名单应用后恢复此前自动亮度状态。
  - **Root 授权恢复与初始化生命周期修复（`FragmentNotRoot` / `TabIconHelper2` / `CheckRootStatus` / `ActivityMain`）**：
    - `TabIconHelper2` 覆写 `getItemId()` 与 `getItemPosition()`，在 Fragment 类型发生切换（如 `FragmentNotRoot` ↔ `FragmentHome`）时阻止 `FragmentPagerAdapter` 复用旧页面实例；`ActivityMain.onCreate()` 同步清理与当前 Root 状态冲突的残留 Fragment。
    - 移除 `ActivityMain.onPause()` 中“未获取 Root 即调用 `finish()`”的提前销毁逻辑（改为仅在 `onDestroy()` 且 `isFinishing && !lastCheckResult` 时重置 `ActivityStartSplash.finished = false`），避免弹出或跳转至 Root 管理器授权后回到应用卡死，同时保障退出未授权主界面后再次打开可自动重检 Root。
    - `FragmentNotRoot` 点击“重试”时先重置 stale `KeepShell` 会话并通过 `CheckRootStatus` 实测探测（内置线程安全 `AtomicBoolean`、超时弹窗 `dismiss()` 及 `KeepShell` EOF / `EPIPE` 异常防护）；一旦授权成功即跳回 `ActivityStartSplash` 补齐首次风险确认、权限授权与 Root 模式 BusyBox 初始化，`FragmentNav.onResume()` 同步刷新全部 `root` 标记功能入口状态。
  - **App 侧安全收敛**：
  - `SceneFreezeProvider` 校验 `Binder.getCallingUid()` 与 `callingPackage` 归属，仅授权自身 UID 或系统当前默认桌面（`CATEGORY_HOME`），并校验目标包名在受控冻结列表中。
  - `ActivityQuickStart` 与 `FreezeAppShortcutHelper`（Oreo+ 快捷方式与旧版仅限默认桌面定向广播）均绑定每安装随机 `shortcutToken` 并校验目标包名合法性与受控列表；`SceneMode` 执行参数校验与单引号转义。
  - `WebViewInjector` 仅在可信本地页暴露 `KrScriptCore`；`ActionPageOnline` 为本地资产页启用 `blockNetworkLoads = true` 并拦截外部网络导航与子资源加载；`ActivityAddinOnline` 移除远程插件脚本桥接及 `.sh`/`.zip` 自动下载安装并强制 HTTPS。
  - 移除全局关闭 SELinux（`setenforce 0`）逻辑；`ActivityCpuModes` 对 `content://` 配置执行 200 KiB 有界流读取与明确超限报错并弹出 Root 风险确认；`DownloaderReceiver` 先确认 `DownloadManager.STATUS_SUCCESSFUL` 再原子声明待处理任务，`Downloader.saveTaskCompleted` 仅在私有文件写入成功后提交 `completed`（返回 `false` 时释放 claim；外层异常/进程被杀后无自动过期恢复）；`CpuConfigInstaller.verifyOutsideConfigSafe()` 强制校验 `/data/powercfg*.sh` 为 `0:0` 属主、非符号链接且无组/其他写权限，未通过则由 `ModeSwitcher` 拒绝 Root 执行；`UperfManager.switchMode` 严格门控于 `/proc/$pid/exe` 验证通过的 `isReady()`。

### 2. Uperf-R 调度集成与安全收敛 (`uperf-r-module/`)
围绕安全边界与原厂温控保留完成以下收敛：
1. **温控与电池保护保留**：彻底移除 `msm_thermal` 关闭、`killall -9 mi_thermald`、`cpu_limits` 锁定、`vendor.thermal-hal-2-0.mtk` 停用、`battery_oc_protect_stop`、`gpufreq` 限频表清除、`fpsgo/fbt/thrm_*` 禁用、`kgsl` `thermal_pwrlevel`/`throttling` 覆盖、联发科 `PPM` 策略 3/4/5 禁用以及 `platform_special.sh` / `miui_migt.sh` / `libpowercfg.sh` / `system/` / `system.prop`（MTK 系统表覆盖）；从 `e2100.json` 移除 `/sys/devices/platform/xperf/prof/power_limit`；`powercfg_once.sh` 仅保留安全的 `unify_cgroup` 并确保 `exit 0`。
2. **移除无关内嵌二进制包**：完全移除内嵌 `modules/asoulopt.zip` 及其安装逻辑。
3. **运行目录隔离与配置白名单校验**：将运行时配置与状态目录迁移至 Root 专属 `/data/adb/uperf`（`0700`/`0600`）；更新全部 120 份平台 JSON 中的 `switchInode` 与 `perapp` 路径；`script/validate_config.awk` 使用 ASCII `CTRL` 查表替代 `[\x00-\x1f]` 以兼容 BusyBox `awk` POSIX 正则引擎，并与 `build_module.py` 共享一致的**精确 42 个允许的 sysfs/cpuset/proc 节点白名单**、`re.fullmatch` 模式校验与深度/数值检查；`import_config.sh` 与 `setup.sh` 共享 `.import.lock` 与 `mktemp` 排他暂存（`setup.sh` 在调用 BusyBox 前先设置 `0755` 并于 `install_uperf()` 结束前显式释放锁；`customize.sh` 显式导出 `MODPATH`）。
4. **模块冲突处理调整**：移除跨模块删除逻辑；`setup.sh` 仅检测冲突并输出提示。
5. **卸载回滚与预写事务日志**：`initsvc.sh` 兼容首次与重启后 `busybox --install -s` 符号链接，仅依据已校验的 `journal.tsv` 摘要判定托管归属，先备份原文件与原始 `inotify` 参数（严格校验 `stat`、属主/权限与 SHA-256），按 `PENDING` -> `COMMITTED` 更新；`uninstall.sh` 分类校验缺失/常规/非预期对象，核对实时摘要、`0:0:mode`、备份摘要与恢复后字节/元数据，并将外部篡改或异常节点保留于 `journal.unresolved.tsv` 或 `inotify.bak`；`initsvc.sh` 仅在 `uperf_start` 校验真实新启动进程 `/proc/$pid/exe` 后清除 `flag/need_recuser`；`post-fs-data.sh` 先执行 `--rollback-bridge-only` 再决定清理或保留 `rollback_failed` 状态并禁用模块。
6. **安装包结构、一致性与脚本整理**：使用标准 Magisk `module_installer.sh`（调用 `install_module`）；移除 `logcat` 后台录制与旧脚本冗余逻辑；修复 7 份 JSON 重复键（`gtcoop`、`rtusr`）、8 份悬空亲和性引用及默认 `perapp_powermode.txt` 映射（`- powersave`、`* balance`）；`build_module.py` 强制 `ZipInfo.create_system = 3`、LF 换行、固定二进制 SHA-256 校验并同步至 App 资产。

### 3. FAS-RS (SCENE4R modify) 集成、安全收敛与 Android 16 eBPF 适配 (`fas-rs-src/` & `fas-rs-module/`)
基于 `shadow3aaa/fas-rs`（`09ba876f`，GNU GPL v3.0，保留 `LICENSE` 及修改文件 GPL-3.0 §5a 声明）做以下安全收敛与平台适配：
1. **移除共享存储与外部攻击面**：移除 `/sdcard/Android/fas-rs`、`Android/data` 与应用可写目录配置/名单读取，以及高特权全局 `setprop`（移除原 `customize.sh` / `service.sh` 中的渲染/游戏相关全局属性覆写）；所有运行时配置、模式节点（`/data/adb/fas-rs/node/mode`，仅白名单 `"mode"` 单文件并移除 `/dev/fas_rs` 暴露）、挂载掩码（`.mount_masks/`）与日志（`fas_log.txt`）全部隔离在 Root 专属 `/data/adb/fas-rs`（`0700` 目录 / `0600` 文件，`umask 077`，`O_NOFOLLOW` + 1 MiB / 64 B 有界读取与原子替换）。
2. **移除高危 Root LuaJIT 扩展引擎**：从 `Cargo.toml` 移除 `mlua`/LuaJIT 与多余构建依赖，移除 `misc.rs` 及扩展加载逻辑并将扩展钩子收敛为空操作桩（No-op），杜绝扩展脚本注入。
3. **干净退出与原厂温控保护**：新增 `SIGTERM`/`SIGINT` 优雅退出处理器，退出前自动分离 eBPF、恢复频率上下限并按存在性/非符号链接检查卸载 `.mount_masks` 绑定挂载；`file_handler.rs` 严格校验 `/sys/devices/system/cpu/` 与 `/proc/` 路径前缀并拒绝符号链接；不关闭或破坏系统原厂温控服务，仅保留柔和的模式级 `core_temp_thresh` 目标帧率回退；`games.toml` 监听父目录 `CLOSE_WRITE | MOVED_TO | CREATE` 事件支持原子替换后的热重载，并新增 `fas-rs validate <path>` CLI Schema 与范围校验子命令。
4. **Android 16 (API 36) `libgui.so` eBPF Uprobe 适配**：实机排查发现 Android 16 `/system/lib64/libgui.so` 已移除旧版 `Surface::queueBuffer(ANativeWindowBuffer*, ...)` 导出符号；在内置 `fas-rs-src/vendor/frame-analyzer/src/uprobe.rs` 中优先探测并挂载 Android 16 `Surface::queueBuffer(sp<GraphicBuffer>&&, int, SurfaceQueueBufferOutput*)` 符号，同时保留 Android 15、Android ≤14、`hook_queueBuffer` 与 `queueBufferInternal` 回退链，并在 `looper/mod.rs` 添加 30 秒限频挂载失败错误日志（`config/read.rs` 添加重载日志）。

### 4. 原厂温度控制保留
- Uperf-R、FAS-RS 与 Scene4 调度链路（`ModeSwitcher.kt`、`UperfManager.kt`、`FasRsManager.kt`、`ThermalDisguise.kt`、`ActivityMain.kt`、`app/src/main/assets/powercfg/**/*.sh`）已移除或禁用主动关停系统温控服务、BCL、`migt`、`msm_performance` 写入及温控伪装（非骁龙 8 Gen 3 的旧平台配置如 `e2100.json`、`mtd9000.json` 等仍按上游机制保留部分 `scaling_max_freq` / `gpu_max_clock` 等调度频率调节点，未在对应真机实测），并在 `app/src/main/assets/kr-script/miui/` 中禁用系统温控文件替换入口。

### 5. 产物摘要与校验
- `python build_module.py`：生成 `dist/uperf-r.zip`（1,933,161 bytes，144 个条目，`SHA-256: 0e25a9ffd1cf40d07f337a0b9d418832926313d0ded379f9a3776ce4303b8e2f`），全量 120 份 SoC JSON 校验通过并同步至 App 资产。
- `python build_fas_rs_module.py`：交叉编译产物 `fas-rs-module/bin/fas-rs`（1,923,624 bytes，`SHA-256: 11decc0adf3bd0a0794c00d5052bfa31c50aa282301d935f1f9cd12883c535d9`）经 NDK `llvm-readelf` 确认为 `ELF64 AArch64`、4 个 `PT_LOAD` 段均对齐 `0x4000`（16 KiB）；生成 `dist/fas-rs-r.zip`（1,970,463 bytes，9 个条目，`SHA-256: 962c04440babc002fe8c26e249519f964b015065d498a141e599dadeb6652082`）并同步至 App 资产。
- `dist/scene4r-debug.apk`：在 JDK 17 + `android-36` + Build-Tools `35.0.0` + CMake `3.22.1` + NDK `27.2.12479018` 下编译生成并通过 `zipalign -c -P 16 4` 与 `apksigner verify` 校验。

## 构建说明

### 前置环境变量与工具链
- `JAVA_HOME`：JDK 17
- `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）：Android SDK（需安装 `platforms;android-36`、`build-tools;35.0.0`、`cmake;3.22.1` 与 `ndk;27.2.12479018`）
- Python 3（仅用于运行根目录下模块化构建脚本）
- Rust + Android NDK + `bpf-linker`（仅在需要重新交叉编译 `fas-rs-src/` 至 `fas-rs-module/bin/fas-rs` 时需要，并需配置 `-Wl,-z,max-page-size=16384`）

### 构建步骤
1. 模块打包（自动校验并同步至 `scene4-modern/app/src/main/assets/`）：
   - `python build_module.py` -> 生成 `dist/uperf-r.zip` 与 `dist/SHA256SUMS.txt`
   - `python build_fas_rs_module.py` -> 生成 `dist/fas-rs-r.zip` 与 `dist/fas-rs-r.sha256`
   - `python build_swap_module.py` -> 编译 `swap-controller-module/src/Main.java` 并生成 `dist/scene-swap-controller.zip` 与 `dist/scene-swap-controller.sha256`
2. Debug APK 编译：进入 `scene4-modern/` 执行 `./gradlew assembleDebug`（产物位于 `scene4-modern/app/build/outputs/apk/debug/app-debug.apk`，可复制至 `dist/scene4r-debug.apk`）。
3. Release APK 编译：设置环境变量 `SCENE_KEYSTORE_PATH`、`SCENE_KEYSTORE_PASSWORD`、`SCENE_KEY_ALIAS`、`SCENE_KEY_PASSWORD`，并完成剩余 Lint 清理或调整 `lint` 策略后执行 `./gradlew assembleRelease`。
4. 发布建议：`dist/*.apk` 与 `dist/*.zip` 已列入 `.gitignore`，建议在 GitHub Releases 页面上传 APK/模块 ZIP 及对应 `.sha256` / `SHA256SUMS.txt` 校验文件。

## 开源许可与风险提示
- 本仓库整体依据 [GNU General Public License v3.0](LICENSE) 分发；各衍生组件版权、上游提交基线与第三方声明详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- Root 权限与系统性能调校存在造成设备不稳定、发热、功耗异常或配置冲突的风险；请在充分备份关键数据后谨慎使用，切勿在存储重要数据的主力工作设备上盲目尝试未知功能。
