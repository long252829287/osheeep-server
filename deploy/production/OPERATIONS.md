# osheeep-server 生产运维手册

更新日期：2026-07-15

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

当前生产配置文件：

```text
/etc/nginx/conf.d/osheeep.com.conf
/etc/nginx/conf.d/00-osheeep-rate-limit.conf
/etc/nginx/snippets/osheeep-api-locations.conf
```

`osheeep.com.conf` 引用 `osheeep-api-locations.conf`；限流共享区必须由按名称先加载的
`00-osheeep-rate-limit.conf` 定义。三个文件均为 `root:root`、权限 `644`，
`/etc/nginx/snippets/` 为 `root:root`、权限 `755`。

### 10.1 生产阈值、429 与健康检查

- 微信登录 `POST /api/auth/wechat`：`12r/m`，`burst=5`，超限返回 HTTP `429`。
- 其他 `/api/`：`300r/m`，`burst=60`；同一来源并发连接上限为 `20`，超限均返回 HTTP `429`。
- 公网健康检查 `https://www.osheeep.com/healthz` 反向代理到
  `http://127.0.0.1:8080/actuator/health`，正常正文包含 `UP`。

2026-07-15 的有界验收结果：登录请求为 `400 × 6`、`429 × 2`；通用未登录 API
请求为 `401 × 74`、`429 × 6`。生产 Nginx 与 `osheeep-server` 均保持
`active`，`/healthz` 返回 `UP`。这些请求只用于本次上线验收；日常检查不得重复发送
429 负载。

### 10.2 备份与精确安全回滚

当前活动配置的完整上线前备份为：

```text
/opt/deploy-backups/osheeep-rate-limit-20260715-105138
```

两次先前尝试均已安全回滚，其证据目录保留为：

```text
/opt/deploy-backups/osheeep-rate-limit-20260715-104127
/opt/deploy-backups/osheeep-rate-limit-20260715-104636
```

只有管理员明确授权回退限流和公网健康检查时，才在服务器执行以下精确命令。命令先恢复
虚拟主机，再按备份标记恢复或删除两个新增文件，最后才测试和重载；不要删除上述证据目录。

```bash
set -Eeuo pipefail
backup_dir=/opt/deploy-backups/osheeep-rate-limit-20260715-105138

install -o root -g root -m 644 \
  "$backup_dir/osheeep.com.conf" \
  /etc/nginx/conf.d/osheeep.com.conf

if test -f "$backup_dir/00-osheeep-rate-limit.conf.absent"; then
  rm -f /etc/nginx/conf.d/00-osheeep-rate-limit.conf
else
  install -o root -g root -m 644 \
    "$backup_dir/00-osheeep-rate-limit.conf" \
    /etc/nginx/conf.d/00-osheeep-rate-limit.conf
fi

if test -f "$backup_dir/osheeep-api-locations.conf.absent"; then
  rm -f /etc/nginx/snippets/osheeep-api-locations.conf
else
  install -o root -g root -m 644 \
    "$backup_dir/osheeep-api-locations.conf" \
    /etc/nginx/snippets/osheeep-api-locations.conf
fi

if test -f "$backup_dir/snippets-directory.absent"; then
  rmdir /etc/nginx/snippets
fi

nginx -t
systemctl reload nginx
systemctl is-active nginx
systemctl is-active osheeep-server
curl --fail --silent http://127.0.0.1:8080/actuator/health
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  https://www.osheeep.com/
```

这会恢复上线前状态并移除公网 `/healthz` 和限流，因此不能作为普通排障步骤执行。如果
`nginx -t` 失败，不得重载 Nginx。

### 10.3 日常 Nginx 检查

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

### 10.4 腾讯云监控与邮件通知

2026-07-15 已确认以下四个固定名称资源存在、启用并完成绑定：

- CAT 任务 `小家开饭-生产健康检查`：GET
  `https://www.osheeep.com/healthz`，每 5 分钟一次，北京和深圳共 2 个节点；要求
  HTTP `200` 且正文包含 `UP`。
- CAT 策略 `小家开饭-健康检查失败`：正确率 `< 100%`，连续 2 个数据点触发，
  只筛选上述健康检查目标。
- 轻量应用服务器策略 `小家开饭-CVM基础故障`：这只是经管理员授权保留固定名称的
  最小等价策略，不是 CVM 事件告警。它只绑定 1 台生产轻量实例，CPU、内存、磁盘
  利用率分别 `> 95%` 且连续 5 个 1 分钟数据点满足时告警；外部不可用由 CAT 覆盖。
- 通知模板 `小家开饭-生产告警邮件`：触发与恢复通知均开启，周一至周日全天生效，
  仅使用邮件渠道，并绑定上述两个策略。接收人是 1 个已有且已验证的联系人；手册不记录
  邮箱全文。

腾讯云通知模板列表、详情和编辑页都没有测试发送入口，管理员已接受不做测试收件。不得
声称测试邮件已发送或收到；首次真实触发和首次真实恢复时再分别核对邮件是否送达。不得为
制造告警而停止、重启或扰动生产服务。

2026-07-15 检查时 CAT 还是剩余 15 天的试用版。管理员选择保留试用，未授权付费升级；
试用到期后 CAT 拨测会自动暂停，必须在到期前重新决定付费续用或替代方案。轻量实例的
CPU、内存、磁盘策略不依赖 CAT 试用。

