# 短信接收助手

**语言：** [English](README.md) | 简体中文

把一台放在家里的 Android 手机变成自托管短信收发网关。手机负责接收、保存和发送短信；你可以通过可信浏览器远程查看短信、选择 SIM 卡发送短信，并查看手机运行状态。

## 解决什么痛点

很多人手里会有一台“专门接短信”的备用手机：银行卡、平台账号、工作号码、海外/异地号码、不能开 eSIM 的实体卡、双卡甚至多卡场景，都可能让你不得不多带一台手机出门。短信接收助手的目标很直接：手机可以放在家里或办公室，只要它开机、有信号、有网络，你就能远程收发短信。

适合这些场景：

- 不用再随身带好几台手机，验证码短信远程查看即可。
- 无法使用 eSIM、必须保留实体 SIM 卡的号码。
- 多 SIM 卡用户，可以选择指定 SIM 发送短信。
- 备用号码、工作号码、注册专用号码集中放在固定地点管理。
- 异地号码、低频使用号码、IoT/流量卡关联号码等不方便随身携带的场景。
- 在电脑、平板或另一台手机上临时通过网页查看和发送短信。
- 通过自己的局域网、家庭路由器、frp、反向代理或 VPN 自托管访问入口。

## 项目亮点

| 能力 | 说明 |
| --- | --- |
| 手机即服务端 | Android App 内置 Web 服务，默认监听 `8787` 端口。 |
| 远程收发短信 | 网页端可以读取短信，也可以提交发送短信。 |
| 多 SIM 卡支持 | 发送短信时可以选择具体 SIM 卡，适合双卡和多卡使用。 |
| 设备状态看板 | 展示电量、内存、存储、网络、运行时间、短信数量、SIM 信息、发送桥状态等。 |
| 搜索和筛选 | 可按号码、备注、正文、发送状态、收发方向、SIM 卡筛选，也可以只看验证码短信。 |
| 验证码助手 | 自动高亮可能的验证码，并提供一键复制。 |
| 备注和导出 | 常用号码可加本地备注，筛选后的短信可导出 CSV 或 JSON。 |
| 健康提醒 | 电量低、存储高、网络断开、内存压力、发送桥异常会直接提示。 |
| 移动端友好 | 针对小屏手机做了紧凑布局，可以直接用另一台手机访问。 |
| 访问密码保护 | 受保护接口都需要 Bearer Token，密码可在 App 或网页设置里修改。 |
| 配置不硬编码 | frp 地址、端口、认证信息、访问密码都在运行时配置。 |
| 发送状态记录 | 发出的短信会写入本地记录，网页端能看到发送中、已发送、发送失败等状态。 |
| Smartisan 兼容处理 | 针对 Smartisan Pro 2S 上普通 `SmsManager` 被系统吞掉的问题，提供本地 shell 发送桥方案。 |
| 自托管优先 | 数据保存在手机本地，访问入口和部署方式由你掌控。 |

## 界面预览

截图中的设备 ID、短信号码和消息列表已按需脱敏。

### Android App

![Android App 主界面](docs/assets/android-app-home.png)

### 网页控制台

![网页状态看板](docs/assets/web-dashboard.png)

![网页短信列表](docs/assets/web-sms-list.png)

![网页发送短信](docs/assets/web-send-sms.png)

![网页访问设置](docs/assets/web-access-settings.png)

## 项目结构

```text
android/                 Android App 和内置网页服务
tools/sms-bridge/        Smartisan OS 等特殊机型使用的 shell 侧短信发送桥
tools/start-phone-services.sh
server/                  旧版 Node.js Web/API 服务，用于桌面调试或兼容测试
docs/assets/             README 截图
```

## 工作方式

1. Android 手机安装并打开 App。
2. App 自动启动本机 Web 服务，端口为 `8787`。
3. 手机接收到短信后写入本地记录。
4. 可信浏览器访问手机的 Web 服务，通过访问密码读取短信。
5. 需要发送短信时，在网页端选择 SIM 卡、填写号码和内容。
6. 网页端可以同时查看手机电量、存储、内存、网络、SIM、发送桥等状态。

## 快速开始

本地 USB 调试时可以使用：

```sh
adb forward tcp:8787 tcp:8787
```

然后浏览器打开：

```text
http://127.0.0.1:8787
```

局域网或内网穿透部署时，可以访问：

```text
http://<phone-ip>:8787
```

也可以通过你自己的 frp、公网域名、反向代理或 VPN 入口访问。

## 配置说明

App 不会在代码里硬编码 frp 地址、frp token 或网页访问密码。

打开 Android App，进入 **访问设置**，可以配置：

- 网页访问密码
- 公网访问地址
- frp 服务器地址
- frp 服务器端口
- frp 远端端口
- frp 认证 token

这些值只保存在手机本地的 Android `SharedPreferences` 中。

## Smartisan 短信发送桥

在已测试的 Smartisan Pro 2S 上，普通 App UID 调用 `SmsManager` 时系统会接受请求，但短信不会真正到达基带发送流程。项目提供了一个本地 shell 侧发送桥，通过 Android `isms` 服务提交短信。

构建并推送发送桥：

```sh
javac -source 8 -target 8 \
  -bootclasspath .tools/android-sdk/platforms/android-35/android.jar \
  -d /tmp/sms-bridge-build/classes \
  tools/sms-bridge/SmsBridge.java

.tools/android-sdk/build-tools/35.0.0/d8 \
  --min-api 23 \
  --output /tmp/sms-bridge-build/dex \
  /tmp/sms-bridge-build/classes/SmsBridge*.class

cd /tmp/sms-bridge-build/dex
zip -q /tmp/sms-bridge.jar classes.dex

adb shell 'mkdir -p /data/local/tmp/sms-bridge'
adb push /tmp/sms-bridge.jar /data/local/tmp/sms-bridge/sms-bridge.jar
adb push tools/start-phone-services.sh /data/local/tmp/sms-bridge/start-phone-services.sh
adb shell 'chmod 755 /data/local/tmp/sms-bridge/start-phone-services.sh'
```

手机重启后启动发送桥：

```sh
adb shell /data/local/tmp/sms-bridge/start-phone-services.sh
```

App 会检查 `127.0.0.1:8790/health`，并在 App 和网页状态中显示发送桥是否可用。

## API

受保护接口需要请求头：

```text
Authorization: Bearer <web-access-password>
```

接口列表：

- `GET /api/messages?limit=10&offset=0`
- `GET /api/sims`
- `POST /api/send`
- `GET /api/config`
- `POST /api/config`
- `GET /api/device`
- `GET /health`

## 安全建议

- 不建议把 `8787` 端口裸露到公网。
- 建议配合 frp、反向代理、HTTPS、访问控制或 VPN 使用。
- 请设置足够长的网页访问密码。
- frp 认证信息和网页访问密码不要提交到 Git 仓库。
- 如果用于重要账号验证码，建议把访问入口限制在自己的设备或可信网络内。
- 手机要保持充电、信号稳定，并注意短信资费和运营商限制。
