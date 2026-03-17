package xiaowu.backed.application.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 对话服务 —— 基于用户画像的个性化聊天
 *
 * <p>核心流程：
 * <pre>
 *   用户消息 + userId
 *       │
 *       ▼
 *   UserProfileService.buildUserPortrait(userId)  → System Prompt
 *       │
 *       ▼
 *   ChatClient.call(systemPrompt + userMessage)   → AI 回复
 * </pre>
 *
 * <p>为什么用 Spring AI 的 ChatClient 而非直接调 OpenAI HTTP API？
 * 1. 统一抽象：将来换模型（Claude、文心一言）只改配置，不改代码
 * 2. Spring 生态集成：自动重试、超时配置、可观测性
 * 3. 类型安全：Prompt/Message 对象比手拼 JSON 更安全
 *
 * @author xiaowu
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    /**
     * 为什么注入 ChatClient.Builder 而非 ChatClient？
     * Spring AI 推荐用 Builder 模式创建 ChatClient，
     * 这样可以在每次请求时设置不同的 system prompt，
     * 而不是全局共享一个固定的 system prompt。
     */
    private final ChatClient.Builder chatClientBuilder;
    private final UserProfileService userProfileService;

    /**
     * 基于用户画像的个性化对话
     *
     * <p>为什么每次请求都重新构建 ChatClient？
     * 不同用户的画像不同，System Prompt 不同。
     * ChatClient 是轻量对象，每次构建的开销可忽略。
     * 这比维护一个 Map<userId, ChatClient> 更简单且无内存泄漏风险。
     *
     * @param userId  用户 ID
     * @param message 用户消息
     * @return AI 回复文本
     */
    public String chat(Long userId, String message) {
        log.info("[Chat] ========== 收到对话请求 ==========");
        log.info("[Chat] userId={}, userMessage={}", userId, message);

        // Step 1: 构建用户画像
        log.info("[Chat] Step 1 → 查询用户画像...");
        var portrait = userProfileService.buildUserPortrait(userId);

        // Step 2: 组装 Prompt 发送给 AI
        log.info("[Chat] Step 2 → 调用 AI，System Prompt 长度={}, User Message 长度={}",
                portrait.length(), message.length());

        var startTime = System.currentTimeMillis();
        var response = chatClientBuilder.build()
                .prompt()
                .system(portrait)
                .user(message)
                .call()
                .content();
        var elapsed = System.currentTimeMillis() - startTime;

        // Step 3: 返回结果
        log.info("[Chat] Step 3 → AI 响应完成, 耗时={}ms, 回复长度={}",
                elapsed, response != null ? response.length() : 0);
        log.info("[Chat] AI 回复内容:\n{}", response);
        log.info("[Chat] ========== 对话请求处理完毕 userId={} ==========", userId);

        return response;
    }
}
