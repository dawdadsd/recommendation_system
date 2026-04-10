package xiaowu.example.supplieretl.datasource.application.port;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;

public interface ConnectionTester {

  DataSourceType supports();

  ConnectionTestResult test(DataSourceConfig config);
}
