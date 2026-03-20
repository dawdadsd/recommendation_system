package xiaowu.example.jvm.application.service;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * JVM 内存学习服务。
 *
 * <p>
 * 这个类故意做两件不同的事情，而且必须分开理解：
 *
 * <p>
 * 第一件事：读取当前进程真实的 JVM 运行时内存信息。
 * 这一部分帮助你理解 JVM 内存到底怎么划分，以及当前进程实际占了多少。
 *
 * <p>
 * 第二件事：基于“推荐系统训练流程”的对象关系，模拟一轮标记-清理垃圾回收。
 * 这一部分帮助你理解 GC Roots、可达性分析、标记、清理、碎片化这些核心概念。
 *
 * <p>
 * 注意：
 * 这里不是在“实现真正的 JVM 垃圾回收器”。
 * 真正的 GC 属于 HotSpot / JVM 内部实现，业务代码做不到替代它。
 * 我们能做的是把原理讲清楚、映射到你熟悉的工程对象上。
 */
@Service
public class JvmMemoryLearningService {

    private static final Logger log = LoggerFactory.getLogger(JvmMemoryLearningService.class);

        /**
         * 采集当前 JVM 进程的真实内存布局。
         *
         * <p>
         * 你可以把这个方法当成“观察真实世界”的入口。
         * 它不会模拟，而是直接读取当前 Application 进程的运行信息。
         *
         * <p>
         * 这里重点关注几块区域：
         *
         * <p>
         * 1. Heap：堆，主要放 Java 对象、集合、数组、DTO、缓存。
         *
         * <p>
         * 2. Non-Heap：非堆，典型包括 Metaspace、Code Cache 等。
         *
         * <p>
         * 3. Direct Buffer：堆外内存，Netty、NIO、Spark 这类框架经常会用到。
         *
         * <p>
         * 4. Thread：线程数量越多，线程栈占用的本地内存也越多。
         */
        public JvmRuntimeLayout captureRuntimeLayout() {
                MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
                ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

                MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
                MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

                List<MemoryPoolSnapshot> memoryPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                                .map(this::toMemoryPoolSnapshot)
                                .sorted(Comparator.comparing(MemoryPoolSnapshot::type)
                                                .thenComparing(MemoryPoolSnapshot::name))
                                .toList();

                List<BufferPoolSnapshot> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
                                .stream()
                                .map(this::toBufferPoolSnapshot)
                                .sorted(Comparator.comparing(BufferPoolSnapshot::name))
                                .toList();

                return new JvmRuntimeLayout(
                                LocalDateTime.now(),
                                Runtime.getRuntime().availableProcessors(),
                                threadMXBean.getThreadCount(),
                                threadMXBean.getPeakThreadCount(),
                                toUsageSnapshot("heap", heapUsage),
                                toUsageSnapshot("non-heap", nonHeapUsage),
                                memoryPools,
                                bufferPools,
                                List.of(
                                                "程序计数器、虚拟机栈、本地方法栈属于线程私有。",
                                                "堆和方法区属于线程共享。",
                                                "JDK 8 以后常说的方法区实现主要是 Metaspace，不再是永久代。",
                                                "Direct Buffer 不在 Java 堆里，但依然会影响整个进程的内存压力。"));
        }

