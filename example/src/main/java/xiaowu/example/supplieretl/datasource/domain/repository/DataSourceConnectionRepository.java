package xiaowu.example.supplieretl.datasource.domain.repository;

import java.util.List;
import java.util.Optional;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;

/**
 * 数据源连接仓库
 */
public interface DataSourceConnectionRepository {
  /**
   * 根据ID查找数据源连接
   *
   * @param id 数据源连接的唯一标识符
   * @return 包含数据源连接的Optional对象，如果未找到则返回Optional.empty()
   */
  Optional<DataSourceConnection> findById(Long id);

  /**
   * 根据连接名称查找数据源连接
   *
   * @param connectionName 数据源连接的名称，应该是唯一的
   * @return 包含数据源连接的Optional对象，如果未找到则返回Optional.empty()
   */
  Optional<DataSourceConnection> findByConnectionName(String connectionName);

  /**
   * 查找所有数据源连接
   *
   * @return 包含所有数据源连接的列表，如果没有连接则返回一个空列表
   */
  List<DataSourceConnection> findAll();

  /**
   * 保存数据源连接
   *
   * @param connection 要保存的数据源连接对象
   * @return 保存后的数据源连接对象
   */

  DataSourceConnection save(DataSourceConnection connection);
}
