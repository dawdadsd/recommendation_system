package xiaowu.example.supplieretl.datasource.security;

public class ConnectionTestRateLimitException extends RuntimeException {

  public ConnectionTestRateLimitException(String message) {
    super(message);
  }
}