        /**
         * 模拟一轮“标记-清理”垃圾回收。
         *
         * <p>
         * 为什么我选 mark-sweep 作为第一课，而不是直接讲 G1 / ZGC：
         *
         * <p>
         * 1. mark-sweep 是 tracing GC 的最基本逻辑。
         *
         * <p>
         * 2. 你先理解“从 GC Roots 出发做可达性分析”，后面学分代、复制、压缩才不飘。
         *
         * <p>
         * 3. 直接跳进 G1、ZGC，容易只背名词，不理解为什么要这么设计。
         *
         * <p>
         * 这次模拟对象图时，我复用了推荐系统训练场景中的对象语义：
         * 训练线程、Spring 单例、SparkSession、训练数据集、写库批次、过期缓存。
         * 这样你读代码时不会脱离项目上下文。
         */
        public GcSimulationReport simulateMarkSweepForTrainerJob() {
                HeapGraph graph = buildTrainerHeapGraph();
                MarkPhaseResult markResult = markReachableObjects(graph);
                SweepPhaseResult sweepResult = sweepUnreachableObjects(graph, markResult.liveObjectIds());

                log.info(
                                "[JVM-Learning] 完成一轮标记清理模拟, roots={}, liveObjects={}, reclaimedObjects={}, reclaimedBytes={}",
                                graph.rootIds().size(),
                                markResult.liveObjectIds().size(),
                                sweepResult.reclaimedObjects().size(),
                                sweepResult.reclaimedBytes());

                return new GcSimulationReport(
                                "Mark-Sweep",
                                """
                                                这是一轮教学模拟，不会替代 HotSpot GC。
                                                它表达的是：从 GC Roots 出发进行可达性分析，先标记活对象，再清理不可达对象。
                                                纯 mark-sweep 的问题是会留下内存碎片，所以真实 JVM 往往会继续引入压缩、分代或分区设计。
                                                """.trim(),
                                graph.rootIds().stream()
                                                .map(graph.objects()::get)
                                                .map(this::toObjectSnapshot)
                                                .toList(),
                                graph.objects().values().stream()
                                                .map(object -> toObjectSnapshot(object,
                                                                markResult.liveObjectIds().contains(object.id())))
                                                .sorted(Comparator.comparing(HeapObjectSnapshot::reachable).reversed()
                                                                .thenComparing(HeapObjectSnapshot::id))
                                                .toList(),
                                sweepResult.reclaimedObjects().stream()
                                                .map(this::toObjectSnapshot)
                                                .toList(),
                                sweepResult.reclaimedBytes(),
                                List.of(
                                                "GC Roots 在 JVM 中通常来自线程栈、静态字段、JNI 引用、活动线程等。",
                                                "对象之间形成环并不可怕，只要整个环从 roots 不可达，依旧可以被回收。",
                                                "纯标记清理只解决“哪些对象该死”，不解决“活对象如何整理得更连续”。",
                                                "真实系统里更常见的问题不是 GC 不工作，而是对象被错误持有，导致逻辑内存泄漏。"));
        }

        private MemoryPoolSnapshot toMemoryPoolSnapshot(MemoryPoolMXBean pool) {
                return new MemoryPoolSnapshot(
                                pool.getName(),
                                pool.getType() == MemoryType.HEAP ? "HEAP" : "NON_HEAP",
                                toUsageSnapshot(pool.getName(), pool.getUsage()));
        }

        private BufferPoolSnapshot toBufferPoolSnapshot(BufferPoolMXBean pool) {
                return new BufferPoolSnapshot(
                                pool.getName(),
                                pool.getCount(),
                                pool.getMemoryUsed(),
                                pool.getTotalCapacity());
        }

        private MemoryUsageSnapshot toUsageSnapshot(String name, MemoryUsage usage) {
                if (usage == null) {
                        return new MemoryUsageSnapshot(name, -1L, -1L, -1L, -1L);
                }
                return new MemoryUsageSnapshot(
                                name,
                                usage.getInit(),
                                usage.getUsed(),
                                usage.getCommitted(),
                                usage.getMax());
        }

