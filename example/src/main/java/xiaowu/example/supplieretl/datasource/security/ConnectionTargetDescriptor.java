package xiaowu.example.supplieretl.datasource.security;

public record ConnectionTargetDescriptor(
    String host,
    int port,
    String rawValue) {

  public ConnectionTargetDescriptor {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    rawValue = rawValue == null ? host + ":" + port : rawValue;
  }
}
