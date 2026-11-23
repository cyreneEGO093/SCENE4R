/*
 * SCENE4R SWAP 控制器后台内存回收守护程序
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) helloklf (vtools / Scene)
 * Modified 2026 for SCENE4R (Android 12-16 cgroup v2 memory.reclaim 适配与安全加固):
 *   1. 新增 Linux 5.x/6.x cgroup v2 (/sys/fs/cgroup/apps/<uid>/<pid>/memory.reclaim) 回收后端；
 *      在 cgroup v2 模式下不再调用 kill -9 强杀进程，OOM 终止完全交由系统原生 PSI lmkd 处理。
 *   2. 修复上游 readAllText 定长 20KiB 缓冲区及 read()==-1 潜在越界，改用 try-with-resources 安全读取。
 *   3. 优先读取标准 /proc/<pid>/oom_score_adj（回退兼容旧内核 /proc/<pid>/oom_adj）。
 *   4. 若内核既不支持 cgroup v2 memory.reclaim 也不支持旧版 /proc/<pid>/reclaim，安全退出而不盲目杀后台。
 *   5. 新增 --self-test 只读自检参数，用于验证后端检测与 cgroup v2 路径解析而不修改系统状态。
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static final long MIN_RECLAIM_CGROUP_BYTES = 8L * 1024L * 1024L;
    private static final long MIN_RECLAIM_TARGET_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_RECLAIM_TARGET_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_RECLAIM_PASS_TARGETS = 6;
    private static final int IDLE_OOM_SCORE_THRESHOLD = 700;

    private static double critical = 0.17;
    private static double high = 0.23;
    private static double middle = 0.25;

    private static final File F_TOP_PROCS = new File("/dev/cpuset/top-app/cgroup.procs");
    private static final File F_FG_PROCS = new File("/dev/cpuset/foreground/cgroup.procs");
    private static final File F_BG_PROCS = new File("/dev/cpuset/background/cgroup.procs");

    private static final File CGROUP_V2_ROOT = new File("/sys/fs/cgroup");
    private static final File CGROUP_V2_CONTROLLERS = new File("/sys/fs/cgroup/cgroup.controllers");
    private static final File CGROUP_V2_RECLAIM = new File("/sys/fs/cgroup/memory.reclaim");

    enum Backend {
        CGROUP_V2("cgroup_v2"),
        CGROUP_V1("cgroup_v1"),
        UNSUPPORTED("unsupported");

        final String id;

        Backend(String id) {
            this.id = id;
        }
    }

    static class MemInfo {
        public long SwapFree;
        public long SwapTotal;
        public long MemTotal;
        public long MemAvailable;
        public long SwapCached;

        public double getMemFreeRatio() {
            if (MemTotal > 0 && MemAvailable > 0) {
                return (0.0 + MemAvailable + SwapCached) / MemTotal;
            }
            return 0.0;
        }

        public double getMemAbsFreeRatio() {
            if (MemTotal > 0 && MemAvailable > 0) {
                return (0.0 + MemAvailable) / MemTotal;
            }
            return 0.0;
        }

        public double getSwapCacheRatio() {
            if (MemTotal > 0 && SwapCached > 0) {
                return (0.0 + SwapCached) / MemTotal;
            }
            return 0.0;
        }
    }

    static class ReclaimReason {
        static final int REASON_MEMORY_WATCH = 1;
        static final int REASON_APP_WATCH = 2;
        static final int REASON_FOREGROUND_WATCH = 3;
        int reason = REASON_MEMORY_WATCH;
    }

    private static final ReclaimReason reclaimReason = new ReclaimReason();

    static class ReclaimCandidate {
        String pid;
        int oomScoreAdj;
        File cgroupDir;
        File reclaimFile;
        long memoryCurrentBytes;
        long targetBytes;
    }

    static class WriteBack {
        private final File kernelReference = new File("/sys/block/zram0/writeback");

        void writeIdle() {
            if (kernelReference.exists()) {
                writeFileQuiet("idle", kernelReference);
            }
        }
    }

    private static String readAllText(File file) {
        if (file == null || !file.exists()) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return "";
        }
    }

    private static boolean writeFileQuiet(String content, File file) {
        if (file == null || content == null) {
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            fos.write(bytes);
            fos.flush();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isNumericPid(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int readOomScoreAdj(String pid) {
        if (!isNumericPid(pid)) {
            return -1000;
        }
        String scoreText = readAllText(new File("/proc/" + pid + "/oom_score_adj"));
        if (!scoreText.isEmpty()) {
            long val = parseLongSafe(scoreText, Long.MIN_VALUE);
            if (val != Long.MIN_VALUE) {
                return (int) val;
            }
        }
        String legacyText = readAllText(new File("/proc/" + pid + "/oom_adj"));
        if (!legacyText.isEmpty()) {
            long legacy = parseLongSafe(legacyText, Long.MIN_VALUE);
            if (legacy != Long.MIN_VALUE) {
                return (int) (legacy >= 0 ? (legacy * 1000L) / 15L : (legacy * 1000L) / 17L);
            }
        }
        return -1000;
    }

    private static Set<String> readPidSet(File file) {
        Set<String> set = new HashSet<>();
        String content = readAllText(file);
        if (content.isEmpty()) {
            return set;
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String pid = line.trim();
            if (isNumericPid(pid)) {
                set.add(pid);
            }
        }
        return set;
    }

    private static long memInfoRowValue(String row) {
        int colon = row.indexOf(':');
        if (colon < 0) {
            return 0L;
        }
        String rest = row.substring(colon + 1).trim();
        int space = rest.indexOf(' ');
        String num = (space > 0) ? rest.substring(0, space) : rest;
        return parseLongSafe(num, 0L);
    }

    private static MemInfo getMemInfo() {
        MemInfo memInfo = new MemInfo();
        String[] rows = readAllText(new File("/proc/meminfo")).split("\n");
        for (String row : rows) {
            if (row.startsWith("MemTotal:")) {
                memInfo.MemTotal = memInfoRowValue(row);
            } else if (row.startsWith("MemAvailable:")) {
                memInfo.MemAvailable = memInfoRowValue(row);
            } else if (row.startsWith("SwapTotal:")) {
                memInfo.SwapTotal = memInfoRowValue(row);
            } else if (row.startsWith("SwapFree:")) {
                memInfo.SwapFree = memInfoRowValue(row);
            } else if (row.startsWith("SwapCached:")) {
                memInfo.SwapCached = memInfoRowValue(row);
            }
        }
        return memInfo;
    }

    private static int getSwapFreeMB() {
        return (int) (getMemInfo().SwapFree / 1024L);
    }

    private static WriteBack detectWriteBack() {
        boolean zramEnabled = readAllText(new File("/proc/swaps")).contains("/zram0");
        if (!zramEnabled) {
            return null;
        }
        File fileBD = new File("/sys/block/zram0/backing_dev");
        String backingDev = fileBD.exists() ? readAllText(fileBD) : "";
        boolean bdConfigured = !backingDev.isEmpty() && !"none".equals(backingDev);
        return bdConfigured ? new WriteBack() : null;
    }

    private static File resolveLegacyMemcgDir() {
        File sysMem = new File("/sys/fs/cgroup/memory");
        if (sysMem.isDirectory()) {
            return sysMem;
        }
        File devMem = new File("/dev/memcg");
        if (devMem.isDirectory()) {
            return devMem;
        }
        return null;
    }

    private static Backend detectBackend() {
        if (CGROUP_V2_CONTROLLERS.isFile() && CGROUP_V2_RECLAIM.exists()) {
            String controllers = readAllText(CGROUP_V2_CONTROLLERS);
            if (!controllers.isEmpty() && Arrays.asList(controllers.split("\\s+")).contains("memory")) {
                return Backend.CGROUP_V2;
            }
        }
        File legacyMemcg = resolveLegacyMemcgDir();
        if (legacyMemcg != null
                && new File(legacyMemcg, "scene_active/cgroup.procs").exists()
                && new File(legacyMemcg, "scene_idle/cgroup.procs").exists()
                && F_TOP_PROCS.exists()
                && F_FG_PROCS.exists()
                && F_BG_PROCS.exists()
                && new File("/proc/1/reclaim").exists()) {
            return Backend.CGROUP_V1;
        }
        return Backend.UNSUPPORTED;
    }

    private static String extractAppCgroupV2Path(String pid) {
        String cgroupText = readAllText(new File("/proc/" + pid + "/cgroup"));
        if (cgroupText.isEmpty()) {
            return null;
        }
        String[] lines = cgroupText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("0::")) {
                String rel = trimmed.substring(3).trim();
                if (rel.startsWith("/apps/") && !rel.contains("..") && !rel.contains("\\")) {
                    return rel;
                }
                return null;
            }
        }
        return null;
    }

    private static long computeTargetReclaimBytes(long currentBytes) {
        long quarter = currentBytes / 4L;
        if (quarter < MIN_RECLAIM_TARGET_BYTES) {
            return MIN_RECLAIM_TARGET_BYTES;
        }
        if (quarter > MAX_RECLAIM_TARGET_BYTES) {
            return MAX_RECLAIM_TARGET_BYTES;
        }
        return quarter;
    }

    private static List<ReclaimCandidate> collectCgroupV2Candidates() {
        Set<String> topPids = readPidSet(F_TOP_PROCS);
        Set<String> bgPids = readPidSet(F_BG_PROCS);
        Map<String, ReclaimCandidate> byCgroup = new LinkedHashMap<>();

        for (String pid : bgPids) {
            if (topPids.contains(pid)) {
                continue;
            }
            int oomScoreAdj = readOomScoreAdj(pid);
            if (oomScoreAdj < IDLE_OOM_SCORE_THRESHOLD) {
                continue;
            }
            String relPath = extractAppCgroupV2Path(pid);
            if (relPath == null) {
                continue;
            }
            File cgDir = new File(CGROUP_V2_ROOT, relPath.substring(1));
            File reclaimFile = new File(cgDir, "memory.reclaim");
            File currentFile = new File(cgDir, "memory.current");
            if (!reclaimFile.exists() || !currentFile.exists()) {
                continue;
            }
            long currentBytes = parseLongSafe(readAllText(currentFile), 0L);
            if (currentBytes < MIN_RECLAIM_CGROUP_BYTES) {
                continue;
            }
            String key = cgDir.getPath();
            ReclaimCandidate existing = byCgroup.get(key);
            if (existing == null || oomScoreAdj > existing.oomScoreAdj) {
                ReclaimCandidate c = new ReclaimCandidate();
                c.pid = pid;
                c.oomScoreAdj = oomScoreAdj;
                c.cgroupDir = cgDir;
                c.reclaimFile = reclaimFile;
                c.memoryCurrentBytes = currentBytes;
                c.targetBytes = computeTargetReclaimBytes(currentBytes);
                byCgroup.put(key, c);
            }
        }

        List<ReclaimCandidate> list = new ArrayList<>(byCgroup.values());
        Collections.sort(list, new Comparator<ReclaimCandidate>() {
            @Override
            public int compare(ReclaimCandidate a, ReclaimCandidate b) {
                if (b.oomScoreAdj != a.oomScoreAdj) {
                    return Integer.compare(b.oomScoreAdj, a.oomScoreAdj);
                }
                return Long.compare(b.memoryCurrentBytes, a.memoryCurrentBytes);
            }
        });
        return list;
    }

    static class MemoryWatchV2 extends Thread {
        @Override
        public void run() {
            WriteBack writeBack = detectWriteBack();
            long lastReclaim = 0L;

            while (true) {
                double memFreeRatio = getMemInfo().getMemFreeRatio();

                synchronized (reclaimReason) {
                    reclaimReason.reason = ReclaimReason.REASON_MEMORY_WATCH;
                    try {
                        if (memFreeRatio >= 0.6) {
                            reclaimReason.wait(180000);
                        } else if (memFreeRatio >= 0.5) {
                            reclaimReason.wait(120000);
                        } else if (memFreeRatio > 0.4) {
                            reclaimReason.wait(60000);
                        } else if (memFreeRatio > 0.25) {
                            reclaimReason.wait(30000);
                        } else if (memFreeRatio > 0.1) {
                            reclaimReason.wait(20000);
                        } else {
                            reclaimReason.wait(10000);
                        }
                    } catch (InterruptedException ignored) {
                    }
                }

                memFreeRatio = getMemInfo().getMemFreeRatio();
                if (!isLowMemory(memFreeRatio)) {
                    continue;
                }

                if (writeBack != null) {
                    writeBack.writeIdle();
                    memFreeRatio = getMemInfo().getMemFreeRatio();
                    if (!isLowMemory(memFreeRatio)) {
                        continue;
                    }
                }

                int swapFree = getSwapFreeMB();
                if (swapFree < 300) {
                    continue;
                }

                if (memFreeRatio >= critical
                        && reclaimReason.reason != ReclaimReason.REASON_APP_WATCH
                        && (System.currentTimeMillis() - lastReclaim) < 120000L) {
                    continue;
                }

                List<ReclaimCandidate> candidates = collectCgroupV2Candidates();
                int count = 0;
                for (ReclaimCandidate c : candidates) {
                    if (count >= MAX_RECLAIM_PASS_TARGETS) {
                        break;
                    }
                    // memory.reclaim returns EAGAIN on partial reclaim in Linux; ignore I/O noise.
                    writeFileQuiet(Long.toString(c.targetBytes), c.reclaimFile);
                    count++;

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }

                    MemInfo updated = getMemInfo();
                    if ((updated.SwapFree / 1024L) < 100L
                            || updated.getMemFreeRatio() >= middle
                            || updated.getSwapCacheRatio() > 0.07) {
                        break;
                    }
                }
                lastReclaim = System.currentTimeMillis();
            }
        }
    }

    static class MemoryWatchV1 extends Thread {
        private final File idleGroup;

        MemoryWatchV1(File idleGroup) {
            this.idleGroup = idleGroup;
        }

        @Override
        public void run() {
            WriteBack writeBack = detectWriteBack();
            long lastReclaim = 0L;

            while (true) {
                double memFreeRatio = getMemInfo().getMemFreeRatio();

                synchronized (reclaimReason) {
                    reclaimReason.reason = ReclaimReason.REASON_MEMORY_WATCH;
                    try {
                        if (memFreeRatio >= 0.6) {
                            reclaimReason.wait(180000);
                        } else if (memFreeRatio >= 0.5) {
                            reclaimReason.wait(120000);
                        } else if (memFreeRatio > 0.4) {
                            reclaimReason.wait(60000);
                        } else if (memFreeRatio > 0.25) {
                            reclaimReason.wait(30000);
                        } else if (memFreeRatio > 0.1) {
                            reclaimReason.wait(20000);
                        } else {
                            reclaimReason.wait(10000);
                        }
                    } catch (InterruptedException ignored) {
                    }
                }

                memFreeRatio = getMemInfo().getMemFreeRatio();
                if (!isLowMemory(memFreeRatio)) {
                    continue;
                }

                if (writeBack != null) {
                    writeBack.writeIdle();
                    memFreeRatio = getMemInfo().getMemFreeRatio();
                    if (!isLowMemory(memFreeRatio)) {
                        continue;
                    }
                }

                int swapFree = getSwapFreeMB();
                if (swapFree < 300) {
                    continue;
                }

                if (memFreeRatio >= critical
                        && reclaimReason.reason != ReclaimReason.REASON_APP_WATCH
                        && (System.currentTimeMillis() - lastReclaim) < 120000L) {
                    continue;
                }

                List<String> pids = new ArrayList<>(readPidSet(idleGroup));
                Collections.sort(pids, new Comparator<String>() {
                    @Override
                    public int compare(String p1, String p2) {
                        return Integer.compare(readOomScoreAdj(p2), readOomScoreAdj(p1));
                    }
                });

                int count = 0;
                for (String pid : pids) {
                    if (count >= MAX_RECLAIM_PASS_TARGETS) {
                        break;
                    }
                    if (readOomScoreAdj(pid) < 100) {
                        continue;
                    }
                    writeFileQuiet("file", new File("/proc/" + pid + "/reclaim"));
                    count++;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                    MemInfo updated = getMemInfo();
                    if ((updated.SwapFree / 1024L) < 100L
                            || updated.getMemFreeRatio() >= middle
                            || updated.getSwapCacheRatio() > 0.07) {
                        break;
                    }
                }
                lastReclaim = System.currentTimeMillis();
            }
        }
    }

    static class WatchForegroundV1 extends Thread {
        private final File idleGroup;

        WatchForegroundV1(File idleGroup) {
            this.idleGroup = idleGroup;
        }

        @Override
        public void run() {
            String lastIdleProcs = "";
            String lastFgProcs = "";
            while (true) {
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException ignored) {
                }
                String idleProcs = readAllText(F_BG_PROCS);
                String fgProcs = readAllText(F_FG_PROCS);
                if (!idleProcs.equals(lastIdleProcs) || !fgProcs.equals(lastFgProcs)) {
                    lastIdleProcs = idleProcs;
                    lastFgProcs = fgProcs;

                    Set<String> topPids = readPidSet(F_TOP_PROCS);
                    StringBuilder idleBuilder = new StringBuilder();
                    for (String pid : readPidSet(F_BG_PROCS)) {
                        if (!topPids.contains(pid)) {
                            idleBuilder.append(pid).append('\n');
                        }
                    }
                    if (idleBuilder.length() > 0) {
                        writeFileQuiet(idleBuilder.toString(), idleGroup);
                    }

                    if (getMemInfo().getMemFreeRatio() <= high) {
                        synchronized (reclaimReason) {
                            reclaimReason.reason = ReclaimReason.REASON_FOREGROUND_WATCH;
                            reclaimReason.notifyAll();
                        }
                    }
                }
            }
        }
    }

    private static void startAppWatch(File activeGroup, File idleGroup, boolean isV1) throws InterruptedException {
        String currentTopProcs = "";
        long lastChange = 0L;
        while (true) {
            String topProcsStr = readAllText(F_TOP_PROCS);
            if (!topProcsStr.equals(currentTopProcs)) {
                currentTopProcs = topProcsStr;
                if (isV1 && activeGroup != null) {
                    writeFileQuiet(topProcsStr, activeGroup);
                }
                double memFreeRatio = getMemInfo().getMemFreeRatio();
                if (memFreeRatio <= middle) {
                    synchronized (reclaimReason) {
                        reclaimReason.reason = ReclaimReason.REASON_APP_WATCH;
                        reclaimReason.notifyAll();
                    }
                }
                lastChange = System.currentTimeMillis();
                Thread.sleep(5000);
            } else {
                long curTime = System.currentTimeMillis();
                if (curTime - lastChange > 600000L) {
                    Thread.sleep(10000);
                } else {
                    Thread.sleep(5000);
                }
            }
        }
    }

    private static boolean isLowMemory(double memFreeRatio) {
        return memFreeRatio <= high || (memFreeRatio <= middle && reclaimReason.reason == ReclaimReason.REASON_APP_WATCH);
    }

    private static void preConfig(String mode) {
        switch (mode) {
            case "force":
                critical = 0.23;
                high = 0.27;
                middle = 0.30;
                break;
            case "active":
                critical = 0.20;
                high = 0.25;
                middle = 0.28;
                break;
            case "lazy":
                critical = 0.14;
                high = 0.16;
                middle = 0.20;
                break;
            case "basic":
            case "passive":
            default:
                critical = 0.17;
                high = 0.23;
                middle = 0.25;
                break;
        }
    }

    private static int runSelfTest() {
        Backend backend = detectBackend();
        MemInfo mem = getMemInfo();
        System.out.println("backend=" + backend.id);
        System.out.println(String.format(
                "meminfo: MemTotal=%dKB MemAvailable=%dKB SwapTotal=%dKB SwapFree=%dKB SwapCached=%dKB freeRatio=%.4f",
                mem.MemTotal, mem.MemAvailable, mem.SwapTotal, mem.SwapFree, mem.SwapCached, mem.getMemFreeRatio()
        ));
        if (backend == Backend.CGROUP_V2) {
            List<ReclaimCandidate> candidates = collectCgroupV2Candidates();
            System.out.println("cgroup_v2_candidates=" + candidates.size());
            int limit = Math.min(3, candidates.size());
            for (int i = 0; i < limit; i++) {
                ReclaimCandidate c = candidates.get(i);
                System.out.println(String.format(
                        "sample[%d]: pid=%s oom_score_adj=%d path=%s current=%d target=%d",
                        i, c.pid, c.oomScoreAdj, c.reclaimFile.getPath(), c.memoryCurrentBytes, c.targetBytes
                ));
            }
        } else if (backend == Backend.CGROUP_V1) {
            File memcg = resolveLegacyMemcgDir();
            System.out.println("cgroup_v1_dir=" + (memcg != null ? memcg.getPath() : "none"));
        }
        return 0;
    }

    public static void main(String[] args) throws InterruptedException {
        for (String arg : args) {
            if ("--self-test".equals(arg)) {
                System.exit(runSelfTest());
                return;
            }
        }

        Backend backend = detectBackend();
        if (backend == Backend.UNSUPPORTED) {
            System.out.println("scene_swap_service: no supported cgroup memory reclaim backend found; exiting cleanly.");
            return;
        }

        String mode = args.length > 0 ? args[0].trim() : "basic";
        preConfig(mode);
        System.out.println("scene_swap_service starting: backend=" + backend.id + " mode=" + mode);

        if (backend == Backend.CGROUP_V2) {
            if (!F_TOP_PROCS.exists() || !F_BG_PROCS.exists()) {
                System.out.println("cpuset top-app/background procs missing; exiting cleanly.");
                return;
            }
            new MemoryWatchV2().start();
            startAppWatch(null, null, false);
        } else {
            File legacyMemcg = resolveLegacyMemcgDir();
            File activeGroup = new File(legacyMemcg, "scene_active/cgroup.procs");
            File idleGroup = new File(legacyMemcg, "scene_idle/cgroup.procs");
            new MemoryWatchV1(idleGroup).start();
            new WatchForegroundV1(idleGroup).start();
            startAppWatch(activeGroup, idleGroup, true);
        }
    }
}
