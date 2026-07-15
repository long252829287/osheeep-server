# Production Rate Limit and Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tested Nginx API rate limits, expose a minimal `/healthz` endpoint, and configure Tencent Cloud external monitoring with email notifications to the existing contact address.

**Architecture:** Store reusable Nginx rate-zone and location snippets in the backend repository, test their exact contracts, then install them beside the existing production virtual host with backup-first rollback. Tencent Cloud CAT probes `/healthz` externally and sends trigger/recovery email through a verified Tencent Cloud notification template; CVM event alarms cover host-level failures.

**Tech Stack:** Nginx 1.26.3, Spring Boot Actuator, systemd, Maven/JUnit/AssertJ, Tencent Cloud Automated Testing (CAT), Tencent Cloud Observability alarm management.

## Global Constraints

- `/api/auth/wechat`: 12 requests per minute per source IP, burst 5, `nodelay`.
- Other `/api/`: 300 requests per minute per source IP, burst 60, `nodelay`.
- Maximum 20 concurrent API connections per source IP.
- Limit rejections return HTTP 429 and use `$binary_remote_addr`, never `X-Forwarded-For`.
- `/healthz` exposes only the basic Actuator health response.
- CAT runs every 5 minutes and alarms after 2 consecutive failed periods; recovery also notifies.
- Email reuses the existing privacy contact; no SMTP credentials are stored on the server.
- Back up every production Nginx file and require `nginx -t` before reload.
- Do not stop production to test monitoring.
- Preserve unrelated dirty-worktree changes and stage only files named by the active task.

---

### Task 1: Add Tested Nginx Configuration Assets

**Files:**
- Create: `deploy/production/nginx/00-osheeep-rate-limit.conf`
- Create: `deploy/production/nginx/osheeep-api-locations.conf`
- Modify: `src/test/java/com/osheeep/server/ProductionDeploymentContractTest.java`

**Interfaces:**
- Consumes: Spring Boot at `127.0.0.1:8080` and `/actuator/health`.
- Produces: One HTTP-context zone file and one server-context location file.

- [ ] **Step 1: Write the failing contract test**

Add to `ProductionDeploymentContractTest`:

```java
@Test
void nginxAssetsDefineApprovedRateLimitsAndHealthEndpoint() throws IOException {
    String zones = readRequired("deploy/production/nginx/00-osheeep-rate-limit.conf");
    String locations = readRequired("deploy/production/nginx/osheeep-api-locations.conf");

    assertThat(zones).contains("map $uri $osheeep_auth_limit_key");
    assertThat(zones).contains("/api/auth/wechat $binary_remote_addr;");
    assertThat(zones).contains("zone=osheeep_auth:10m rate=12r/m");
    assertThat(zones).contains("zone=osheeep_api:10m rate=300r/m");
    assertThat(zones).contains("limit_conn_zone $binary_remote_addr zone=osheeep_conn:10m;");
    assertThat(locations).contains("location = /healthz");
    assertThat(locations).contains("proxy_pass http://127.0.0.1:8080/actuator/health;");
    assertThat(locations).contains("limit_req zone=osheeep_auth burst=5 nodelay;");
    assertThat(locations).contains("limit_req zone=osheeep_api burst=60 nodelay;");
    assertThat(locations).contains("limit_conn osheeep_conn 20;");
    assertThat(locations).contains("limit_req_status 429;");
    assertThat(locations).contains("limit_conn_status 429;");
    assertThat(locations).doesNotContain("3000");
}
```

- [ ] **Step 2: Run the test and verify RED**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -Dtest=ProductionDeploymentContractTest test
```

Expected: FAIL because `deploy/production/nginx/00-osheeep-rate-limit.conf` is missing.

- [ ] **Step 3: Create the rate-zone file**

Create `deploy/production/nginx/00-osheeep-rate-limit.conf`:

```nginx
map $uri $osheeep_auth_limit_key {
    default "";
    /api/auth/wechat $binary_remote_addr;
}

