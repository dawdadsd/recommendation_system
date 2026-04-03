package xiaowu.example.supplieretl.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import xiaowu.example.supplieretl.domain.entity.SupplierConnection;

public interface SupplierConnectionRepository {
        /**
         * 根据供应商ID查询供应商连接信息。
         *
         * @param supplierId 供应商ID，必须不为null
         * @return 包含供应商连接信息的Optional，如果未找到则返回Optional.empty()
         */
        Optional<SupplierConnection> findBySupplierId(Long supplierId);

        List<SupplierConnection> findAll();

        /**
         * 查询可拉取的供应商连接信息。
         *
         * @param now   当前时间，必须不为null
         * @param limit 查询结果的最大数量
         * @return 可拉取的供应商连接信息列表
         */
        List<SupplierConnection> findSchedulableConnections(LocalDateTime now, int limit);

        /**
         * 保存供应商连接信息。
         *
         * @param connection 供应商连接信息，必须不为null
         * @return 保存成功返回true，否则返回false
         */
        boolean save(SupplierConnection connection);

        /**
         * 尝试获取供应商连接的租约。
         *
         * @param supplierId      供应商ID，必须不为null
         * @param expectedVersion 期望的版本号
         * @param now             当前时间，必须不为null
         * @param leaseUntil      租约到期时间，必须不为null
         * @param updatedAt       更新时间，必须不为null
         * @return 获取租约成功返回true，否则返回false
         */

        boolean tryAcquireLease(
                        Long supplierId,
                        long expectedVersion,
                        LocalDateTime now,
                        LocalDateTime leaseUntil,
                        LocalDateTime updatedAt);

        /**
         * 释放供应商连接的租约。
         *
         * @param supplierId      供应商ID，必须不为null
         * @param expectedVersion 期望的版本号
         * @param updatedAt       更新时间，必须不为null
         * @return 释放租约成功返回true，否则返回false
         */

        boolean releaseLease(
                        Long supplierId,
                        long expectedVersion,
                        LocalDateTime updatedAt);

        /**
         * 标记拉取成功。
         *
         * @param supplierId      供应商ID，必须不为null
         * @param expectedVersion 期望的版本号
         * @param lastCursor      上一次拉取的游标
         * @param lastSuccessAt   上一次拉取成功的时间，必须不为null
         * @param nextPullAt      下一次拉取的时间，必须不为null
         * @param updatedAt       更新时间，必须不为null
         * @return 标记成功返回true，否则返回false
         */
        boolean markPullSuccess(
                        Long supplierId,
                        long expectedVersion,
                        String lastCursor,
                        LocalDateTime lastSuccessAt,
                        LocalDateTime nextPullAt,
                        LocalDateTime updatedAt);

        /**
         * 标记拉取失败。
         *
         * @param supplierId      供应商ID，必须不为null
         * @param expectedVersion 期望的版本号
         * @param lastErrorAt     上一次拉取失败的时间，必须不为null
         * @param nextPullAt      下一次拉取的时间，必须不为null
         * @param retryCount      当前重试次数
         * @param updatedAt       更新时间，必须不为null
         * @return 标记成功返回true，否则返回false
         */
        boolean markPullFailure(
                        Long supplierId,
                        long expectedVersion,
                        LocalDateTime lastErrorAt,
                        LocalDateTime nextPullAt,
                        int retryCount,
                        LocalDateTime updatedAt);

        /**
         * 将供应商连接状态置为 SUSPENDED（Auth 鉴权失败时调用）。
         *
         * <p>
         * SUSPENDED 状态下调度器不再选取该供应商，需人工修复凭证后手动恢复为 ACTIVE。
         *
         * @param supplierId      供应商 ID
         * @param expectedVersion 期望版本号（乐观锁）
         * @param lastErrorAt     失败时间
         * @param updatedAt       更新时间
         * @return true = 挂起成功，false = 版本冲突（已被其他线程处理）
         */
        boolean markSuspended(
                        Long supplierId,
                        long expectedVersion,
                        LocalDateTime lastErrorAt,
                        LocalDateTime updatedAt);
}
