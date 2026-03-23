**如果你的服务只有 1 个实例，就不可能做到真正“服务不动、无感更新”。**
你至少要有：

- 2 个应用实例
- 1 个反向代理 / 负载均衡器
- 健康检查
- 可回滚的发布方式

对你这个项目，最适合的路线不是一上来 Kubernetes，而是：

**GitHub Actions + Docker + Nginx + Blue-Green/滚动发布**

---

**你这个仓库适合怎么做**

你现在是一个 Maven 多模块项目，里面至少有：

- `backed`：在线服务，端口 `8922`
- `recommendation-trainer`：训练服务，端口 `8923`
- `example`：教学模块，端口 `8924`

真正需要 CI/CD 无损更新的，优先是 `backed`。
`example` 不需要上生产。
`recommendation-trainer` 往往是后台任务型服务，可以和在线服务分开部署。

---

**完整 CI/CD 应该长这样**

1. 你 push 到 GitHub 的 `main` 分支
2. GitHub Actions 自动执行 CI
3. CI 做这些事
   - 拉代码
   - 安装 JDK
   - `mvn test` / `mvn package`
   - 构建 Docker 镜像
   - 推送到镜像仓库，比如 `GHCR` 或阿里云 ACR
4. CD 开始
   - SSH 到服务器
   - 拉取新镜像
   - 启动一个“新版本容器”，先不切流量
   - 对新容器做健康检查
   - 健康检查通过后，Nginx 切流到新版本
   - 旧版本容器延迟下线
5. 如果新版本失败
   - 不切流
   - 保留旧版本继续服务
   - 自动回滚

---

**最关键的点：怎么做到不停机**

推荐你用 **Blue-Green 发布**。

比如同一个服务准备两套容器：

- `app-blue`
- `app-green`

Nginx 永远只代理其中一套。

发布流程：

1. 当前线上是 `blue`
2. 新代码来了，启动 `green`
3. 检查 `green` 的 `/actuator/health`
4. 健康后，把 Nginx upstream 从 `blue` 切到 `green`
5. reload Nginx
6. 确认流量正常后，停掉 `blue`

这样用户几乎无感。

---

**为什么我不建议你先用“直接覆盖 jar + systemctl restart”**

因为那样一定会有瞬时中断。

即使只有 1~2 秒，也不叫真正无损更新。

那种方案只能算：

**自动部署，不算零停机部署。**

---

**你现在最实用的技术选型**

对于你当前项目，我建议：

- CI：`GitHub Actions`
- 镜像仓库：`GHCR`
- 部署：`Docker Compose`
- 切流：`Nginx`
- 发布策略：`Blue-Green`
- 健康检查：`Spring Boot Actuator`
- 数据库变更：`Flyway`
- 回滚：保留上一个镜像 tag

---

**你需要补的基础设施**

### 1. Dockerfile

每个要部署的服务都要有 Dockerfile。
比如 `backed` 一个，`recommendation-trainer` 一个。

### 2. Actuator 健康检查

你已经有 actuator 依赖，继续确保：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

上线切流前检查：

```bash
curl http://127.0.0.1:18922/actuator/health
```

### 3. Nginx

Nginx 不直接写死一个端口，而是转发到当前活动版本。

### 4. Docker Compose

至少两套服务定义，或者发布脚本动态起不同名字容器。

### 5. GitHub Secrets

你需要在 GitHub 配：

- `SERVER_HOST`
- `SERVER_USER`
- `SERVER_SSH_KEY`
- `GHCR_TOKEN`

---

**一套推荐目录结构**

你可以后面补成这样：

```text
.github/workflows/
  backed-ci-cd.yml

deploy/
  nginx/
    backed.conf
  scripts/
    deploy-backed.sh
  compose/
    docker-compose.backed.yml

backed/
  Dockerfile
```

---

**GitHub Actions 的职责**

CI/CD workflow 大概做这些：

```yaml
name: backed-ci-cd

on:
  push:
    branches: [main]
    paths:
      - "backed/**"
      - "pom.xml"

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - checkout
      - setup-java
      - mvn -pl backed -am clean package -DskipTests
      - docker build -t ghcr.io/yourname/backed:${{ github.sha }} ./backed
      - docker push ghcr.io/yourname/backed:${{ github.sha }}

  deploy:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - ssh to server
      - run deploy-backed.sh ${{ github.sha }}
```

---

**服务器上的 deploy 脚本怎么做**

核心逻辑：

1. 判断当前线上是 `blue` 还是 `green`
2. 新版本部署到另一边
3. 等待健康检查通过
4. 改 Nginx upstream
5. reload Nginx
6. 停掉旧实例

伪代码：

```bash
NEW_TAG=$1

docker pull ghcr.io/yourname/backed:$NEW_TAG

docker rm -f backed-green || true
docker run -d \
  --name backed-green \
  -p 18922:8922 \
  ghcr.io/yourname/backed:$NEW_TAG

for i in {1..30}; do
  curl -f http://127.0.0.1:18922/actuator/health && break
  sleep 2
done

# 切 nginx 到 green
cp /etc/nginx/upstreams/backed-green.conf /etc/nginx/conf.d/backed-upstream.conf
nginx -s reload

docker rm -f backed-blue || true
```

真实脚本会再严谨一点，但核心就是这个。

---

**Nginx 配置思路**

当前 upstream 指向活动版本：

```nginx
upstream backed_upstream {
    server 127.0.0.1:18922;
}
server {
    listen 80;
    location / {
        proxy_pass http://backed_upstream;
    }
}
```

如果切回 `blue`，就把 upstream 改到另一个端口，比如 `28922`。

---

**数据库怎么处理，不然会把零停机搞假了**

零停机部署经常死在数据库变更上。

你必须遵守：

**先做向后兼容 schema，再发应用，再删旧字段。**

不要这样干：

- 新版本一上线就依赖新字段
- 旧版本一跑就报错
- 切流一半直接炸

正确做法：

1. 先加字段 / 加表 / 加索引
2. 新旧版本都兼容
3. 发布新应用
4. 稳定后再删旧字段

这就是为什么我建议你加 `Flyway`。

---

**你这个项目最实际的三阶段路线**

### 第一阶段：先做“自动构建 + 自动部署”，允许 1~2 秒中断

适合你现在先跑通

- GitHub Actions
- 打 jar / docker
- SSH 到服务器
- `docker compose up -d --build` 或 `systemctl restart`

### 第二阶段：做真正零停机

- Docker
- Nginx
- Blue-Green
- 健康检查切流

### 第三阶段：做完整生产级

- Flyway
- 监控告警
- 日志
- 自动回滚
- 多环境 dev/test/prod
- 可观测性