limit_req_zone $osheeep_auth_limit_key zone=osheeep_auth:10m rate=12r/m;
limit_req_zone $binary_remote_addr zone=osheeep_api:10m rate=300r/m;
limit_conn_zone $binary_remote_addr zone=osheeep_conn:10m;
```

- [ ] **Step 4: Create the location file**

Create `deploy/production/nginx/osheeep-api-locations.conf`:

```nginx
location = /healthz {
    access_log off;
    proxy_pass http://127.0.0.1:8080/actuator/health;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /api/ {
    limit_req zone=osheeep_auth burst=5 nodelay;
    limit_req zone=osheeep_api burst=60 nodelay;
    limit_conn osheeep_conn 20;
    limit_req_status 429;
    limit_conn_status 429;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

- [ ] **Step 5: Verify GREEN and the full suite**

```bash
mvn -Dtest=ProductionDeploymentContractTest test
mvn test
git diff --check
```

Expected: focused PASS; full suite 102 tests, 0 failures/errors; diff check exits 0.

- [ ] **Step 6: Commit only Task 1 files**

```bash
git add deploy/production/nginx/00-osheeep-rate-limit.conf \
  deploy/production/nginx/osheeep-api-locations.conf \
  src/test/java/com/osheeep/server/ProductionDeploymentContractTest.java
git diff --cached --name-only
git commit -m "feat: add production nginx rate limits"
```

Expected: only those three paths are staged.

---

### Task 2: Deploy and Verify Production Nginx

**Files:**
- Install: `/etc/nginx/conf.d/00-osheeep-rate-limit.conf`
- Install: `/etc/nginx/snippets/osheeep-api-locations.conf`
- Modify: `/etc/nginx/conf.d/osheeep.com.conf`
- Backup: `/opt/deploy-backups/osheeep-rate-limit-$(date +%Y%m%d-%H%M%S)/`

**Interfaces:**
- Consumes: Task 1 files and the existing HTTPS virtual host.
- Produces: Public `/healthz`, 429 limits, and an exact rollback directory.

- [ ] **Step 1: Capture baseline**

```bash
systemctl is-active nginx
systemctl is-active osheeep-server
curl --fail --silent http://127.0.0.1:8080/actuator/health
curl --silent --output /dev/null --write-out '%{http_code}\n' https://www.osheeep.com/
nginx -t
```

Expected: services active, health `UP`, site 200, syntax successful.

- [ ] **Step 2: Build the new virtual host without changing TLS paths**

```bash
scp osheeep:/etc/nginx/conf.d/osheeep.com.conf /tmp/osheeep.com.conf.new
```

Use `apply_patch` on `/tmp/osheeep.com.conf.new` to replace the existing complete `location /api/ { ... }` block with:

```nginx
    # --- 小家开饭后端接口与健康检查 ---
    include /etc/nginx/snippets/osheeep-api-locations.conf;
```

Do not alter TLS directives, redirects, or the static `location /` block.

- [ ] **Step 3: Upload staged files and compare SHA-256**

```bash
scp deploy/production/nginx/00-osheeep-rate-limit.conf osheeep:/tmp/00-osheeep-rate-limit.conf.new
scp deploy/production/nginx/osheeep-api-locations.conf osheeep:/tmp/osheeep-api-locations.conf.new
scp /tmp/osheeep.com.conf.new osheeep:/tmp/osheeep.com.conf.new
```

Run `shasum -a 256` locally and `sha256sum` remotely for all three files. Stop on any mismatch.

- [ ] **Step 4: Back up, install, syntax-check, and reload**

Run this complete transaction on the server:

```bash
set -e
ts=$(date +%Y%m%d-%H%M%S)
backup_dir="/opt/deploy-backups/osheeep-rate-limit-${ts}"
mkdir -p "$backup_dir"
cp -a /etc/nginx/conf.d/osheeep.com.conf "$backup_dir/osheeep.com.conf"

if [ -e /etc/nginx/conf.d/00-osheeep-rate-limit.conf ]; then
  cp -a /etc/nginx/conf.d/00-osheeep-rate-limit.conf \
    "$backup_dir/00-osheeep-rate-limit.conf"
else
  touch "$backup_dir/00-osheeep-rate-limit.conf.absent"
fi

if [ -e /etc/nginx/snippets/osheeep-api-locations.conf ]; then
  cp -a /etc/nginx/snippets/osheeep-api-locations.conf \
    "$backup_dir/osheeep-api-locations.conf"
else
  touch "$backup_dir/osheeep-api-locations.conf.absent"
fi

install -o root -g root -m 644 /tmp/00-osheeep-rate-limit.conf.new \
  /etc/nginx/conf.d/00-osheeep-rate-limit.conf
install -o root -g root -m 644 /tmp/osheeep-api-locations.conf.new \
  /etc/nginx/snippets/osheeep-api-locations.conf
install -o root -g root -m 644 /tmp/osheeep.com.conf.new \
  /etc/nginx/conf.d/osheeep.com.conf

if ! nginx -t; then
  cp -a "$backup_dir/osheeep.com.conf" /etc/nginx/conf.d/osheeep.com.conf

  if [ -e "$backup_dir/00-osheeep-rate-limit.conf.absent" ]; then
    rm -f /etc/nginx/conf.d/00-osheeep-rate-limit.conf
  else
    cp -a "$backup_dir/00-osheeep-rate-limit.conf" \
      /etc/nginx/conf.d/00-osheeep-rate-limit.conf
  fi

  if [ -e "$backup_dir/osheeep-api-locations.conf.absent" ]; then
    rm -f /etc/nginx/snippets/osheeep-api-locations.conf
  else
    cp -a "$backup_dir/osheeep-api-locations.conf" \
      /etc/nginx/snippets/osheeep-api-locations.conf
  fi

  nginx -t
  exit 1
fi

systemctl reload nginx
printf 'BACKUP_DIR=%s\n' "$backup_dir"
```

Expected: syntax succeeds, Nginx reloads, and the exact backup directory is printed. A syntax failure restores all three previous states and exits before reload.

- [ ] **Step 5: Verify health and routing**

```bash
systemctl is-active nginx
systemctl is-active osheeep-server
curl --fail --silent https://www.osheeep.com/healthz
curl --silent --output /dev/null --write-out '%{http_code}\n' https://www.osheeep.com/
curl --silent --output /dev/null --write-out '%{http_code}\n' https://www.osheeep.com/api/dinner/recipes
```

Expected: active, active, health `UP`, 200, 401.

- [ ] **Step 6: Verify login 429 without a WeChat code**

```bash
for i in $(seq 1 8); do
  curl --silent --output /dev/null --write-out '%{http_code}\n' \
    -H 'Content-Type: application/json' -d '{}' \
    https://www.osheeep.com/api/auth/wechat
done
```

Expected: at least one 400, followed by at least one 429; no request contains a code.

- [ ] **Step 7: Verify general API 429 with bounded traffic**

```bash
for i in $(seq 1 65); do
  curl --silent --output /dev/null --write-out '%{http_code}\n' \
    https://www.osheeep.com/api/dinner/recipes
done
```

Expected: at least one 401, followed by at least one 429.

- [ ] **Step 8: Recheck logs and remove temporary uploads**

```bash
tail -n 100 /var/log/nginx/error.log
journalctl -u osheeep-server --since '15 minutes ago' --no-pager
rm -f /tmp/00-osheeep-rate-limit.conf.new \
  /tmp/osheeep-api-locations.conf.new /tmp/osheeep.com.conf.new
```

Expected: no configuration error, application ERROR/exception, or sensitive value.

---

### Task 3: Configure Tencent Cloud CAT and Email

**Files:**
- External state: Tencent Cloud Observability console
- External state: Existing contact mailbox

**Interfaces:**
- Consumes: Task 2 `https://www.osheeep.com/healthz`.
- Produces: External checks, trigger/recovery email, and CVM event alarms.

- [ ] **Step 1: Verify the alarm recipient**

Set the existing privacy contact email as a Tencent Cloud alarm recipient. If Tencent sends verification mail, pause for the administrator to click it; never request mailbox credentials.

- [ ] **Step 2: Create the CAT task**

Create a Port Performance task:

- Name: `小家开饭-生产健康检查`
- Method/URL: `GET https://www.osheeep.com/healthz`
- Frequency: 5 minutes
- Success: HTTP 200 and body contains `UP`
- Points: at least two mainland China nodes from different regions or carriers

Expected: first result succeeds.

- [ ] **Step 3: Create the notification template**

Create `小家开饭-生产告警邮件`: trigger and recovery, Monday-Sunday all day, email only, verified existing contact recipient.

- [ ] **Step 4: Create the CAT alert policy**

Create `小家开饭-健康检查失败`: CAT Port Performance, filtered to the task/domain, trigger after 2 consecutive failed 5-minute periods, using the email template.

- [ ] **Step 5: Create the CVM event policy**

Create `小家开饭-CVM基础故障` for only the production CVM instance, covering Ping unreachable, disk read-only, and available Tencent infrastructure failure events; bind the same template.

- [ ] **Step 6: Send a test notification**

Use Tencent Cloud's notification-template test feature. Do not stop any production service. Pause until the administrator confirms the existing contact mailbox received the email.

---

### Task 4: Record Evidence and Close the Checklist

**Files:**
- Modify: `deploy/production/OPERATIONS.md`
- Modify: `/Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-wx/docs/HANDOFF.md`
- Modify: `/Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-wx/docs/review-submission-checklist.md`
- Test: `src/test/java/com/osheeep/server/ProductionDeploymentContractTest.java`

**Interfaces:**
- Consumes: Task 2 backup/evidence and Task 3 policy names/email receipt.
- Produces: Reproducible guidance and an evidence-backed completed operations item.

- [ ] **Step 1: Add failing operations-manual assertions**

Add to the existing manual contract:

```java
assertThat(manual).contains("00-osheeep-rate-limit.conf");
assertThat(manual).contains("osheeep-api-locations.conf");
assertThat(manual).contains("https://www.osheeep.com/healthz");
assertThat(manual).contains("小家开饭-生产健康检查");
assertThat(manual).contains("小家开饭-健康检查失败");
assertThat(manual).contains("小家开饭-CVM基础故障");
```

- [ ] **Step 2: Run the test and verify RED**

```bash
mvn -Dtest=ProductionDeploymentContractTest test
```

Expected: FAIL because the manual lacks those deployed-state records.

- [ ] **Step 3: Update the operations manual**

Record installed paths, thresholds, 429 behavior, `/healthz`, Task 2 backup directory and rollback commands, all three Tencent names, trigger/recovery email, and the prohibition on stopping production for alert tests. Do not record the email address or credentials.

- [ ] **Step 4: Update handoff and checklist after email receipt**

Record the successful 429 tests, health endpoint, backup directory, CAT/policy state, and confirmed test-email receipt in `HANDOFF.md`.

Change the checklist item to:

```markdown
- [x] 限流、告警和故障联系人已确认；Nginx 登录/API/并发限制已验证返回 429，腾讯云外部健康拨测、CVM 事件告警、邮件触发与恢复通知均已启用，管理员已确认测试邮件送达现有联系邮箱。
```

Do not check it before the administrator confirms receipt.

- [ ] **Step 5: Run final local verification**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
git diff --check
cd /Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-wx
npx prettier --check docs/HANDOFF.md docs/review-submission-checklist.md
git diff --check
```

Expected: backend 102 tests with no failures/errors; both diff checks exit 0; frontend docs pass Prettier.

- [ ] **Step 6: Perform final production verification**

```bash
nginx -t
systemctl is-active nginx
systemctl is-active osheeep-server
curl --fail --silent https://www.osheeep.com/healthz
curl --silent --output /dev/null --write-out '%{http_code}\n' https://www.osheeep.com/
```

Expected: syntax succeeds, services active, health `UP`, site 200.

- [ ] **Step 7: Commit only backend Task 4 files**

```bash
git add deploy/production/OPERATIONS.md \
  src/test/java/com/osheeep/server/ProductionDeploymentContractTest.java
git diff --cached --name-only
git commit -m "docs: record production monitoring operations"
```

Expected: only those two backend paths staged. Do not commit or push frontend dirty-worktree changes unless the administrator explicitly requests it.
