# osheeep-server 生产运维手册

更新日期：2026-07-13

## 1. 服务说明

`osheeep-server` 是 `www.osheeep.com/api/**` 的唯一正式后端。

```text
微信小程序 / 网站
        │
        │ HTTPS :443
        ▼
Nginx
        │
        │ http://127.0.0.1:8080
        ▼
osheeep-server
        │
        ├── MySQL :3306
        ├── Redis :6379
        └── RabbitMQ :5672
```

系统服务名：`osheeep-server`

正式 JAR：`/opt/osheeep-server/osheeep-server.jar`

本机健康检查：`http://127.0.0.1:8080/actuator/health`

公网 API 根地址：`https://www.osheeep.com/api`

## 2. 目录和文件用途

```text
/opt/osheeep-server/
├── osheeep-server.jar
├── osheeep-server.env
├── OPERATIONS.md
├── backup/
└── logs/
```

- `osheeep-server.jar`：当前正式版本。systemd 始终启动这个固定文件。
- `osheeep-server.env`：生产环境配置和密钥。归属 `root:osheeep`，权限 `640`，不得提交 Git。
- `OPERATIONS.md`：本手册。
- `backup/`：部署前备份的历史 JAR，默认只保留最近 10 个。
- `logs/application.log`：当前应用日志。
- `logs/application.YYYY-MM-DD.N.log.gz`：滚动压缩日志，最多保留 14 天，总容量不超过 300MB。

其他系统文件：

- Java：`/opt/java/jre-21/`
- systemd：`/etc/systemd/system/osheeep-server.service`
- Nginx：`/etc/nginx/conf.d/osheeep.com.conf`
- Nginx 备份：`/opt/deploy-backups/`

## 3. 本地打包

要求：JDK 21、Maven 3.9+。

```bash
cd /Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

先执行完整测试：

```bash
mvn test
```

只有测试全部通过后才能打包：

```bash
mvn clean package -DskipTests
```

生成文件：

```text
target/osheeep-server-0.0.1-SNAPSHOT.jar
```

确认 JAR 存在且非空：

```bash
test -s target/osheeep-server-0.0.1-SNAPSHOT.jar
ls -lh target/osheeep-server-0.0.1-SNAPSHOT.jar
```

## 4. 上传新 JAR

先把新版本上传到服务器临时路径，不要直接覆盖正式 JAR：

```bash
scp target/osheeep-server-0.0.1-SNAPSHOT.jar \
  root@82.156.49.122:/tmp/osheeep-server.jar.new
```

登录服务器：

```bash
ssh root@82.156.49.122
```

确认临时文件存在：

```bash
ls -lh /tmp/osheeep-server.jar.new
```

## 5. 部署前检查

```bash
systemctl status osheeep-server --no-pager
curl --fail --silent http://127.0.0.1:8080/actuator/health
df -h /opt
free -h
ls -lt /opt/osheeep-server/backup | head
```

确认：

1. 当前服务状态已知。
2. `/tmp/osheeep-server.jar.new` 已上传。
3. `/opt` 有足够磁盘空间。
4. 至少有一个可用的旧 JAR，或这是首次部署。
5. 生产配置文件存在且权限正确。

```bash
ls -l /opt/osheeep-server/osheeep-server.env
```

期望权限：

```text
-rw-r----- root osheeep
```

## 6. 替换正式版本

进入应用目录：

```bash
cd /opt/osheeep-server
```

如果正式 JAR 已存在，先按当前时间备份：

```bash
cp -a osheeep-server.jar \
  "backup/osheeep-server-$(date +%Y%m%d-%H%M%S).jar"
```

设置新 JAR 权限：

```bash
chown osheeep:osheeep /tmp/osheeep-server.jar.new
chmod 644 /tmp/osheeep-server.jar.new
```

在同一文件系统内原子替换：

```bash
mv /tmp/osheeep-server.jar.new osheeep-server.jar
```

重启并检查：

```bash
systemctl restart osheeep-server
systemctl status osheeep-server --no-pager
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

`systemctl restart osheeep-server` 只会重启固定路径的当前 JAR，不负责上传、备份或替换文件。

## 7. 部署后验证

检查本机服务：

```bash
systemctl is-active osheeep-server
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

检查公网 API：

```bash
curl -sS -D - https://www.osheeep.com/api/dinner/recipes
```

未登录访问受保护接口时可以返回 `401` 或 `403`，但响应不得再来自旧 Node 后端，也不得出现 `requestedPath` 和“请求的路径不存在”。

检查静态网站：

```bash
curl --fail --silent --output /dev/null https://www.osheeep.com/
```

## 8. 常用 systemd 命令

```bash
systemctl start osheeep-server
systemctl stop osheeep-server
systemctl restart osheeep-server
systemctl status osheeep-server
systemctl enable osheeep-server
systemctl disable osheeep-server
```

修改 `/etc/systemd/system/osheeep-server.service` 后必须执行：

```bash
systemctl daemon-reload
systemctl restart osheeep-server
```

## 9. 日志查看

实时应用日志：

```bash
tail -f /opt/osheeep-server/logs/application.log
```

最近 200 行：

```bash
tail -n 200 /opt/osheeep-server/logs/application.log
```

搜索错误：

```bash
rg -n 'ERROR|Exception|Caused by' /opt/osheeep-server/logs/application.log
```

如果服务器没有 `rg`，使用：

```bash
grep -nE 'ERROR|Exception|Caused by' /opt/osheeep-server/logs/application.log
```

查看指定时间段的 systemd 日志：

```bash
journalctl -u osheeep-server --since '30 minutes ago' --no-pager
journalctl -u osheeep-server -f
```

## 10. Nginx 运维

配置文件：

```text
/etc/nginx/conf.d/osheeep.com.conf
```

修改前备份：

```bash
cp -a /etc/nginx/conf.d/osheeep.com.conf \
  "/opt/deploy-backups/osheeep.com.conf.$(date +%Y%m%d-%H%M%S)"
