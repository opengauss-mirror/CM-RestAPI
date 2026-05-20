/*
 * Copyright (c) 2021 Huawei Technologies Co.,Ltd.
 *
 * CM is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package org.opengauss.cmrestapi;

import org.opengauss.cmrestapi.OGCmdExecuter.CmdResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects node-level metrics (memory, CPU breakdown, NFS mount I/O) on a schedule and
 * exposes the latest snapshot via {@link #getLatestMetric()}.
 *
 * @Title: NodeMetricCollector
 * @Description:
 * Periodically collects node-level metrics (memory, CPU breakdown, NFS mount I/O)
 * and exposes the latest snapshot via {@link #getLatestMetric()}.
 * @since 2021-05-17
 */
public class NodeMetricCollector {
    private static final Logger logger = LoggerFactory.getLogger(NodeMetricCollector.class);
    private static final long[] EMPTY_LONG_ARRAY = new long[0];
    private static final int METRIC_DIV_SCALE = 6;
    private static final RoundingMode METRIC_ROUNDING = RoundingMode.HALF_UP;
    private static final float FLOAT_COMPARE_EPSILON = 1e-6f;

    private static final Pattern PAT_NFS_DEVICE_HEADER = Pattern.compile(
        "^device (.+?) mounted on (.+?) with fstype (nfs\\d*)", Pattern.MULTILINE);
    private static final Pattern PAT_NFS_READ_LINE = Pattern.compile("(?im)^\\s*read:\\s*(\\d+)");
    private static final Pattern PAT_NFS_WRITE_LINE = Pattern.compile("(?im)^\\s*write:\\s*(\\d+)");
    private static final Pattern PAT_NFS_BYTES_INLINE = Pattern.compile("(?m)^bytes:\\s*((?:\\d+\\s+)+\\d+)\\s*$");

    private static final Thread.UncaughtExceptionHandler COLLECTOR_UNCAUGHT_HANDLER = (t, e) ->
        logger.error("Uncaught exception in thread {}: {}", t.getName(), e.getMessage(), e);

