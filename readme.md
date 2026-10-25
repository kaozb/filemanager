请严格遵守我的开发习惯，就是不要走，走MR直接推送

# 博爷（文件管理器）

高性能安卓文件管理器，纯 Java 实现，无 AndroidX、无 Kotlin、无第三方依赖。

- 包名：`com.lumen.files`
- minSdk 21 / targetSdk 34 / compileSdk 34
- 支持：本地存储、WebDAV 远程、双栏浏览、搜索、多选操作等

## 打包环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+（Temurin 17/21 验证通过） | AGP 8.5.2 要求，勿用 JDK 8 |
| Gradle | 8.14.6 | 命令行直接调用，无需 wrapper |
| Android Gradle Plugin | 8.5.2 | 声明于根 `build.gradle` |
| Android SDK | cmdline-tools（最新） | 安装于 `sdk.dir`（见 `lumen-files/local.properties`） |
| SDK 组件 | `platforms;android-34`、`build-tools;34.0.0`、`platform-tools` | sdkmanager 安装 |

SDK 安装：

```bash
mkdir -p $ANDROID_HOME/cmdline-tools
unzip commandlinetools-linux-*.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## 签名密钥（后续打包固定使用此密钥）

| 项 | 值 |
|---|---|
| 密钥库文件 | `lumen-files/keystore/mibo.jks` |
| 格式 | JKS |
| 别名（alias） | `mibo` |
| storepass | `mibo` |
| keypass | `mibo` |
| 算法 | SHA256withRSA |
| 证书 SHA-256 | `52d39c230177ddb28a5d4f896e33de7581e9da0cd357a942a979ea0a5599753d` |
| 证书 SHA-1 | `767b9c1a37580b2e0decbc7eedd93143ab08940d` |
| 有效期 | 2026-10-25 至 2051-10-19 |

> 该密钥为项目唯一正式签名密钥，所有对外发布的 APK 一律用它签名；`build/lumen.jks` 为旧调试密钥，已弃用（两者签名不兼容，互相覆盖安装需先卸载）。

## 打包命令

推荐直接运行脚本（清理 + 构建 + 对齐 + 签名 + 验证 + 清理中间文件）：

```bash
cd lumen-files
./package.sh
```

脚本内容（等价的手动步骤）：

```bash
cd lumen-files

# 0. 清理缓存（必须，避免增量构建残留把旧 dex/资源打进包体）
gradle clean
rm -rf app/build build .gradle
rm -f aligned.apk LumenFiles.apk LumenFiles.apk.idsig

# 1. Debug 构建（输出 app/build/outputs/apk/debug/app-debug.apk）
gradle assembleDebug

# 2. 对齐
zipalign -f 4 app/build/outputs/apk/debug/app-debug.apk aligned.apk

# 3. 签名（密钥库 keystore/mibo.jks，别名 mibo，密码 mibo）
apksigner sign --ks keystore/mibo.jks --ks-key-alias mibo --ks-pass pass:mibo --key-pass pass:mibo --out LumenFiles.apk aligned.apk

# 4. 验证
apksigner verify --print-certs LumenFiles.apk

# 5. 清理中间文件
rm -f aligned.apk LumenFiles.apk.idsig
```

Release 构建（已配置混淆 + 资源收缩）：

```bash
gradle assembleRelease
```

## 打包注意事项

1. **网络代理**：受限网络环境下 Gradle 拉取 AGP 依赖必须走代理，否则报
   `Plugin [id: 'com.android.application'] was not found`：
   ```bash
   GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080" gradle assembleDebug
   ```
   sdkmanager 同理需加 `--proxy=http --proxy_host=127.0.0.1 --proxy_port=18080`。
2. **签名一致性**：覆盖安装必须使用同一密钥库 `keystore/mibo.jks`（别名 `mibo`），更换签名会导致安装失败（需先卸载）。注意：与旧版 `build/lumen.jks` 签名不兼容，从旧版升级需先卸载。
3. **首启权限**：targetSdk 34 下存储权限需动态申请，旧版荣耀/鸿蒙设备有专门的授权引导分支，改动 `StorageUtil` 时注意回归。
4. **无第三方依赖**：刻意不引入 AndroidX/Kotlin，新增功能请使用平台 API，勿随手添加依赖。
5. **浅克隆**：如本地历史被裁剪（`rev-list --count main` 偏小），先 `git fetch origin main --depth=50` 再操作。