```

检查并平滑重载：

```bash
nginx -t
systemctl reload nginx
systemctl status nginx --no-pager
```

`/api/` 必须反向代理到：

```text
http://127.0.0.1:8080
```

## 11. 手工回滚 JAR

列出备份：

```bash
cd /opt/osheeep-server
ls -lt backup/osheeep-server-*.jar
```

保存失败版本：

```bash
mv osheeep-server.jar \
  "backup/osheeep-server-failed-$(date +%Y%m%d-%H%M%S).jar"
```

复制一个已确认正常的版本：

```bash
cp -a backup/osheeep-server-YYYYMMDD-HHmmss.jar osheeep-server.jar
chown osheeep:osheeep osheeep-server.jar
chmod 644 osheeep-server.jar
```

重启并验证：

```bash
systemctl restart osheeep-server
systemctl status osheeep-server --no-pager
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

应用回滚只使用历史 Spring Boot JAR，不再回退到旧 Node 后端。

## 12. 清理旧备份

默认保留最近 10 个正常备份：

```bash
cd /opt/osheeep-server/backup
ls -1t osheeep-server-[0-9]*.jar | tail -n +11 | xargs -r rm -f
```

带 `failed` 的文件用于故障排查，确认不需要后再手工删除。

## 13. 修改生产环境变量

配置文件：

```text
/opt/osheeep-server/osheeep-server.env
```

修改前备份：

```bash
cp -a /opt/osheeep-server/osheeep-server.env \
  "/opt/osheeep-server/backup/osheeep-server.env.$(date +%Y%m%d-%H%M%S)"
```

使用 root 编辑，禁止把内容复制到聊天、日志或 Git：

```bash
vi /opt/osheeep-server/osheeep-server.env
chown root:osheeep /opt/osheeep-server/osheeep-server.env
chmod 640 /opt/osheeep-server/osheeep-server.env
systemctl restart osheeep-server
```

必须配置的变量名称：

```text
SPRING_PROFILES_ACTIVE
OSHEEEP_DB_HOST
OSHEEEP_DB_PORT
OSHEEEP_DB_NAME
OSHEEEP_DB_USERNAME
OSHEEEP_DB_PASSWORD
OSHEEEP_REDIS_HOST
OSHEEEP_REDIS_PORT
OSHEEEP_REDIS_PASSWORD
OSHEEEP_RABBITMQ_HOST
OSHEEEP_RABBITMQ_PORT
OSHEEEP_RABBITMQ_USERNAME
OSHEEEP_RABBITMQ_PASSWORD
OSHEEEP_RABBITMQ_VHOST
OSHEEEP_JWT_SECRET
OSHEEEP_WECHAT_APP_ID
OSHEEEP_WECHAT_APP_SECRET
OSHEEEP_DINNER_INVITE_SECRET
```

## 14. 常见故障

### 14.1 端口 8080 被占用

```bash
ss -ltnp | grep ':8080 '
```

确认占用进程后再处理，不要直接结束未知服务。

### 14.2 数据库连接失败

```bash
systemctl status mysqld --no-pager
ss -ltnp | grep ':3306 '
grep -nE 'CommunicationsException|Access denied' \
  /opt/osheeep-server/logs/application.log
```

检查 DB host、端口、数据库名、账号权限和密码是否与生产配置一致。

### 14.3 Redis 或 RabbitMQ 连接失败

```bash
systemctl status redis --no-pager
systemctl status rabbitmq-server --no-pager
ss -ltnp | grep -E ':6379 |:5672 '
```

### 14.4 微信登录失败

```bash
grep -nE 'Wechat|code2session|40029|40125' \
  /opt/osheeep-server/logs/application.log
```

检查微信 AppID 和 AppSecret 是否属于当前小程序；不得记录或转发登录 code、session key 和 AppSecret。

### 14.5 内存不足

```bash
free -h
journalctl -k --since '1 hour ago' | grep -iE 'out of memory|killed process'
systemctl status osheeep-server --no-pager
```

服务默认 JVM 上限为 256MB。不要在不了解整机内存的情况下直接增大。

### 14.6 Nginx 返回 502

```bash
systemctl is-active osheeep-server
ss -ltnp | grep ':8080 '
curl --fail --silent http://127.0.0.1:8080/actuator/health
nginx -t
tail -n 100 /var/log/nginx/error.log
```

## 15. 禁止事项

- 不得把 `osheeep-server.env`、密码或密钥提交 Git。
- 不得在命令行输出、日志或聊天中打印生产配置内容。
- 不得跳过测试直接部署。
- 不得直接覆盖正式 JAR 而不先备份。
- 不得删除唯一可用的备份。
- 不得修改已经执行过的 Flyway 迁移；数据库变化必须新增迁移版本。
- 不得在 `nginx -t` 失败时重载 Nginx。
- 不得把 `/api/` 重新指向已废弃的 Node 后端。
