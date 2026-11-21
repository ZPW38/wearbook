# 签名与发版密钥

> 这份文档解释**为什么必须有正式签名**、密钥放在哪、以及丢了会发生什么。
> 密钥本身不进仓库（`.gitignore` 已排除 `*.jks` / `*.keystore`）。

## 为什么不能用 debug 签名

项目早期一直用 Android SDK 自带的 `~/.android/debug.keystore`（证书主体 `CN=Android Debug`）。
它能装能用，但有三个实质问题：

1. **谁都能伪造**：调试证书是公开的，任何装了 Android SDK 的人都能生成**同一签名**的 APK。
   安卓靠签名判断"是不是同一个应用的更新"——签名相同就能覆盖安装。
   也就是说，别人可以打一个同样的包，骗过系统校验替换掉你装的腕上书。
2. **应用商店不收**：F-Droid、Google Play 都拒绝 debug 签名的包。
3. **容易丢**：`debug.keystore` 会在重装 SDK、换电脑时被重新生成，一旦丢失，
   之前发出去的包就**再也无法覆盖升级**（新签名不同，安卓直接拒绝安装，
   用户必须先卸载——而卸载会清掉书架和阅读进度）。

## 现在的正式密钥

| 项 | 值 |
|---|---|
| 路径 | `~/.android/keys/wearbook-release.jks` |
| 别名 | `wearbook` |
| 密码 | 同目录 `release-password.txt`（32 位随机，**无 BOM**） |
| 算法 | RSA 4096，PKCS12 |
| 有效期 | 10000 天（约 27 年） |
| 证书主体 | `CN=ZPW38, OU=WearBook, O=ZPW38, C=CN` |
| SHA-256 | `1D:49:28:A0:32:95:FF:F7:87:9A:4F:12:8C:68:EB:07:37:66:20:8D:A3:C3:C0:23:77:25:F9:C0:99:42:37:FE` |

签名方案 v1 + v2 + v3 全部启用（v1 给 minSdk 24 的老设备，v2/v3 给新系统）。

## ⚠️ 务必备份

`~/.android/keys/` 这个目录**丢了就没了**，而且不可挽回：

- 已发布的包无法再覆盖升级；
- 用户升级必须卸载重装，**书架上的书和阅读进度全部丢失**；
- 应用商店也无法"换签名"（要求旧密钥签名迁移）。

建议至少备份两处：**加密压缩包存网盘** + **离线介质（U 盘）**。
备份时把 `.jks` 和 `release-password.txt` **分开放**（避免一起泄露）。

## 构建时怎么用

密码的查找顺序（见 `app/build.gradle.kts`）：

1. 环境变量 `WEARBOOK_STORE_PASS`（CI 用）
2. `~/.android/keys/release-password.txt`（开发机用）

两者都拿不到时，`release` 会**退回产出 unsigned 包**而不是构建失败——
这样没有密钥的机器（或 CI 上的外来 PR）依然能编译验证。

```bash
# 正常构建，Gradle 会自动签名
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
```

校验：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 关于 `v1 scheme: false`（正常现象）

`apksigner verify -v` 在 **minSdk ≥ 24** 的包上会显示 `Verified using v1 scheme: false`，
即使 v1 签名确实存在。因为 Android 7.0+ 有 v2 就不再需要 v1，apksigner 因此跳过 v1 校验。

想确认 v1 真的签了，用 `jarsigner -verify`（退出码 0 即通过），或者看 APK 里有没有
`META-INF/CERT.SF` 与 `META-INF/CERT.RSA`。

## 踩过的坑

**密码文件带 BOM**。PowerShell 5.1 的 `Out-File -Encoding utf8` 会在文件头写 `EF BB BF`，
混进密码里就报 `keystore password was incorrect` —— 而错误信息**只字不提 BOM**，
极难排查。`build.gradle.kts` 里读密码时已 `.trim('\uFEFF', ...)` 兜底；
写这类文件建议用 `[System.IO.File]::WriteAllText(path, text, UTF8Encoding($false))`。
