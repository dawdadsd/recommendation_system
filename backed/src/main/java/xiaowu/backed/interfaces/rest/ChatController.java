package xiaowu.backed.interfaces.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.service.ChatService;

/**
 * AI 对话控制器 —— 基于用户画像的个性化聊天入口
 *
 * <p>使用方式（curl）：
 * <pre>
 *   curl -X POST "http://localhost:8922/api/chat?userId=1001" \
 *        -H "Content-Type: text/plain" \
 *        -d "帮我推荐一款手机"
 * </pre>
 *
 * <p>为什么 message 用 RequestBody 纯文本而非 JSON DTO？
 * 这是最小可用版本（MVP），目的是快速验证"画像注入 AI 对话"的效果。
 * 后续迭代再加多轮对话、会话管理、JSON 格式等功能。
 * 当前阶段，curl 能直接传文本最方便。
 *
 * @author xiaowu
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    /**
     * 发送消息给 AI，获取基于画像的个性化回复
     *
     * @param userId  用户 ID（URL 参数）
     * @param message 用户消息（请求体纯文本）
     * @return AI 回复文本
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @RequestParam Long userId,
            @RequestBody String message) {

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse(false, "消息不能为空", null));
        }

        try {
            var reply = chatService.chat(userId, message);
            return ResponseEntity.ok(new ChatResponse(true, "success", reply));
        } catch (Exception e) {
            log.error("[Chat] AI call failed: userId={}", userId, e);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse(false, "AI 服务暂时不可用: " + e.getMessage(), null));
        }
    }

    /**
     * 为什么用 record 而非 Map？
     * 类型安全、自文档化、序列化结果稳定。
     * Map<String, Object> 无法保证字段名一致性。
     */
    private record ChatResponse(boolean success, String message, String reply) {
    }
}
