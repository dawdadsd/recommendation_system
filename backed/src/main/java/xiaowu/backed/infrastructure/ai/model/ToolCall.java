package xiaowu.backed.infrastructure.ai.model;

/**
 * AI 返回的工具调用请求
 *
 * 对应 OpenAI 响应中的 tool_calls[] 元素：
 * { "id": "call_abc123", "type": "function", "function": { "name": "...",
 * "arguments": "..." } }
 *
 * id 是配对的关键：执行完工具后，必须用相同的 id 构造 tool 角色的消息，
 * AI 才知道这个结果对应哪个工具调用。
 *
 * @author xiaowu
 */
public record ToolCall(
    String id,
    String type,
    FunctionCall function) {
  public record FunctionCall(
      String name,
      String arguments) {
  }
}
