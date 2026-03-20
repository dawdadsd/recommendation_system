package xiaowu.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * example 模块启动入口。
 *
 * <p>这个模块专门放学习型、演示型代码，不和在线服务、训练服务混在一起。
 * 这样做的好处有两个：
 *
 * <p>1. 学习代码可以大胆表达概念，不会污染生产模块职责。
 *
 * <p>2. 你可以直接运行这个 Application，只关注当前学习主题，不需要带上
 * Kafka、Spark、MySQL 等额外依赖场景。
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
