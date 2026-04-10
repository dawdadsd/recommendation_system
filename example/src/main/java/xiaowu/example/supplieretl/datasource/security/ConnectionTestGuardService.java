package xiaowu.example.supplieretl.datasource.security;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.infrastructure.config.ConnectionTestSecurityProperties;

@Service
public class ConnectionTestGuardService {

  private static final String AWS_METADATA_IP = "169.254.169.254";

  private final ConnectionTestSecurityProperties properties;
  private final ConnectionTargetDescriptorResolver targetResolver;
  private final Map<String, Deque<Instant>> counters = new ConcurrentHashMap<>();

  public ConnectionTestGuardService(
      ConnectionTestSecurityProperties properties,
      ConnectionTargetDescriptorResolver targetResolver) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver must not be null");
  }

  public ConnectionSecurityAuditContext inspectTransient(
      HttpServletRequest request,
      DataSourceType type,
      DataSourceConfig config) {
    return buildContext(resolveActorId(request), resolveClientIp(request), null, type, resolveTargets(type, config));
  }

  public ConnectionSecurityAuditContext inspectSaved(
      HttpServletRequest request,
      DataSourceConnection connection) {
    return buildContext(
        resolveActorId(request),
        resolveClientIp(request),
        connection.getId(),
        connection.getType(),
        resolveTargets(connection.getType(), connection.getConfig()));
  }

  public ConnectionSecurityAuditContext inspectExcel(HttpServletRequest request) {
    return new ConnectionSecurityAuditContext(
        resolveActorId(request),
        resolveClientIp(request),
        null,
        DataSourceType.EXCEL,
        List.of("excel-upload"),
        List.of());
  }

  public String resolveClientIp(HttpServletRequest request) {
    if (request == null) {
      return "unknown";
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  public String resolveActorId(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String userId = normalizeHeader(request.getHeader("X-User-Id"));
    if (userId != null) {
      return userId;
    }
    return normalizeHeader(request.getHeader("X-Actor-Id"));
  }

  public void enforce(ConnectionSecurityAuditContext context) {
    String clientIp = context.clientIp();
    DataSourceType type = context.dataSourceType();
    ConnectionTestSecurityProperties.RateLimit limit = properties.getRateLimit();
    checkRate("ip:" + clientIp, limit.getMaxRequestsPerClientIp(), limit.getWindowSeconds(),
        "Too many connection test requests from client IP: " + clientIp);

    if (type == DataSourceType.EXCEL) {
      return;
    }

    List<ResolvedTarget> targets = rebuildTargets(context);
    for (ResolvedTarget target : targets) {
      validateTarget(type, target);
      String targetKey = "target:" + type.name() + ":" + target.fingerprint();
      checkRate(targetKey, limit.getMaxRequestsPerResolvedTarget(), limit.getWindowSeconds(),
          "Too many repeated requests to target: " + target.rawValue());
      String compositeKey = "ip-target:" + clientIp + ":" + type.name() + ":" + target.fingerprint();
      checkRate(compositeKey, limit.getMaxRequestsPerClientIpTarget(), limit.getWindowSeconds(),
          "Too many repeated requests from the same client to target: " + target.rawValue());
    }
  }

  private List<ResolvedTarget> resolveTargets(DataSourceType type, DataSourceConfig config) {
    List<ConnectionTargetDescriptor> descriptors = targetResolver.resolve(type, config);
    List<ResolvedTarget> resolved = new ArrayList<>();
    for (ConnectionTargetDescriptor descriptor : descriptors) {
      try {
        InetAddress[] addresses = InetAddress.getAllByName(descriptor.host());
        List<InetAddress> sorted = Arrays.stream(addresses)
            .sorted(Comparator.comparing(InetAddress::getHostAddress))
            .toList();
        resolved.add(new ResolvedTarget(descriptor, sorted));
      } catch (UnknownHostException ex) {
        throw new IllegalArgumentException("Failed to resolve target host: " + descriptor.host(), ex);
      }
    }
    return List.copyOf(resolved);
  }

  private ConnectionSecurityAuditContext buildContext(
      String actorId,
      String clientIp,
      Long connectionId,
      DataSourceType type,
      List<ResolvedTarget> targets) {
    List<String> targetSummaries = targets.stream()
        .map(ResolvedTarget::rawValue)
        .toList();
    List<String> resolvedAddresses = targets.stream()
        .flatMap(target -> target.addresses().stream())
        .map(InetAddress::getHostAddress)
        .distinct()
        .sorted()
        .toList();
    return new ConnectionSecurityAuditContext(
        actorId,
        clientIp,
        connectionId,
        type,
        targetSummaries,
        resolvedAddresses);
  }

  private List<ResolvedTarget> rebuildTargets(ConnectionSecurityAuditContext context) {
    if (context.dataSourceType() == DataSourceType.EXCEL) {
      return List.of();
    }
    List<ResolvedTarget> rebuilt = new ArrayList<>();
    for (String rawTarget : context.targetSummaries()) {
      ConnectionTargetDescriptor descriptor = inferDescriptor(rawTarget, context.dataSourceType());
      try {
        InetAddress[] addresses = InetAddress.getAllByName(descriptor.host());
        List<InetAddress> sorted = Arrays.stream(addresses)
            .sorted(Comparator.comparing(InetAddress::getHostAddress))
            .toList();
        rebuilt.add(new ResolvedTarget(descriptor, sorted));
      } catch (UnknownHostException ex) {
        throw new IllegalArgumentException("Failed to resolve target host: " + descriptor.host(), ex);
      }
    }
    return List.copyOf(rebuilt);
  }

  private ConnectionTargetDescriptor inferDescriptor(String rawTarget, DataSourceType type) {
    return switch (type) {
      case KAFKA -> parseHostPort(rawTarget, 9092, rawTarget);
      case REDIS -> parseHostPort(rawTarget, 6379, rawTarget);
      case MYSQL -> parseMysqlTarget(rawTarget);
      case EXCEL -> new ConnectionTargetDescriptor("excel-upload", 1, rawTarget);
    };
  }

  private ConnectionTargetDescriptor parseMysqlTarget(String rawTarget) {
    if (!rawTarget.startsWith("jdbc:mysql://")) {
      throw new IllegalArgumentException("Invalid MySQL target: " + rawTarget);
    }
    String withoutPrefix = rawTarget.substring("jdbc:mysql://".length());
    int slashIndex = withoutPrefix.indexOf('/');
    String authority = slashIndex >= 0 ? withoutPrefix.substring(0, slashIndex) : withoutPrefix;
    return parseHostPort(authority, 3306, rawTarget);
  }

  private ConnectionTargetDescriptor parseHostPort(String authority, int defaultPort, String rawValue) {
    String[] parts = authority.split(":");
    if (parts.length == 1) {
      return new ConnectionTargetDescriptor(parts[0], defaultPort, rawValue);
    }
    return new ConnectionTargetDescriptor(parts[0], Integer.parseInt(parts[1]), rawValue);
  }

  private String normalizeHeader(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private void validateTarget(DataSourceType type, ResolvedTarget target) {
    String host = target.descriptor().host().toLowerCase();
    if ("localhost".equals(host) || host.endsWith(".local")) {
      throw new IllegalArgumentException("Localhost or .local targets are not allowed for " + type);
    }

    if (matchesAllowlist(host, target.addresses())) {
      return;
    }

    for (InetAddress address : target.addresses()) {
      if (AWS_METADATA_IP.equals(address.getHostAddress())) {
        throw new IllegalArgumentException("Metadata service address is not allowed: " + AWS_METADATA_IP);
      }
      if (properties.getAllowlist().isPublicOnly() && !isPublicAddress(address)) {
        throw new IllegalArgumentException(
            "Non-public target address is not allowed: " + target.rawValue() + " -> " + address.getHostAddress());
      }
    }
  }

  private boolean matchesAllowlist(String host, List<InetAddress> addresses) {
    ConnectionTestSecurityProperties.Allowlist allowlist = properties.getAllowlist();
    if (allowlist.getExactHosts().stream().anyMatch(entry -> entry.equalsIgnoreCase(host))) {
      return true;
    }
    if (allowlist.getDomainSuffixes().stream().anyMatch(suffix -> host.endsWith(suffix.toLowerCase()))) {
      return true;
    }
    for (InetAddress address : addresses) {
      for (String cidr : allowlist.getCidrBlocks()) {
        if (matchesCidr(address, cidr)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isPublicAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    if (address instanceof Inet6Address inet6Address && inet6Address.isIPv4CompatibleAddress()) {
      return false;
    }
    return true;
  }

  private boolean matchesCidr(InetAddress address, String cidr) {
    String[] parts = cidr.split("/");
    if (parts.length != 2) {
      return false;
    }
    try {
      InetAddress baseAddress = InetAddress.getByName(parts[0]);
      int prefixLength = Integer.parseInt(parts[1]);
      byte[] candidateBytes = address.getAddress();
      byte[] baseBytes = baseAddress.getAddress();
      if (candidateBytes.length != baseBytes.length) {
        return false;
      }

      BigInteger candidate = new BigInteger(1, candidateBytes);
      BigInteger base = new BigInteger(1, baseBytes);
      int totalBits = candidateBytes.length * 8;
      BigInteger mask = prefixLength == 0
          ? BigInteger.ZERO
          : BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE)
              .shiftRight(totalBits - prefixLength)
              .shiftLeft(totalBits - prefixLength);
      return candidate.and(mask).equals(base.and(mask));
    } catch (Exception ex) {
      return false;
    }
  }

  private void checkRate(String key, int limit, int windowSeconds, String message) {
    Instant now = Instant.now();
    Instant threshold = now.minusSeconds(windowSeconds);
    Deque<Instant> deque = counters.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst().isBefore(threshold)) {
        deque.pollFirst();
      }
      if (deque.size() >= limit) {
        throw new ConnectionTestRateLimitException(message);
      }
      deque.addLast(now);
    }
  }

  private record ResolvedTarget(
      ConnectionTargetDescriptor descriptor,
      List<InetAddress> addresses) {

    String rawValue() {
      return descriptor.rawValue();
    }

    String fingerprint() {
      StringBuilder builder = new StringBuilder();
      builder.append(descriptor.port()).append('|');
      addresses.forEach(address -> builder.append(address.getHostAddress()).append(','));
      return builder.toString();
    }
  }
}
