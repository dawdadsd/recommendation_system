package xiaowu.example.supplieretl.application.etl.parser;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;

/**
 * ERP 原始负载解析分发器。
 *
 * <p>
 * 负责根据 RawDataEvent 中的 erpType，选择合适的 SupplierRawPayloadParser。
 * 它本身不解析具体 JSON，只负责路由到对应的解析器实现。
 */
@Component
public class SupplierRawPayloadParserDispatcher {

  private final List<SupplierRawPayloadParser> parsers;

  public SupplierRawPayloadParserDispatcher(List<SupplierRawPayloadParser> parsers) {
    Objects.requireNonNull(parsers, "parsers");
    if (parsers.isEmpty()) {
      throw new IllegalArgumentException("parsers must not be empty");
    }
    this.parsers = List.copyOf(parsers);
  }

  public List<NormalizedSupplierRecord> parse(RawDataEvent event) {
    Objects.requireNonNull(event, "event");
    SupplierRawPayloadParser parser = resolveParser(event.erpType());
    return parser.parse(event);
  }

  private SupplierRawPayloadParser resolveParser(String erpType) {
    for (SupplierRawPayloadParser parser : parsers) {
      if (parser.supports(erpType)) {
        return parser;
      }
    }
    throw new IllegalArgumentException("No parser found for ERP type: " + erpType);
  }
}
