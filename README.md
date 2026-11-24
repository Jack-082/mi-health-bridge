# MiHealthBridge

把**国行小米手环 / 手表**（如小米手环 9 Pro）的健康数据，近实时同步到 Android **Health Connect**。

国行「小米运动健康」（`com.mi.health`）不支持写入 Health Connect。本应用不替换官方 App，也不碰蓝牙：

```
手环 ──蓝牙──> 小米运动健康 ──上传──> 小米健康云 (hlth.io.mi.com)
                                            │  本应用定时拉取（非官方接口）
                                            ▼
                                     Health Connect ──> Google Fit / 其他健康 App
```

NFC 公交卡、支付、消息通知、表盘这些官方功能都不受影响。

## 同步的数据

| 小米数据 | Health Connect 记录 | 说明 |
|---|---|---|
| 步数 / 距离 / 活动热量 | Steps / Distance / ActiveCaloriesBurned | 按自然日汇总，每次同步覆盖更新当天的值 |
| 心率 | HeartRate | 按整点小时打包 |
| 静息心率 | RestingHeartRate | |
| 血氧 | OxygenSaturation | |
| 睡眠（含深睡/浅睡/REM/清醒分期） | SleepSession | |
| 睡眠平均呼吸率 | RespiratoryRate | |
| 运动记录 | ExerciseSession | 距离和热量写在备注里，避免和每日汇总重复计数 |
| 体重 / 体脂（体脂秤） | Weight / BodyFat | |

压力和 HRV 不同步：Health Connect 没有压力类型，小米 HRV 的口径也和 Health Connect 的 RMSSD 不同。

每条记录都有固定的 `clientRecordId`，重复同步只会覆盖更新，不会产生重复数据。

## 关于“实时”

延迟由三段组成：

1. **手环 → 小米运动健康**：手机后台与手环的同步周期，一般几分钟到几十分钟。打开小米运动健康 App 会立即触发同步。
2. **小米运动健康 → 云端**：同步后自动上传。
3. **云端 → Health Connect**：由本应用控制。默认每 15 分钟一次，可开启 2 / 5 / 10 分钟的“快速模式”。

通常能做到分钟级，但做不到秒级。真正的瓶颈是第 1 段，本应用改变不了。

## 安装

### 方式 A：从 GitHub Actions 下载 APK

1. Fork 或推送本仓库到 GitHub，Actions 会自动构建。
2. 在 Actions 运行详情页的 Artifacts 里下载 `MiHealthBridge-apk`。打 `v*` tag 时会同时发布到 Releases。

> 没有配置签名 Secrets 时，APK 使用 CI 临时生成的 debug 签名，**每次构建的签名都不同，升级时需要先卸载**。
> 想直接覆盖升级，请在仓库 Secrets 中配置：
> `SIGNING_KEYSTORE_BASE64`（`base64 -i release.jks`）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`，以及可选的 `SIGNING_KEY_PASSWORD`（不配置时沿用 keystore 密码）。

> **版本号自动生成**：`versionCode` 是 Git 提交总数，`versionName` 来自最近的 `v*` 标签
> （在标签上为 `0.1.0`，标签后有新提交时为 `0.1.0-3-gabc1234`）。发布新版本只需打标签：
> `git tag v0.2.0 && git push origin v0.2.0`。

### 方式 B：本地构建

用 Android Studio 打开项目（会自动下载 Gradle），或执行：

```bash
gradle wrapper --gradle-version 8.11.1   # 只需执行一次，生成 ./gradlew
./gradlew :app:assembleRelease
```

要求 JDK 17、Android SDK 35。

## 使用

1. 确认手机上的「小米运动健康」已登录，并且手环能正常同步数据。
2. 打开 MiHealthBridge，点 **登录小米账号**，在小米官方登录页完成登录（支持短信验证）。本应用只保存登录后的 `passToken` Cookie，不接触你的密码。
3. 点 **授权写入**，在 Health Connect 中勾选全部权限。
4. 点 **立即同步**，查看日志。
5. 建议点 **关闭电池优化**。在小米、华为等国产系统上，还需要在系统设置中为本应用开启“自启动”，后台策略设为“无限制”。小米运动健康也要同样设置。

### Health Connect 在国行手机上

- **Android 14 及以上**：Health Connect 是系统组件，入口在“设置 → 安全与隐私 → 健康数据”或类似位置。部分国行 ROM 可能删掉了它。
- **Android 13 及以下**：需要安装 Health Connect 应用（包名 `com.google.android.apps.healthdata`）。没有 Google Play 的话，需要从可信渠道下载 APK。

如果应用提示“此设备不支持 Health Connect”，说明系统缺少这个组件。

## 风险与限制

- 使用的是**小米非官方、未公开的云端接口**，小米随时可能修改或关闭，届时需要更新代码。
- `passToken` 等同于账号登录凭据，加密保存在本机，只会发送给小米官方域名。请勿把日志、备份或截图里的凭据分享给他人。
- `passToken` 失效后（修改密码、在其他地方退出登录、长时间未使用等），应用会提示重新登录。
- 仅供个人读取**本人账号**的数据使用。

## 项目结构

```
app/src/main/java/io/github/mihealthbridge/
├── mi/MiCrypto.kt            # RC4 + SHA 签名
├── mi/MiCloudClient.kt       # 登录换会话、签名请求、分页拉取
├── hc/RecordMapper.kt        # 小米条目 → Health Connect 记录
├── hc/HealthConnectWriter.kt # 按权限过滤、批量写入、失败时逐条重试
├── sync/SyncEngine.kt        # 增量窗口、会话续期、整体编排
├── sync/SyncWorker.kt        # WorkManager：15 分钟周期 + 快速模式自续约
├── sync/Settings.kt          # 加密凭据 + 同步状态
└── ui/                       # Compose 主界面、WebView 登录、隐私说明页
```

### 增量策略

每次拉取的窗口是 `[ (上次成功 − 6h) 所在自然日 00:00, 现在 + 1h ]`：

- 从零点开始拉，保证每日汇总类记录总是基于整天数据写入，不会被半天的数据覆盖。
- 回看 6 小时，接住手环延迟上传的数据。
- 只要有任意一项数据拉取失败，就不推进窗口，下次重拉。

## 致谢

小米健康云的登录与签名协议移植自 [HealthMi](https://github.com/ShawnDiego/HealthMi)（Apache-2.0）和 mi-fitness-mcp-cn（MIT），详见 [NOTICE](NOTICE)。

## License

Apache-2.0