    private final ScheduledExecutorService collector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NodeMetricCollector");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(COLLECTOR_UNCAUGHT_HANDLER);
        return t;
    });

    private volatile Optional<NodeMetric> latestMetric = Optional.empty();
    private volatile long[] prevCpuStat = EMPTY_LONG_ARRAY;
    private volatile Map<String, NfsMountRawSnapshot> prevNfsMountSnapshots = Collections.emptyMap();
    private volatile long prevSampleTimeMs = 0L;

    /**
     * Memory snapshot in kB, aligned with {@code free} column semantics.
     *
     * @Title: MemoryDetail
     * @Description:
     * Memory snapshot in kB, aligned with {@code free} columns (total, used, free, shared, buff-cache, available).
     * @since 2021-05-17
     */
    public static class MemoryDetail {
        public final long memTotalKb;
        public final long memUsedKb;
        public final long memFreeKb;
        public final long memSharedKb;
        public final long buffCacheKb;
        public final long memAvailableKb;
        public final long swapTotalKb;
        public final long swapUsedKb;
        public final long swapFreeKb;
        public final float memUsedPercent;

        /**
         * Creates an immutable memory snapshot from parsed {@code /proc/meminfo} values.
         *
         * @Title: MemoryDetail
         * @Description: Constructs a memory snapshot.
         * @param memTotalKb total physical memory in kB
         * @param memUsedKb used physical memory in kB ({@code MemTotal - MemAvailable}, same as {@code free -k} used)
         * @param memFreeKb free physical memory in kB
         * @param memSharedKb shared memory in kB
         * @param buffCacheKb buffers and cache in kB
         * @param memAvailableKb available memory in kB
         * @param swapTotalKb total swap in kB
         * @param swapUsedKb used swap in kB
         * @param swapFreeKb free swap in kB
         * @param memUsedPercent memory used percentage
         */
        public MemoryDetail(long memTotalKb, long memUsedKb, long memFreeKb, long memSharedKb, long buffCacheKb,
            long memAvailableKb, long swapTotalKb, long swapUsedKb, long swapFreeKb, float memUsedPercent) {
            this.memTotalKb = memTotalKb;
            this.memUsedKb = memUsedKb;
            this.memFreeKb = memFreeKb;
            this.memSharedKb = memSharedKb;
            this.buffCacheKb = buffCacheKb;
            this.memAvailableKb = memAvailableKb;
            this.swapTotalKb = swapTotalKb;
            this.swapUsedKb = swapUsedKb;
            this.swapFreeKb = swapFreeKb;
            this.memUsedPercent = memUsedPercent;
        }
    }

    /**
     * Aggregate CPU time breakdown as percentages between two {@code /proc/stat} samples.
     *
     * @Title: CpuBreakdown
     * @Description:
     * Aggregate CPU jiffies delta percentages (similar to {@code iostat}/{@code mpstat}).
     * {@code pctSystem} includes irq + softirq.
     * @since 2021-05-17
     */
    public static class CpuBreakdown {
        public final float pctUser;
        public final float pctNice;
        public final float pctSystem;
        public final float pctIowait;
        public final float pctSteal;
        public final float pctIdle;

        /**
         * Creates an immutable CPU breakdown from per-category utilization percentages.
         *
         * @Title: CpuBreakdown
         * @Description: Constructs a CPU breakdown snapshot.
         * @param pctUser user CPU percentage
         * @param pctNice nice CPU percentage
         * @param pctSystem system CPU percentage
         * @param pctIowait I/O wait percentage
         * @param pctSteal steal percentage
         * @param pctIdle idle percentage
         */
        public CpuBreakdown(float pctUser, float pctNice, float pctSystem, float pctIowait, float pctSteal,
            float pctIdle) {
            this.pctUser = pctUser;
            this.pctNice = pctNice;
            this.pctSystem = pctSystem;
            this.pctIowait = pctIowait;
            this.pctSteal = pctSteal;
            this.pctIdle = pctIdle;
        }
    }

    /**
     * Per-operation NFS I/O rates and latency for one sampling interval.
     *
     * @Title: NfsOpIoStats
     * @Description:
     * Per READ/WRITE op stats in one sampling window; aligned with nfs-utils {@code nfs-iostat.py}.
     * @since 2021-05-17
     */
    public static class NfsOpIoStats {
        public final float opsPerSec;
        public final float kbPerSec;
        public final float kbPerOp;
        public final long retrans;
        public final float retransPercent;
        public final float avgRttMs;
        public final float avgExeMs;
        public final float avgQueueMs;
        public final long errors;
        public final float errorsPercent;

        /**
         * Creates immutable NFS per-operation I/O statistics for one sampling window.
         *
         * @Title: NfsOpIoStats
         * @Description: Constructs NFS per-operation I/O statistics.
         * @param opsPerSec operations per second
         * @param kbPerSec kilobytes per second
         * @param kbPerOp kilobytes per operation
         * @param retrans retransmission count
         * @param retransPercent retransmission percentage
         * @param avgRttMs average RTT in ms
         * @param avgExeMs average execution time in ms
         * @param avgQueueMs average queue time in ms
         * @param errors error count
         * @param errorsPercent error percentage
         */
        public NfsOpIoStats(float opsPerSec, float kbPerSec, float kbPerOp, long retrans, float retransPercent,
            float avgRttMs, float avgExeMs, float avgQueueMs, long errors, float errorsPercent) {
            this.opsPerSec = opsPerSec;
            this.kbPerSec = kbPerSec;
            this.kbPerOp = kbPerOp;
            this.retrans = retrans;
            this.retransPercent = retransPercent;
            this.avgRttMs = avgRttMs;
            this.avgExeMs = avgExeMs;
            this.avgQueueMs = avgQueueMs;
            this.errors = errors;
            this.errorsPercent = errorsPercent;
        }

        /**
         * Returns a zero-filled stats instance used when counters are unavailable.
         *
         * @Title: zeros
         * @Description: Constructs all-zero NFS per-operation I/O statistics.
         * @return zero {@link NfsOpIoStats}
         */
        static NfsOpIoStats zeros() {
            return new NfsOpIoStats(0f, 0f, 0f, 0L, 0f, 0f, 0f, 0f, 0L, 0f);
        }
    }

    /**
     * NFS mount-level RPC and READ/WRITE I/O statistics (nfsiostat-style).
     *
     * @Title: NfsMountIoDetail
     * @Description:
     * One NFS mount: mount-level RPC plus READ/WRITE stats (nfsiostat-style).
     * @since 2021-05-17
     */
    public static class NfsMountIoDetail {
        public final String device;
        public final float rpcOpsPerSec;
        public final float rpcBklog;
        public final NfsOpIoStats read;
        public final NfsOpIoStats write;

        /**
         * Creates immutable NFS mount-level RPC and READ/WRITE I/O detail.
         *
         * @Title: NfsMountIoDetail
         * @Description: Constructs NFS mount I/O detail.
         * @param device NFS device name
         * @param rpcOpsPerSec RPC ops per second
         * @param rpcBklog RPC backlog
         * @param read READ stats
         * @param write WRITE stats
         */
        public NfsMountIoDetail(String device, float rpcOpsPerSec, float rpcBklog, NfsOpIoStats read,
            NfsOpIoStats write) {
            this.device = device;
            this.rpcOpsPerSec = rpcOpsPerSec;
            this.rpcBklog = rpcBklog;
            this.read = read;
            this.write = write;
        }
    }

    /**
     * Latest aggregated node metrics snapshot exposed to REST handlers.
     *
     * @Title: NodeMetric
     * @Description:
     * Latest aggregated node metrics snapshot.
     * @since 2021-05-17
     */
    public static class NodeMetric {
        public final MemoryDetail memoryDetail;
        public final CpuBreakdown cpuBreakdown;
        public final Map<String, NfsMountIoDetail> nfsIoByMount;
        public final boolean isCheckMemorySourceFailed;
        public final boolean isCheckCpuSourceFailed;
        public final boolean isCheckNfsSourceFailed;

        /**
         * Creates an immutable aggregate of the latest memory, CPU, and NFS metrics.
         *
         * @Title: NodeMetric
         * @Description: Constructs a node metrics snapshot.
         * @param memoryDetail memory snapshot
         * @param cpuBreakdown CPU breakdown
         * @param nfsIoByMount NFS I/O by mount point
         * @param isCheckMemorySourceFailed true when {@code /proc/meminfo} could not be read this cycle
         * @param isCheckCpuSourceFailed true when {@code /proc/stat} could not be read this cycle
         * @param isCheckNfsSourceFailed true when {@code /proc/self/mountstats} could not be read this cycle
         */
        public NodeMetric(MemoryDetail memoryDetail, CpuBreakdown cpuBreakdown,
            Map<String, NfsMountIoDetail> nfsIoByMount, boolean isCheckMemorySourceFailed,
            boolean isCheckCpuSourceFailed, boolean isCheckNfsSourceFailed) {
            this.memoryDetail = memoryDetail;
            this.cpuBreakdown = cpuBreakdown;
            this.nfsIoByMount = nfsIoByMount;
            this.isCheckMemorySourceFailed = isCheckMemorySourceFailed;
            this.isCheckCpuSourceFailed = isCheckCpuSourceFailed;
            this.isCheckNfsSourceFailed = isCheckNfsSourceFailed;
        }
    }

    private static final class MetricReadOutcome<T> {
        private final boolean isSourceReadable;
        private final T payload;

        private MetricReadOutcome(boolean isSourceReadable, T payload) {
            this.isSourceReadable = isSourceReadable;
            this.payload = payload;
        }

        private static <T> MetricReadOutcome<T> failed() {
            return new MetricReadOutcome<>(false, null);
        }

        private static <T> MetricReadOutcome<T> ok(T payload) {
            return new MetricReadOutcome<>(true, payload);
        }

        private boolean isCurSourceReadable() {
            return isSourceReadable;
        }

        private T getPayload() {
            return payload;
        }
    }

    private static final class NfsMountRawSnapshot {
        final String mountPoint;
        final String device;
        final long readBytes;
        final long writeBytes;
        boolean hasXprt;
        long rpcSends;
        long rpcBacklogUtil;
        long[] readOp = EMPTY_LONG_ARRAY;
        long[] writeOp = EMPTY_LONG_ARRAY;

        /**
         * Stores raw NFS mount counters for delta computation on the next sample.
         *
         * @Title: NfsMountRawSnapshot
         * @Description: Constructs a mountstats raw snapshot.
         * @param mountPoint mount point path
         * @param device NFS device name
         * @param readBytes cumulative read bytes
         * @param writeBytes cumulative write bytes
         */
        NfsMountRawSnapshot(String mountPoint, String device, long readBytes, long writeBytes) {
            this.mountPoint = mountPoint;
            this.device = device;
            this.readBytes = readBytes;
            this.writeBytes = writeBytes;
        }
    }

    /**
     * Starts periodic metric collection on a background thread (1 second interval).
     *
     * @Title: start
     * @Description: Starts periodic metric collection (1 second interval).
     */
    public void start() {
        collector.scheduleAtFixedRate(this::collectMetricSafely, 0L, 1L, TimeUnit.SECONDS);
    }

    /**
     * Stops the metric collector scheduler and pending collection tasks.
     *
     * @Title: stop
     * @Description: Stops the metric collector scheduler.
     */
    public void stop() {
        collector.shutdownNow();
    }

    /**
     * Returns the most recently collected node metrics snapshot, if any.
     *
     * @Title: getLatestMetric
     * @Description: Returns the most recently collected node metrics snapshot.
     * @return latest {@link NodeMetric}, or empty if not yet collected
     */
    public Optional<NodeMetric> getLatestMetric() {
        return latestMetric;
    }

    /**
     * Runs {@link #collectMetric()} and logs failures without stopping the scheduler.
     *
     * @Title: collectMetricSafely
     * @Description: Collects metrics inside a try/catch guard for the scheduled task.
     */
    private void collectMetricSafely() {
        try {
            collectMetric();
        } catch (IllegalArgumentException | ArithmeticException | IndexOutOfBoundsException e) {
            logger.error("collect node metric failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Samples memory, CPU, and NFS metrics and updates {@link #latestMetric}.
     *
     * @Title: collectMetric
     * @Description: Records per-source read failures; does not reuse stale values when a read fails.
     */
    private void collectMetric() {
        MetricReadOutcome<long[]> cpuOutcome = readCpuStatLine();
        boolean isCheckCpuSourceFailed = !cpuOutcome.isCurSourceReadable();
        long[] cpuStat = isCheckCpuSourceFailed ? EMPTY_LONG_ARRAY : cpuOutcome.getPayload();

        Optional<CpuBreakdown> cpuBreakdown = Optional.empty();
        if (!isCheckCpuSourceFailed && prevCpuStat.length > 0 && prevSampleTimeMs > 0 && cpuStat.length > 0) {
            cpuBreakdown = calcCpuBreakdown(prevCpuStat, cpuStat);
        }
        if (!isCheckCpuSourceFailed && cpuStat.length > 0) {
            prevCpuStat = cpuStat;
        }

        MetricReadOutcome<List<NfsMountRawSnapshot>> nfsOutcome = readNfsMountSnapshots();
        boolean isCheckNfsSourceFailed = !nfsOutcome.isCurSourceReadable();
        List<NfsMountRawSnapshot> nfsSnapshots =
            isCheckNfsSourceFailed ? Collections.emptyList() : nfsOutcome.getPayload();
        Map<String, NfsMountRawSnapshot> nfsByMount = nfsSnapshotsToMap(nfsSnapshots);
        Map<String, NfsMountIoDetail> nfsIoByMount = new HashMap<>();
        long now = System.currentTimeMillis();
        if (!isCheckNfsSourceFailed && !prevNfsMountSnapshots.isEmpty()
            && prevSampleTimeMs > 0 && !nfsByMount.isEmpty()) {
            nfsIoByMount = calcNfsIoDetails(prevSampleTimeMs, now, prevNfsMountSnapshots, nfsByMount);
        }
        if (!isCheckNfsSourceFailed) {
            prevNfsMountSnapshots = new HashMap<>(nfsByMount);
        }
        prevSampleTimeMs = now;

        MetricReadOutcome<MemoryDetail> memoryOutcome = readMemoryDetail();
        boolean isCheckMemorySourceFailed = !memoryOutcome.isCurSourceReadable();
        MemoryDetail memoryDetail = isCheckMemorySourceFailed ? null : memoryOutcome.getPayload();

        latestMetric = Optional.of(new NodeMetric(memoryDetail, cpuBreakdown.orElse(null),
            Collections.unmodifiableMap(nfsIoByMount), isCheckMemorySourceFailed,
            isCheckCpuSourceFailed, isCheckNfsSourceFailed));
    }

    private static boolean isProcCmdSuccessful(CmdResult cmdResult) {
        return cmdResult != null && cmdResult.statusCode == 0 && cmdResult.resultString != null;
    }

    /**
     * Reads aggregate {@code cpu} jiffies from {@code /proc/stat} (first line).
     *
     * @Title: readCpuStatLine
     * @Description:
     * Returns jiffies array (user, nice, system, idle, iowait, irq, softirq, steal, ...),
     * or an empty array on failure.
     * @return CPU stat counters, or empty array if unavailable
     */
    private MetricReadOutcome<long[]> readCpuStatLine() {
        CmdResult cmdResult = OGCmdExecuter.execCmd("cat /proc/stat | head -n 1");
        if (!isProcCmdSuccessful(cmdResult)) {
            return MetricReadOutcome.failed();
        }
        String firstLine = cmdResult.resultString.trim();
        if (firstLine.isEmpty()) {
            return MetricReadOutcome.failed();
        }
        String[] fields = firstLine.split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) {
            return MetricReadOutcome.failed();
        }
        long[] v = new long[10];
        int n = Math.min(fields.length - 1, 10);
        for (int i = 0; i < n; i++) {
            try {
                v[i] = Long.parseLong(fields[i + 1]);
            } catch (NumberFormatException e) {
                return MetricReadOutcome.failed();
            }
        }
        return MetricReadOutcome.ok(v);
    }

    /**
     * Computes CPU category percentages from two aggregate {@code cpu} stat samples.
     *
     * @Title: calcCpuBreakdown
     * @Description: Derives user/nice/system/iowait/steal/idle percentages from jiffies deltas.
     * @param v1 previous sample
     * @param v2 current sample
     * @return CPU breakdown, or empty if samples are invalid
     */
    private Optional<CpuBreakdown> calcCpuBreakdown(long[] v1, long[] v2) {
        if (v1.length == 0 || v2.length == 0) {
            return Optional.empty();
        }
        long du = v2[0] - v1[0];
        long dn = v2[1] - v1[1];
        long ds = v2[2] - v1[2];
        long didle = v2[3] - v1[3];
        long diowait = v2.length > 4 ? v2[4] - v1[4] : 0L;
        long dirq = v2.length > 5 ? v2[5] - v1[5] : 0L;
        long dsoft = v2.length > 6 ? v2[6] - v1[6] : 0L;
        long dst = v2.length > 7 ? v2[7] - v1[7] : 0L;

        long dSysCombined = ds + dirq + dsoft;
        long total = du + dn + dSysCombined + didle + diowait + dst;
        if (total <= 0) {
            return Optional.empty();
        }
        float pctUser = percentOf(du, total);
        float pctNice = percentOf(dn, total);
        float pctSystem = percentOf(dSysCombined, total);
        float pctIowait = percentOf(diowait, total);
        float pctSteal = percentOf(dst, total);
        float pctIdle = percentOf(didle, total);
        return Optional.of(new CpuBreakdown(pctUser, pctNice, pctSystem, pctIowait, pctSteal, pctIdle));
    }

    /**
     * Looks up a {@code /proc/meminfo} value in kB, or {@code -1} if absent.
     *
     * @Title: parseMeminfoLineKb
     * @Description: Returns the parsed kB value for the given meminfo key.
     * @param map parsed meminfo entries
     * @param key meminfo field name
     * @return value in kB, or {@code -1} when missing
     */
    private static long parseMeminfoLineKb(Map<String, Long> map, String key) {
        Long v = map.get(key);
        return v != null ? v : -1L;
    }

    /**
     * Reads and parses {@code /proc/meminfo} into a {@link MemoryDetail} snapshot.
     *
     * @Title: readMemoryDetail
     * @Description: Computes memory columns to match {@code free -k} (used = total - available).
     * @return memory detail, or empty on read/parse failure
     */
    private MetricReadOutcome<MemoryDetail> readMemoryDetail() {
        CmdResult cmdResult = OGCmdExecuter.execCmd("cat /proc/meminfo");
        if (!isProcCmdSuccessful(cmdResult)) {
            return MetricReadOutcome.failed();
        }
        return buildMemoryDetail(parseMeminfoKbMap(cmdResult.resultString));
    }

    /**
     * Parses {@code /proc/meminfo} text into a key-to-kB map.
     *
     * @Title: parseMeminfoKbMap
     * @Description: Parses meminfo lines of the form {@code Key: <value> kB}.
     * @param meminfoText raw {@code /proc/meminfo} content
     * @return parsed entries (keys without units)
     */
    private static Map<String, Long> parseMeminfoKbMap(String meminfoText) {
        Map<String, Long> meminfo = new HashMap<>();
        String[] lines = meminfoText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String rest = trimmed.substring(colon + 1).trim();
            String[] parts = rest.split("\\s+");
            if (parts.length < 1) {
                continue;
            }
            try {
                meminfo.put(key, Long.parseLong(parts[0]));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return meminfo;
    }

    /**
     * Builds {@link MemoryDetail} from a parsed meminfo map.
     *
     * @Title: buildMemoryDetail
     * @Description: Aligns with procps {@code free -k} (used = total - available).
     * @param meminfo parsed {@code /proc/meminfo} entries in kB
     * @return memory detail, or failed when required fields are missing
     */
    private MetricReadOutcome<MemoryDetail> buildMemoryDetail(Map<String, Long> meminfo) {
        long memTotal = parseMeminfoLineKb(meminfo, "MemTotal");
        long memFree = parseMeminfoLineKb(meminfo, "MemFree");
        if (memTotal <= 0 || memFree < 0) {
            return MetricReadOutcome.failed();
        }

        long buffCache = computeBuffCacheKb(meminfo);
        long memAvailableRaw = parseMeminfoLineKb(meminfo, "MemAvailable");
        long memUsed = computeMemUsedKb(memTotal, memFree, buffCache, memAvailableRaw);
        long[] swap = normalizeSwapKb(parseMeminfoLineKb(meminfo, "SwapTotal"),
            parseMeminfoLineKb(meminfo, "SwapFree"));
        float memUsedPct = computeMemUsedPercent(memTotal, memUsed);
        long shmem = Math.max(0L, parseMeminfoLineKb(meminfo, "Shmem"));
        long memAvailable = memAvailableRaw >= 0 ? memAvailableRaw : memFree + buffCache;
        return MetricReadOutcome.ok(new MemoryDetail(memTotal, memUsed, memFree, shmem, buffCache, memAvailable,
            swap[0], swap[2], swap[1], memUsedPct));
    }

    private static long computeBuffCacheKb(Map<String, Long> meminfo) {
        long buffers = Math.max(0L, parseMeminfoLineKb(meminfo, "Buffers"));
        long cached = Math.max(0L, parseMeminfoLineKb(meminfo, "Cached"));
        long sReclaim = Math.max(0L, parseMeminfoLineKb(meminfo, "SReclaimable"));
        return buffers + cached + sReclaim;
    }

    private static long computeMemUsedKb(long memTotal, long memFree, long buffCache, long memAvailableRaw) {
        long memUsed = memAvailableRaw >= 0 ? memTotal - memAvailableRaw : memTotal - memFree - buffCache;
        return memUsed < 0 ? 0L : memUsed;
    }

    /**
     * @return {@code [swapTotalKb, swapFreeKb, swapUsedKb]}
     */
    private static long[] normalizeSwapKb(long swapTotal, long swapFree) {
        long normalizedTotal = swapTotal;
        long normalizedFree = swapFree;
        long swapUsed = 0L;
        if (normalizedTotal < 0) {
            normalizedTotal = 0L;
            normalizedFree = 0L;
        } else if (normalizedFree >= 0 && normalizedTotal >= normalizedFree) {
            swapUsed = normalizedTotal - normalizedFree;
        } else {
            swapUsed = 0L;
        }
        return new long[] {normalizedTotal, normalizedFree, swapUsed};
    }

    private static float computeMemUsedPercent(long memTotal, long memUsed) {
        float memUsedPct = memTotal > 0 ? percentOf(memUsed, memTotal) : -1.0f;
        return Math.max(0.0f, Math.min(memUsedPct, 100.0f));
    }

    /**
     * Extracts cumulative read/write byte counters from one mountstats section.
     *
     * @Title: parseNfsBytesFromSection
     * @Description: Tries inline {@code read:}/{@code write:} lines, then legacy {@code bytes:} layout.
     * @param sec one {@code device ...} section from {@code /proc/self/mountstats}
     * @return {@code [readBytes, writeBytes]}, or empty array if not found
     */
    private long[] parseNfsBytesFromSection(String sec) {
        Matcher mr = PAT_NFS_READ_LINE.matcher(sec);
        Matcher mw = PAT_NFS_WRITE_LINE.matcher(sec);
        long readB = -1;
        long writeB = -1;
        if (mr.find()) {
            try {
                readB = Long.parseLong(mr.group(1));
            } catch (NumberFormatException e) {
                readB = -1;
            }
        }
        if (mw.find()) {
            try {
                writeB = Long.parseLong(mw.group(1));
            } catch (NumberFormatException e) {
                writeB = -1;
            }
        }
        if (readB >= 0 && writeB >= 0) {
            return new long[] {readB, writeB};
        }
        if (readB >= 0) {
            return new long[] {readB, writeB >= 0 ? writeB : 0L};
        }
        if (writeB >= 0) {
            return new long[] {readB >= 0 ? readB : 0L, writeB};
        }
        Matcher inline = PAT_NFS_BYTES_INLINE.matcher(sec);
        if (inline.find()) {
            String[] nums = inline.group(1).trim().split("\\s+");
            if (nums.length >= 2) {
                try {
                    long r = Long.parseLong(nums[0]);
                    long w = Long.parseLong(nums[1]);
                    return new long[] {r, w};
                } catch (NumberFormatException e) {
                    return EMPTY_LONG_ARRAY;
                }
            }
        }
        return parseNfsBytesLineAfterBytesLabel(sec);
    }

    /**
     * Parses legacy mountstats layout where byte totals follow a {@code bytes:} label line.
     *
     * @Title: parseNfsBytesLineAfterBytesLabel
     * @Description: Scans lines after {@code bytes:} for a whitespace-separated number pair.
     * @param sec mountstats section text
     * @return {@code [readBytes, writeBytes]}, or empty array if not found
     */
    private long[] parseNfsBytesLineAfterBytesLabel(String sec) {
        String[] lines = sec.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!isMountstatsBytesLabelLine(lines[i])) {
                continue;
            }
            Optional<long[]> pair = findNfsBytePairAfterBytesLabel(lines, i);
            if (pair.isPresent()) {
                return pair.get();
            }
        }
        return EMPTY_LONG_ARRAY;
    }

    private static boolean isMountstatsBytesLabelLine(String line) {
        String trimmed = line.trim();
        return "bytes:".equalsIgnoreCase(trimmed) || trimmed.matches("(?i)bytes:\\s*");
    }

    private static Optional<long[]> findNfsBytePairAfterBytesLabel(String[] lines, int bytesLabelIndex) {
        int end = Math.min(lines.length, bytesLabelIndex + 6);
        for (int j = bytesLabelIndex + 1; j < end; j++) {
            String trimmed = lines[j].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isNfsReadOrWriteCounterLine(trimmed)) {
                break;
            }
            Optional<long[]> pair = tryParseWhitespaceBytePair(trimmed);
            if (pair.isPresent()) {
                return pair;
            }
            break;
        }
        return Optional.empty();
    }

    private static boolean isNfsReadOrWriteCounterLine(String line) {
        return line.regionMatches(true, 0, "read:", 0, 5) || line.regionMatches(true, 0, "write:", 0, 6);
    }

    /**
     * @return present with two cumulative byte counters; present with {@link #EMPTY_LONG_ARRAY} on parse error;
     *         empty when the line format does not match
     */
    private static Optional<long[]> tryParseWhitespaceBytePair(String line) {
        if (!line.matches("^(?:\\d+\\s+)+\\d+$")) {
            return Optional.empty();
        }
        String[] nums = line.split("\\s+");
        if (nums.length < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new long[] {Long.parseLong(nums[0]), Long.parseLong(nums[1])});
        } catch (NumberFormatException e) {
            return Optional.of(EMPTY_LONG_ARRAY);
        }
    }

    /**
     * Parses {@code xprt: tcp|udp|rdma} line for rpcsends and backlogutil (nfs-utils nfs-iostat indices).
     *
     * @Title: parseXprtSendsBacklog
     * @Description: Fills {@code outSendsBacklog[0]} with rpc sends and {@code [1]} with backlog util.
     * @param line mountstats line
     * @param outSendsBacklog two-element output buffer
     * @return true if the line was parsed
     */
    private static boolean parseXprtSendsBacklog(String line, long[] outSendsBacklog) {
        String[] w = line.trim().split("\\s+");
        if (w.length < 3 || !"xprt:".equalsIgnoreCase(w[0])) {
            return false;
        }
        String kind = w[1].toLowerCase();
        try {
            if ("tcp".equals(kind) && w.length >= 12) {
                outSendsBacklog[0] = Long.parseLong(w[7]);
                outSendsBacklog[1] = Long.parseLong(w[11]);
                return true;
            }
            if ("udp".equals(kind) && w.length >= 9) {
                outSendsBacklog[0] = Long.parseLong(w[4]);
                outSendsBacklog[1] = Long.parseLong(w[8]);
                return true;
            }
            if ("rdma".equals(kind) && w.length >= 10) {
                outSendsBacklog[0] = Long.parseLong(w[7]);
                outSendsBacklog[1] = Long.parseLong(w[9]);
                return true;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }

    /**
     * Parses a per-op mountstats line such as {@code READ: n n ...} (at least 8 counters).
     *
     * @Title: parsePerOpCountersLine
     * @Description: Returns counter array for the requested op name, or empty array if not matched.
     * @param line mountstats line
     * @param wantOp {@code READ} or {@code WRITE}
     * @return op counters, or empty array
     */
    private static long[] parsePerOpCountersLine(String line, String wantOp) {
        String t = line.trim();
        int colon = t.indexOf(':');
        if (colon <= 0) {
            return EMPTY_LONG_ARRAY;
        }
        String op = t.substring(0, colon).trim();
        if (!op.equalsIgnoreCase(wantOp)) {
            return EMPTY_LONG_ARRAY;
        }
        String rest = t.substring(colon + 1).trim();
        if (rest.isEmpty()) {
            return EMPTY_LONG_ARRAY;
        }
        String[] parts = rest.split("\\s+");
        if (parts.length < 8) {
            return EMPTY_LONG_ARRAY;
        }
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                return EMPTY_LONG_ARRAY;
            }
        }
        return out;
    }

    /**
     * Computes nfsiostat-style per-op rates between two counter snapshots.
     *
     * @Title: calcNfsPerOpStats
     * @Description: Applies nfs-utils delta formulas for ops/s, kB/s, latency, and error rates.
     * @param intervalSec sampling interval in seconds
     * @param prev previous op counters
     * @param cur current op counters
     * @return per-op I/O statistics, or zeros when counters are insufficient
     */
    private static NfsOpIoStats calcNfsPerOpStats(float intervalSec, long[] prev, long[] cur) {
        if (prev.length < 8 || cur.length < 8) {
            return NfsOpIoStats.zeros();
        }
        long d0 = cur[0] - prev[0];
        long d1 = cur[1] - prev[1];
        long d2 = cur[2] - prev[2];
        long d3 = cur[3] - prev[3];
        long d4 = cur[4] - prev[4];
        long d5 = cur[5] - prev[5];
        long d6 = cur[6] - prev[6];
        long d7 = cur[7] - prev[7];
        long d8 = (cur.length > 8 && prev.length > 8) ? (cur[8] - prev[8]) : 0L;
        if (d0 < 0 || d1 < 0 || d2 < 0 || d3 < 0 || d4 < 0 || d5 < 0 || d6 < 0 || d7 < 0 || d8 < 0) {
            return NfsOpIoStats.zeros();
        }
        long retrans = d1 - d0;
        if (retrans < 0) {
            retrans = 0L;
        }
        float opsPerSec = divideLongByFloat(d0, intervalSec);
        BigDecimal kb = bytesToKilobytes(d3 + d4);
        float kbPerSec = divideByFloat(kb, intervalSec);
        float kbPerOp = d0 > 0 ? divideByLong(kb, d0) : 0f;
        float avgQueueMs = d0 > 0 ? divideLongByLong(d5, d0) : 0f;
        float avgRttMs = d0 > 0 ? divideLongByLong(d6, d0) : 0f;
        float avgExeMs = d0 > 0 ? divideLongByLong(d7, d0) : 0f;
        float retransPct = d0 > 0 ? percentOf(retrans, d0) : 0f;
        float errPct = d0 > 0 ? percentOf(d8, d0) : 0f;
        return new NfsOpIoStats(opsPerSec, kbPerSec, kbPerOp, retrans, retransPct, avgRttMs, avgExeMs, avgQueueMs, d8,
            errPct);
    }

    /**
     * Parses {@code /proc/self/mountstats} into raw per-mount NFS snapshots.
     *
     * @Title: readNfsMountSnapshots
     * @Description: Collects device, byte totals, xprt RPC counters, and READ/WRITE op lines.
     * @return list of raw snapshots (possibly empty)
     */
    private MetricReadOutcome<List<NfsMountRawSnapshot>> readNfsMountSnapshots() {
        CmdResult cmdResult = OGCmdExecuter.execCmd("cat /proc/self/mountstats");
        if (!isProcCmdSuccessful(cmdResult)) {
            return MetricReadOutcome.failed();
        }
        List<NfsMountRawSnapshot> list = new ArrayList<>();
        String[] sections = cmdResult.resultString.split("(?m)(?=^device )");
        long[] xprt = new long[2];
        for (String sec : sections) {
            if (sec.isEmpty() || !sec.contains("fstype nfs")) {
                continue;
            }
            Matcher head = PAT_NFS_DEVICE_HEADER.matcher(sec);
            if (!head.find()) {
                continue;
            }
            String device = head.group(1).trim();
            String mountPoint = head.group(2).trim();
            long[] rw = parseNfsBytesFromSection(sec);
            long readB = rw.length >= 1 ? rw[0] : 0L;
            long writeB = rw.length >= 2 ? rw[1] : 0L;
            NfsMountRawSnapshot snap = new NfsMountRawSnapshot(mountPoint, device, readB, writeB);
            for (String rawLine : sec.split("\\r?\\n")) {
                if (parseXprtSendsBacklog(rawLine, xprt)) {
                    snap.hasXprt = true;
                    snap.rpcSends = xprt[0];
                    snap.rpcBacklogUtil = xprt[1];
                }
                long[] ro = parsePerOpCountersLine(rawLine, "READ");
                if (ro.length > 0) {
                    snap.readOp = ro;
                }
                long[] wo = parsePerOpCountersLine(rawLine, "WRITE");
                if (wo.length > 0) {
                    snap.writeOp = wo;
                }
            }
            if (rw.length == 0 && snap.readOp.length == 0 && snap.writeOp.length == 0 && !snap.hasXprt) {
                continue;
            }
            list.add(snap);
        }
        return MetricReadOutcome.ok(list);
    }

    /**
     * Indexes raw NFS snapshots by mount point for delta lookup.
     *
     * @Title: nfsSnapshotsToMap
     * @Description: Builds a map keyed by {@link NfsMountRawSnapshot#mountPoint}.
     * @param snapshots parsed mount snapshots
     * @return map keyed by mount point
     */
    private Map<String, NfsMountRawSnapshot> nfsSnapshotsToMap(List<NfsMountRawSnapshot> snapshots) {
        Map<String, NfsMountRawSnapshot> map = new HashMap<>();
        for (NfsMountRawSnapshot s : snapshots) {
            map.put(s.mountPoint, s);
        }
        return map;
    }

    /**
     * Derives per-mount NFS I/O detail from two raw snapshot maps over an interval.
     *
     * @Title: calcNfsIoDetails
     * @Description: Computes RPC and READ/WRITE rates; falls back to byte deltas when op lines are missing.
     * @param t1 previous sample time (ms)
     * @param t2 current sample time (ms)
     * @param prev previous raw snapshots by mount point
     * @param cur current raw snapshots by mount point
     * @return mount point to nfsiostat-style detail (possibly empty)
     */
    private Map<String, NfsMountIoDetail> calcNfsIoDetails(long t1, long t2, Map<String, NfsMountRawSnapshot> prev,
        Map<String, NfsMountRawSnapshot> cur) {
        Map<String, NfsMountIoDetail> result = new HashMap<>();
        if (prev.isEmpty() || cur.isEmpty()) {
            return result;
        }
        float intervalSec = intervalSecondsFromMillis(t1, t2);
        if (isFloatNonPositive(intervalSec)) {
            return result;
        }
        for (Map.Entry<String, NfsMountRawSnapshot> e : cur.entrySet()) {
            String mount = e.getKey();
            NfsMountRawSnapshot c = e.getValue();
            NfsMountRawSnapshot p = prev.get(mount);
            if (p == null) {
                continue;
            }
            if (c.readBytes < p.readBytes || c.writeBytes < p.writeBytes) {
                continue;
            }
            float rpcOpsPerSec = 0f;
            float rpcBklog = 0f;
            if (c.hasXprt && p.hasXprt && c.rpcSends >= p.rpcSends && c.rpcBacklogUtil >= p.rpcBacklogUtil) {
                long dSends = c.rpcSends - p.rpcSends;
                long dBk = c.rpcBacklogUtil - p.rpcBacklogUtil;
                rpcOpsPerSec = divideLongByFloat(dSends, intervalSec);
                if (dSends > 0) {
                    rpcBklog = divideLongLongByFloat(dBk, dSends, intervalSec);
                }
            }
            NfsOpIoStats readStat = calcNfsPerOpStats(intervalSec, p.readOp, c.readOp);
            NfsOpIoStats writeStat = calcNfsPerOpStats(intervalSec, p.writeOp, c.writeOp);
            if ((p.readOp.length < 8 || c.readOp.length < 8) && isFloatZero(readStat.opsPerSec)
                && isFloatZero(readStat.kbPerSec)) {
                long dr = c.readBytes - p.readBytes;
                if (dr > 0) {
                    readStat = new NfsOpIoStats(0f, kilobytesPerSecondFromBytes(dr, intervalSec), 0f, 0L, 0f, 0f, 0f,
                        0f, 0L, 0f);
                }
            }
            if ((p.writeOp.length < 8 || c.writeOp.length < 8) && isFloatZero(writeStat.opsPerSec)
                && isFloatZero(writeStat.kbPerSec)) {
                long dw = c.writeBytes - p.writeBytes;
                if (dw > 0) {
                    writeStat = new NfsOpIoStats(0f, kilobytesPerSecondFromBytes(dw, intervalSec), 0f, 0L, 0f, 0f, 0f,
                        0f, 0L, 0f);
                }
            }
            String device = c.device != null ? c.device : mount;
            result.put(mount, new NfsMountIoDetail(device, rpcOpsPerSec, rpcBklog, readStat, writeStat));
        }
        return result;
    }

    private static float intervalSecondsFromMillis(long t1Ms, long t2Ms) {
        return BigDecimal.valueOf(t2Ms - t1Ms)
            .divide(BigDecimal.valueOf(1000L), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static float percentOf(long part, long total) {
        if (total <= 0) {
            return 0f;
        }
        return BigDecimal.valueOf(part)
            .multiply(BigDecimal.valueOf(100L))
            .divide(BigDecimal.valueOf(total), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static float divideLongByFloat(long numerator, float denominator) {
        if (isFloatZero(denominator)) {
            return 0f;
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf((double) denominator), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static float divideLongByLong(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0f;
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static float divideLongLongByFloat(long numerator, long denominator, float divisor) {
        if (denominator <= 0 || isFloatZero(divisor)) {
            return 0f;
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .divide(BigDecimal.valueOf((double) divisor), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static BigDecimal bytesToKilobytes(long bytes) {
        return BigDecimal.valueOf(bytes)
            .divide(BigDecimal.valueOf(1024L), METRIC_DIV_SCALE, METRIC_ROUNDING);
    }

    private static float divideByFloat(BigDecimal value, float denominator) {
        if (isFloatZero(denominator)) {
            return 0f;
        }
        return value.divide(BigDecimal.valueOf((double) denominator), METRIC_DIV_SCALE, METRIC_ROUNDING)
            .floatValue();
    }

    private static float divideByLong(BigDecimal value, long denominator) {
        if (denominator <= 0) {
            return 0f;
        }
        return value.divide(BigDecimal.valueOf(denominator), METRIC_DIV_SCALE, METRIC_ROUNDING).floatValue();
    }

    private static float kilobytesPerSecondFromBytes(long bytes, float intervalSec) {
        return divideByFloat(bytesToKilobytes(bytes), intervalSec);
    }

    private static boolean isFloatZero(float value) {
        return Math.abs(value) <= FLOAT_COMPARE_EPSILON;
    }

    private static boolean isFloatNonPositive(float value) {
        return value <= FLOAT_COMPARE_EPSILON;
    }
}
