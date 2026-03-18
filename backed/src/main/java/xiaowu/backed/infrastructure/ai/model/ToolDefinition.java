package xiaowu.backed.infrastructure.ai.model;

import java.util.Map;

/**
 * 工具定义 —— 告诉 AI "你有哪些工具可以用"
 *
 * 对应 OpenAI 请求中的 tools[] 元素：
 * { "type": "function", "function": { "name": "...", "description": "...",
 * "parameters": {...} } }
 *
 * parameters 是 JSON Schema 格式，告诉 AI 这个工具接受什么参数。
 * AI 会根据 description 和 parameters 自行判断何时调用、传什么参数。
 * 所以 description 写得越清晰，AI 的调用越准确。
 *
 * @author xiaowu
 */
public record ToolDefinition(
    String type,
    FunctionDefinition function) {
  public record FunctionDefinition(
      String name,
      String description,
      Map<String, Object> parameters) {
  }

  public static final ToolDefinition function(String name, String description, Map<String, Object> parameters) {
    return new ToolDefinition("function", new FunctionDefinition(name, description, parameters));
  }

}
