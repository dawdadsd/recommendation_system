package xiaowu.backed.infrastructure.ai.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 4 种 role：
 * - system: AI 人设（用户看不到）
 * - user: 用户输入
 * - assistant: AI 回复（可能含 tool_calls，此时 content 为 null）
 * - tool: 工具执行结果（通过 tool_call_id 配对）
 *
 * @author xiaowu
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
    String role,
    String content,
    @JsonProperty("tool_calls") List<ToolCall> toolCalls,
    @JsonProperty("tool_call_id") String toolCallId) {
  public static ChatMessage system(String content) {
    return new ChatMessage("system", content, null, null);
  }

  public static ChatMessage user(String content) {
    return new ChatMessage("user", content, null, null);
  }

  public static ChatMessage assistant(String content) {
    return new ChatMessage("assistant", content, null, null);
  }

  public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
    return new ChatMessage("assistant", null, toolCalls, null);
  }

  public static ChatMessage tool(String toolCallId, String result) {
    return new ChatMessage("tool", result, null, toolCallId);
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }
}
