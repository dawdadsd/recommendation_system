package xiaowu.backed.infrastructure.ai.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 发给 OpenAI API 的请求体
 *
 * 对应 POST /v1/chat/completions 的 request body。
 * 本质就是：模型 + 消息历史 + 可用工具 + 生成参数。
 *
 * 为什么 tools 可以为 null？
 * 不是每次调用都需要工具。纯聊天场景不传 tools，AI 直接回复文本。
 * 只有需要 AI 查数据库、调接口时才传 tools 定义。
 *
 * @author xiaowu
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
    String model,
    List<ChatMessage> messages,
    List<ToolDefinition> tools,
    Double temperature,
    @JsonProperty("max_tokens") Integer maxTokens) {
  public static ChatRequest of(String model, List<ChatMessage> messages) {
    return new ChatRequest(model, messages, null, 0.7, 1024);
  }

  public static ChatRequest withTools(String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
    return new ChatRequest(model, messages, tools, 0.7, 1024);
  }
}
