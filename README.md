# OrionTV-native

OrionTV 原生重写：Kotlin + Jetpack Compose for TV，只做 Android TV。
从 0 开始的新项目，对齐 v1.7.2 全部用户功能（RN 版已不再维护）。

## 基本信息

| 项 | 值 |
|---|---|
| 包名 | `com.oriontv` |
| 版本 | 2.0.0（versionCode 10703） |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| 目标设备 | Android TV 横屏（arm64-v8a） |
| APK 体积目标 | < 25MB |

> 注意：2.0.0 用全新签名密钥，**须先卸载旧版 OrionTV 再安装**，收藏/播放记录带不过来。

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose for TV（tv-foundation / tv-material 1.0.1，不用 Leanback）
- Navigation for Compose（类型安全路由）
- Media3 ExoPlayer（点播与直播共用单例）
- OkHttp + kotlinx.serialization + Coil 3
- DataStore Preferences（设置）+ Room（收藏/记录/搜索历史）
- Hilt（依赖注入）+ KSP
- NanoHTTPD（远程输入 12346 服务）+ ZXing（二维码）

依赖版本口径见 `gradle/libs.versions.toml`（以 `CONTRACT.md` §2 为准，全仓只用一套）。

## 怎么编

```bash
./gradlew assembleDebug testDebugUnitTest   # 调试包 + 单测
./gradlew assembleRelease                   # 发布包（需签名 Secrets，见下）
```

需要 JDK 17 与 Android SDK（compileSdk 34）。

## Release 流程

1. 总协调核对 `app/build.gradle.kts` 里的版本号与远端 main HEAD 一致。
2. 打 tag `v2.0.0`（只有总协调打 tag，各阶段 agent 不要自己打）。
3. `.github/workflows/release.yml` 自动跑 `assembleRelease`（用 Secrets 里的
   keystore 签名）、校验 APK 大小、创建正式 Release 并上传 APK + `.sha256` 文件。
4. 产物名固定 `orionTV.{version}.apk`，下载地址与 1.7.2 版本接口一致：
   `https://dl.ciweihaha.win/orionTV.{version}.apk`。

签名只在 `ORION_KEYSTORE_BASE64` 存在时启用，本地无 Secrets 也能编 debug。
密钥与密码只放在 GitHub Actions Secrets 里，绝不进仓库。

## 包结构

```
com.oriontv
├─ MainActivity.kt / OrionTvApp.kt（Hilt 入口）
├─ ui/theme/            OrionTheme（颜色/字号照抄 plan §3.1）
├─ ui/components/       VideoCard、StyledButton、MediaButton、LoadingAnimation、SectionCard、ConfirmDialog、Toast
├─ navigation/          NavHost、路由（Home/Live/Detail/Play/Search/Favorites/Settings）
├─ feature/home/        首页
├─ feature/detail/      详情
├─ feature/play/        PlayerManager（ExoPlayer 单例）、播放页、控制条、抽屉
├─ feature/live/        直播：M3uParser、EpgParser、频道面板、EPG 弹窗
├─ feature/search/      搜索 + 搜索历史
├─ feature/favorites/   收藏
├─ feature/settings/    设置（手工焦点机）
├─ feature/update/      版本检查/下载/SHA256 校验/安装
├─ feature/auth/        登录弹窗 + 会话续期
├─ feature/remoteinput/ 12346 远程输入服务
├─ data/api/            AggApi、MoonTvApi、ApiClient（超时/重试/熔断/401 续期）
├─ data/repo/           Favorites/PlayRecords/SearchHistory/Settings 仓库
├─ data/local/          Room（AppDatabase + DAO + Entity）、DataStore
└─ core/                StorageKeys、焦点工具（KeyUp 去重）、时间/版本号工具
```

## 文档

- 执行口径：`CONTRACT.md`（见总协调目录 `~/workspace/oriontv-native-rewrite/`）
- 功能规划：`plan.md`（同上）
