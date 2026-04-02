package xiaowu.example.supplieretl.application.port;

import java.time.LocalDateTime;

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
            LocalDateTime pulledAt) {
    }
}
