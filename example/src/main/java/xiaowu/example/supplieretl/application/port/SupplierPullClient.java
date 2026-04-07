package xiaowu.example.supplieretl.application.port;

import java.time.LocalDateTime;

/**
 * 供应商拉取客户端接口，定义了从供应商 ERP 系统拉取数据的抽象方法。
 */
public interface SupplierPullClient {

        PullResult pull(PullCommand command);

        record PullCommand(
                        Long supplierId,
                        String supplierCode,
                        String lastCursor,
                        int retryCount) {
        }

        record PullResult(
                        String nextCursor,
                        int recordCount,
                        LocalDateTime pulledAt,
                        String rawPayload) {
        }
}
