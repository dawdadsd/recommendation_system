package xiaowu.backed.infrastructure.ai.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI API 响应体
 *
 * 对应 POST /v1/chat/completions 的 response body。
 * 只提取我们需要的字段，忽略 usage、created 等。
 *
 * 为什么用 @JsonIgnoreProperties(ignoreUnknown = true)？
 * OpenAI 经常在响应中加新字段，如果不忽略未知字段，
 * 每次 API 更新都会导致反序列化失败。防御性设计。
 *
 * @author xiaowu
 */
public record ChatResponse(
    List<Choice> choices) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Choice(
      ChatMessage message,
      @JsonProperty("finish_reason") String finishReason) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Usage(
      @JsonProperty("prompt_tokens") int promptTokens,
      @JsonProperty("completion_tokens") int completionTokens,
      @JsonProperty("total_tokens") int totalTokens) {
  }

  public ChatMessage getMessage() {
    return choices.get(0).message;
  }

  public boolean requireToolCall() {
    return getMessage().hasToolCalls();
  }
}
