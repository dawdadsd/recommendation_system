package xiaowu.example.supplieretl.datasource.infrastructure.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "data-source.security")
public class ConnectionTestSecurityProperties {

  private final Allowlist allowlist = new Allowlist();
  private final RateLimit rateLimit = new RateLimit();

  public Allowlist getAllowlist() {
    return allowlist;
  }

  public RateLimit getRateLimit() {
    return rateLimit;
  }

  public static class Allowlist {
    private boolean publicOnly = true;
    private List<String> exactHosts = new ArrayList<>();
    private List<String> domainSuffixes = new ArrayList<>();
    private List<String> cidrBlocks = new ArrayList<>();

    public boolean isPublicOnly() {
      return publicOnly;
    }

    public void setPublicOnly(boolean publicOnly) {
      this.publicOnly = publicOnly;
    }

    public List<String> getExactHosts() {
      return exactHosts;
    }

    public void setExactHosts(List<String> exactHosts) {
      this.exactHosts = exactHosts == null ? new ArrayList<>() : new ArrayList<>(exactHosts);
    }

    public List<String> getDomainSuffixes() {
      return domainSuffixes;
    }

    public void setDomainSuffixes(List<String> domainSuffixes) {
      this.domainSuffixes = domainSuffixes == null ? new ArrayList<>() : new ArrayList<>(domainSuffixes);
    }

    public List<String> getCidrBlocks() {
      return cidrBlocks;
    }

    public void setCidrBlocks(List<String> cidrBlocks) {
      this.cidrBlocks = cidrBlocks == null ? new ArrayList<>() : new ArrayList<>(cidrBlocks);
    }
  }

  public static class RateLimit {
    private int windowSeconds = 300;
    private int maxRequestsPerClientIp = 20;
    private int maxRequestsPerResolvedTarget = 10;
    private int maxRequestsPerClientIpTarget = 3;

    public int getWindowSeconds() {
      return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
      this.windowSeconds = windowSeconds;
    }

    public int getMaxRequestsPerClientIp() {
      return maxRequestsPerClientIp;
    }

    public void setMaxRequestsPerClientIp(int maxRequestsPerClientIp) {
      this.maxRequestsPerClientIp = maxRequestsPerClientIp;
    }

    public int getMaxRequestsPerResolvedTarget() {
      return maxRequestsPerResolvedTarget;
    }

    public void setMaxRequestsPerResolvedTarget(int maxRequestsPerResolvedTarget) {
      this.maxRequestsPerResolvedTarget = maxRequestsPerResolvedTarget;
    }

    public int getMaxRequestsPerClientIpTarget() {
      return maxRequestsPerClientIpTarget;
    }

    public void setMaxRequestsPerClientIpTarget(int maxRequestsPerClientIpTarget) {
      this.maxRequestsPerClientIpTarget = maxRequestsPerClientIpTarget;
    }
  }
}
