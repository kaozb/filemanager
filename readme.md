请严格遵守我的开发习惯，就是不要走，走MR直接推送

# 博爷（文件管理器）

高性能安卓文件管理器，纯 Java 实现，无 AndroidX、无 Kotlin、无第三方依赖。

- 包名：`com.lumen.files`
- minSdk 21 / targetSdk 34 / compileSdk 34
- 支持：本地存储、WebDAV 远程、双栏浏览、搜索、多选操作等

## 打包环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+（当前环境 Temurin 25.0.4.1 验证通过） | AGP 8.5.2 要求，勿用 JDK 8 |
| Gradle | 8.14.6 | 命令行直接调用，无需 wrapper |
| Android Gradle Plugin | 8.5.2 | 声明于根 `build.gradle` |
| Android SDK | 手工解压安装于 `sdk.dir`（见 `lumen-files/local.properties`，当前为 `/opt/android-sdk`） | 不使用 cmdline-tools/sdkmanager |
| SDK 组件 | `platforms/android-34`、`build-tools/34.0.0` | 从 `dl.google.com` 直接下载 zip 解压；`platform-tools`（adb）未安装，仅打包签名不需要它 |

SDK 安装（直接下载官方 zip，无需 sdkmanager）：

```bash
mkdir -p $ANDROID_HOME/platforms $ANDROID_HOME/build-tools

# 平台 android-34（解压后目录名就是 android-34）
curl -sSL -o /tmp/platform-34.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -q /tmp/platform-34.zip -d $ANDROID_HOME/platforms/

# build-tools 34.0.0（zip 内目录名是 android-14，需改名为 34.0.0）
curl -sSL -o /tmp/build-tools-34.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
unzip -q /tmp/build-tools-34.zip -d /tmp/bt
mv /tmp/bt/* $ANDROID_HOME/build-tools/34.0.0
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

> 该密钥为项目唯一正式签名密钥，所有对外发布的 APK 一律用它签名；旧调试密钥 `build/lumen.jks` 已删除清理（两者签名不兼容，互相覆盖安装需先卸载）。

## 版本号规则

每次 `./package.sh` 打包自动执行，无需手工改：

| 项 | 规则 | 示例 |
|---|---|---|
| 应用名称（桌面显示） | `超级文件 N`，N 每版 +1 | `超级文件 2` |
| versionCode | 每版 +1，记录在 `version.properties` | `2` |
| versionName | 打包时刻 `MM.dd.H.mm`（H 不补零） | 2026-10-26 01:07 → `10.26.1.07` |

`version.properties` 只存 `versionCode`，请勿回退；Gradle 侧读取它并拼接应用名称：

```groovy
versionCode appVersionCode          // 来自 version.properties
versionName appVersionName          // 打包时间，或 -PappVersionName 传入
manifestPlaceholders = [appLabel: appLabel]   // AndroidManifest 的 android:label="${appLabel}"
```

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
   `Plugin [id: 'com.android.application'] was not found`。当前环境已在
   `~/.gradle/gradle.properties` 固化代理（推荐，一次配置全局生效）：
   ```properties
   systemProp.http.proxyHost=127.0.0.1
   systemProp.http.proxyPort=18080
   systemProp.https.proxyHost=127.0.0.1
   systemProp.https.proxyPort=18080
   systemProp.http.nonProxyHosts=localhost|127.0.0.1
   ```
   也可临时用环境变量：`GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080" gradle assembleDebug`。
   手工下载 SDK zip（见上文安装步骤）时 curl 会自动读取 `http_proxy`/`https_proxy` 环境变量。
2. **签名一致性**：覆盖安装必须使用同一密钥库 `keystore/mibo.jks`（别名 `mibo`），更换签名会导致安装失败（需先卸载）。注意：与旧版 `build/lumen.jks` 签名不兼容，从旧版升级需先卸载。
3. **首启权限**：targetSdk 34 下存储权限需动态申请（`MANAGE_EXTERNAL_STORAGE` 跳转系统设置页），改动 `StorageUtil` 时注意回归。
4. **无第三方依赖**：刻意不引入 AndroidX/Kotlin，新增功能请使用平台 API，勿随手添加依赖。
5. **浅克隆**：如本地历史被裁剪（`rev-list --count main` 偏小），先 `git fetch origin main --depth=50` 再操作。