        /**
         * 构建一张“教学堆对象图”。
         *
         * <p>
         * 这张图不是为了还原 HotSpot 的真实内部结构，而是为了把抽象 GC 原理
         * 翻译成你在这个项目里能立刻理解的对象关系。
         *
         * <p>
         * 这里专门造了三类对象：
         *
         * <p>
         * 1. Root：模拟线程栈、Spring 单例等 GC Roots。
         *
         * <p>
         * 2. Live Object：训练中仍然被引用的对象。
         *
         * <p>
         * 3. Garbage Object：旧缓存、旧数据包装器、孤儿版本信息，已经没人引用。
         *
         * <p>
         * 后两类对象混在同一张图里，正好可以直观看出“可达”和“不可达”的差异。
         */
        private HeapGraph buildTrainerHeapGraph() {
                Map<String, HeapObject> objects = new LinkedHashMap<>();
                Set<String> rootIds = new LinkedHashSet<>();

                addObject(objects, "root-thread-als", "GC Root", "ALS 训练线程栈", 64);
                addObject(objects, "root-spring-als", "GC Root", "Spring 单例：AlsTrainingService", 96);
                addObject(objects, "root-spring-hot", "GC Root", "Spring 单例：HotRecallSnapshotService", 96);
                addObject(objects, "root-driver-thread", "GC Root", "Spark Driver 线程栈", 64);

                rootIds.add("root-thread-als");
                rootIds.add("root-spring-als");
                rootIds.add("root-spring-hot");
                rootIds.add("root-driver-thread");

                addObject(objects, "spark-session", "Heap", "ALS 训练中的 SparkSession", 4 * 1024 * 1024);
                addObject(objects, "preference-dataset", "Heap", "训练数据集：user_item_preference", 24 * 1024 * 1024);
                addObject(objects, "recommendation-dataset", "Heap", "ALS 推荐结果数据集", 20 * 1024 * 1024);
                addObject(objects, "parquet-break-lineage", "Heap", "切断 lineage 的临时 parquet 元数据", 512 * 1024);
                addObject(objects, "jdbc-write-batch", "Heap", "写入 user_cf_recall 的 JDBC 批次", 2 * 1024 * 1024);
                addObject(objects, "model-version-state", "Heap", "当前模型版本状态", 8 * 1024);
                addObject(objects, "hot-recall-sql-plan", "Heap", "热门召回快照 SQL 计划", 128 * 1024);
                addObject(objects, "runtime-status-dto", "Heap", "训练状态 DTO 对象图", 32 * 1024);

                addObject(objects, "stale-dataset-wrapper", "Heap", "上一次训练遗留的废弃 Dataset 包装对象", 16 * 1024 * 1024);
                addObject(objects, "old-hot-cache", "Heap", "无人引用的旧热门缓存", 6 * 1024 * 1024);
                addObject(objects, "orphan-version-map", "Heap", "无人引用的旧版本 Map", 512 * 1024);

                link(objects, "root-thread-als", "runtime-status-dto");
                link(objects, "root-spring-als", "model-version-state");
                link(objects, "root-spring-als", "spark-session");
                link(objects, "root-spring-hot", "hot-recall-sql-plan");
                link(objects, "root-driver-thread", "spark-session");

                link(objects, "spark-session", "preference-dataset");
                link(objects, "spark-session", "recommendation-dataset");
                link(objects, "recommendation-dataset", "parquet-break-lineage");
                link(objects, "recommendation-dataset", "jdbc-write-batch");
                link(objects, "runtime-status-dto", "model-version-state");

                return new HeapGraph(objects, rootIds);
        }

        /**
         * 标记阶段。
         *
         * <p>
         * 核心逻辑只有一句话：
         * 从 GC Roots 出发，能走到的对象都标记为存活。
         *
         * <p>
         * 这里用一个栈做深度优先遍历。
         * 你完全可以换成队列做广度优先遍历，结果不会变；
         * 因为我们关心的是“是否可达”，不是遍历顺序。
         *
         * <p>
         * 这也是为什么现代 JVM 不依赖简单引用计数：
         * 只要做 roots tracing，就不会被循环引用误伤。
         */
        private MarkPhaseResult markReachableObjects(HeapGraph graph) {
                Set<String> liveObjectIds = new LinkedHashSet<>();
                Deque<String> stack = new ArrayDeque<>(graph.rootIds());

                while (!stack.isEmpty()) {
                        String objectId = stack.pop();
                        if (!liveObjectIds.add(objectId)) {
                                continue;
                        }

                        HeapObject object = graph.objects().get(objectId);
                        if (object == null) {
                                continue;
                        }

                        for (String referenceId : object.referenceIds()) {
                                stack.push(referenceId);
                        }
                }

                return new MarkPhaseResult(liveObjectIds);
        }

        /**
         * 清理阶段。
         *
         * <p>
         * 所有没有被上一轮标记到的对象，都被视为垃圾对象。
         *
         * <p>
         * 这里我故意只做“清理”，没有做“压缩”。
         * 这样你才能理解纯 mark-sweep 的缺陷：
         *
         * <p>
         * 1. 垃圾能清掉，但活对象位置不连续。
         *
         * <p>
         * 2. 容易产生内存碎片。
         *
         * <p>
         * 3. 大对象分配时可能因为找不到足够大的连续空间而受影响。
         */
        private SweepPhaseResult sweepUnreachableObjects(HeapGraph graph, Set<String> liveObjectIds) {
                List<HeapObject> reclaimedObjects = graph.objects().values().stream()
                                .filter(object -> !liveObjectIds.contains(object.id()))
                                .filter(object -> !"GC Root".equals(object.region()))
                                .sorted(Comparator.comparing(HeapObject::retainedBytes).reversed())
                                .toList();

                long reclaimedBytes = reclaimedObjects.stream()
                                .mapToLong(HeapObject::retainedBytes)
                                .sum();

                return new SweepPhaseResult(reclaimedObjects, reclaimedBytes);
        }

        private HeapObjectSnapshot toObjectSnapshot(HeapObject object) {
                return toObjectSnapshot(object, true);
        }

