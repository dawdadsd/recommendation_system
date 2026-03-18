package xiaowu.backed.infrastructure.ai.client;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.infrastructure.ai.model.ChatRequest;
import xiaowu.backed.infrastructure.ai.model.ChatResponse;

@Slf4j
@Component
public class OpenAiClient {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String baseUrl;

  public OpenAiClient(
      ObjectMapper objectMapper,
      @Value("${ai.api-key}") String apiKey,
      @Value("${ai.base-url}") String baseUrl,
      @Value("${ai.proxy.host:}") String proxyHost,
      @Value("${ai.proxy.port:0}") int proxyPort) {
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    var build = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10));
    if (!proxyHost.isBlank() && proxyPort > 0) {
      build.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
    }
    this.httpClient = build.build();
  }

  public ChatResponse chat(ChatRequest request) {
    try {
      var jsonBody = objectMapper.writeValueAsString(request);
      log.debug("[Ai-Client] request body : {}", jsonBody);
      var httpRequest = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/v1/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
          .timeout(Duration.ofSeconds(60))
          .build();
      var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (httpResponse.statusCode() != 200) {
        log.error("API error : " + httpResponse.statusCode());
        throw new RuntimeException(
            "OpenAI API error : " + httpResponse.statusCode() + " - " + new String(httpResponse.body()));
      }
      var response = objectMapper.readValue(httpResponse.body(),
          ChatResponse.class);
      return response;
    } catch (Exception e) {
      log.error("request failed : {}", e.getMessage(), e);
      throw new RuntimeException("request failed : " + e.getMessage());
    }
  }
}