## 11. 数据库备份与临时恢复验证

只有管理员明确授权后才能执行恢复验证。生产库只允许通过应用账号做一致性只读导出；临时库由本机 root socket 创建和删除。不要全局 `export MYSQL_PWD`，否则应用密码会干扰本机 root socket 认证。

加载变量但不打印内容：

```bash
set -a
source /opt/osheeep-server/osheeep-server.env
set +a
```

创建权限受限的压缩备份：

```bash
ts=$(date +%Y%m%d-%H%M%S)
backup_dir=/opt/osheeep-server/backup/db
backup="$backup_dir/osheeep-${ts}.sql.gz"
mkdir -p "$backup_dir"
chmod 750 "$backup_dir"
umask 077

MYSQL_PWD="$OSHEEEP_DB_PASSWORD" mysqldump \
  --host="$OSHEEEP_DB_HOST" \
  --port="$OSHEEEP_DB_PORT" \
  --user="$OSHEEEP_DB_USERNAME" \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=OFF \
  --no-tablespaces \
  "$OSHEEEP_DB_NAME" | gzip -9 > "$backup"

gzip -t "$backup"
sha256sum "$backup"
stat -c '%a %U:%G %s' "$backup"
```

备份文件期望为 `600 root:root`。不得把数据库密码写进命令输出、脚本文件或手册。

恢复到一次性临时库：

```bash
verify_db="osheeep_restore_verify_${ts//-/}"

cleanup_restore_verify() {
  unset MYSQL_PWD
  mysql -NBe "DROP DATABASE IF EXISTS \`$verify_db\`"
}
trap cleanup_restore_verify EXIT

unset MYSQL_PWD
mysql -NBe "CREATE DATABASE \`$verify_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
gzip -dc "$backup" | mysql "$verify_db"
```

从 `information_schema.tables` 取得生产库全部基础表，分别用应用账号和本机 root 查询每张表的 `COUNT(*)`；所有表的行数和 `flyway_schema_history` 记录数必须一致。任何一张表不一致都视为恢复验证失败，不能勾选上线清单。

验证结束后删除临时库并确认没有残留：

```bash
cleanup_restore_verify
trap - EXIT

remaining=$(mysql -NBe \
  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'osheeep_restore_verify_%'")
printf 'TEMP_DATABASES_REMAINING=%s\n' "$remaining"
```

期望结果：

```text
TEMP_DATABASES_REMAINING=0
```

2026-07-15 已完成一次实测：17 张表、43 行数据和 4 条 Flyway 记录逐项一致，临时库残留为 0。备份文件路径和哈希应记录在当次交接材料中，但不得记录任何凭据。

## 12. 手工回滚 JAR

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

## 13. 清理旧备份

默认保留最近 10 个正常备份：

```bash
cd /opt/osheeep-server/backup
ls -1t osheeep-server-[0-9]*.jar | tail -n +11 | xargs -r rm -f
```

带 `failed` 的文件用于故障排查，确认不需要后再手工删除。

## 14. 修改生产环境变量

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

## 15. 常见故障

### 15.1 端口 8080 被占用

```bash
ss -ltnp | grep ':8080 '
```

确认占用进程后再处理，不要直接结束未知服务。

### 15.2 数据库连接失败

```bash
systemctl status mysqld --no-pager
ss -ltnp | grep ':3306 '
grep -nE 'CommunicationsException|Access denied' \
  /opt/osheeep-server/logs/application.log
```

检查 DB host、端口、数据库名、账号权限和密码是否与生产配置一致。

### 15.3 Redis 或 RabbitMQ 连接失败

```bash
systemctl status redis --no-pager
systemctl status rabbitmq-server --no-pager
ss -ltnp | grep -E ':6379 |:5672 '
```

### 15.4 微信登录失败

```bash
grep -nE 'Wechat|code2session|40029|40125' \
  /opt/osheeep-server/logs/application.log
```

检查微信 AppID 和 AppSecret 是否属于当前小程序；不得记录或转发登录 code、session key 和 AppSecret。

### 15.5 内存不足

```bash
free -h
journalctl -k --since '1 hour ago' | grep -iE 'out of memory|killed process'
systemctl status osheeep-server --no-pager
```

服务默认 JVM 上限为 256MB。不要在不了解整机内存的情况下直接增大。

### 15.6 Nginx 返回 502

```bash
systemctl is-active osheeep-server
ss -ltnp | grep ':8080 '
curl --fail --silent http://127.0.0.1:8080/actuator/health
nginx -t
tail -n 100 /var/log/nginx/error.log
```

## 16. 禁止事项

- 不得把 `osheeep-server.env`、密码或密钥提交 Git。
- 不得在命令行输出、日志或聊天中打印生产配置内容。
- 不得跳过测试直接部署。
- 不得直接覆盖正式 JAR 而不先备份。
- 不得删除唯一可用的备份。
- 不得修改已经执行过的 Flyway 迁移；数据库变化必须新增迁移版本。
- 不得在 `nginx -t` 失败时重载 Nginx。
- 不得把 `/api/` 重新指向已废弃的 Node 后端。