        private HeapObjectSnapshot toObjectSnapshot(HeapObject object, boolean reachable) {
                return new HeapObjectSnapshot(
                                object.id(),
                                object.region(),
                                object.label(),
                                object.retainedBytes(),
                                reachable,
                                List.copyOf(object.referenceIds()));
        }

        private void addObject(
                        Map<String, HeapObject> objects,
                        String id,
                        String region,
                        String label,
                        long retainedBytes) {
                objects.put(id, new HeapObject(id, region, label, retainedBytes, new LinkedHashSet<>()));
        }

        private void link(Map<String, HeapObject> objects, String fromId, String toId) {
                HeapObject source = objects.get(fromId);
                if (source == null) {
                        throw new IllegalArgumentException("缺少源对象: " + fromId);
                }
                if (!objects.containsKey(toId)) {
                        throw new IllegalArgumentException("缺少目标对象: " + toId);
                }
                source.referenceIds().add(toId);
        }

        /**
         * 当前 JVM 运行时布局快照。
         */
        public record JvmRuntimeLayout(
                        LocalDateTime capturedAt,
                        int availableProcessors,
                        int liveThreadCount,
                        int peakThreadCount,
                        MemoryUsageSnapshot heap,
                        MemoryUsageSnapshot nonHeap,
                        List<MemoryPoolSnapshot> memoryPools,
                        List<BufferPoolSnapshot> bufferPools,
                        List<String> learningNotes) {
        }

        /**
         * 通用内存使用快照。
         */
        public record MemoryUsageSnapshot(
                        String name,
                        long initBytes,
                        long usedBytes,
                        long committedBytes,
                        long maxBytes) {
        }

        /**
         * 单个内存池快照。
         *
         * <p>
         * 不同 GC 下池名称会变化，比如你可能看到 Eden、Survivor、Old、Metaspace、
         * Code Cache 等名字。
         */
        public record MemoryPoolSnapshot(
                        String name,
                        String type,
                        MemoryUsageSnapshot usage) {
        }

        /**
         * 直接缓冲区池快照。
         *
         * <p>
         * 它不在 Java 堆里，但在排查真实内存问题时经常是关键线索。
         */
        public record BufferPoolSnapshot(
                        String name,
                        long bufferCount,
                        long memoryUsedBytes,
                        long totalCapacityBytes) {
        }

        /**
         * 一轮 GC 教学模拟的最终结果。
         */
        public record GcSimulationReport(
                        String algorithm,
                        String explanation,
                        List<HeapObjectSnapshot> gcRoots,
                        List<HeapObjectSnapshot> heapObjects,
                        List<HeapObjectSnapshot> reclaimedObjects,
                        long reclaimedBytes,
                        List<String> learningNotes) {

                /**
                 * 生成一个更适合快速观察的摘要视图。
                 */
                public Map<String, Object> toSummaryMap() {
                        Map<String, Object> summary = new LinkedHashMap<>();
                        summary.put("algorithm", algorithm);
                        summary.put("gcRootCount", gcRoots.size());
                        summary.put("heapObjectCount", heapObjects.size());
                        summary.put("liveObjectCount",
                                        heapObjects.stream().filter(HeapObjectSnapshot::reachable).count());
                        summary.put("reclaimedObjectCount", reclaimedObjects.size());
                        summary.put("reclaimedBytes", reclaimedBytes);
                        summary.put(
                                        "largestReclaimedObjects",
                                        reclaimedObjects.stream()
                                                        .limit(3)
                                                        .map(snapshot -> snapshot.id() + ":" + snapshot.retainedBytes())
                                                        .collect(Collectors.toList()));
                        return summary;
                }
        }

        /**
         * 教学用堆对象快照。
         */
        public record HeapObjectSnapshot(
                        String id,
                        String region,
                        String label,
                        long retainedBytes,
                        boolean reachable,
                        List<String> outgoingReferences) {
        }

        /**
         * 内部堆图结构。
         */
        private record HeapGraph(
                        Map<String, HeapObject> objects,
                        Set<String> rootIds) {
        }

        /**
         * 内部堆对象节点。
         */
        private record HeapObject(
                        String id,
                        String region,
                        String label,
                        long retainedBytes,
                        Set<String> referenceIds) {
        }

        /**
         * 标记阶段结果。
         */
        private record MarkPhaseResult(Set<String> liveObjectIds) {
        }

        /**
         * 清理阶段结果。
         */
        private record SweepPhaseResult(
                        List<HeapObject> reclaimedObjects,
                        long reclaimedBytes) {
        }
}
