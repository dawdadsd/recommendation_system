package xiaowu.example.supplieretl.datasource.domain.repository;

import java.util.List;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnectionTestLog;

public interface DataSourceConnectionTestLogRepository {

  DataSourceConnectionTestLog save(DataSourceConnectionTestLog log);

  List<DataSourceConnectionTestLog> findRecentByConnectionId(Long connectionId, int limit);
}
