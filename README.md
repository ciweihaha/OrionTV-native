# OrionTV-native

OrionTV 原生重写：Kotlin + Jetpack Compose for TV，只做 Android TV。
从 0 开始的新项目，对齐 v1.7.2 全部用户功能（RN 版已不再维护）。

## 基本信息

| 项 | 值 |
|---|---|
| 包名 | `com.oriontv` |
| 版本 | 2.0.3（versionCode 10706） |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |
| 目标设备 | Android TV 横屏（arm64-v8a） |
| APK 体积目标 | < 25MB |

> 注意：2.0.0 用全新签名密钥，**须先卸载旧版 OrionTV 再安装**，收藏/播放记录带不过来。

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose for TV（tv-material 1.0.1，不用 Leanback；
  tv-foundation 1.0.0 已删掉 Tv Lazy Layout，改用标准 LazyColumn/LazyRow/LazyVerticalGrid）
- Navigation for Compose（类型安全路由）
- Media3 ExoPlayer（点播与直播共用单例）
- OkHttp + kotlinx.serialization + Coil 3
- DataStore Preferences（设置）+ Room（收藏/记录/搜索历史）
- Hilt（依赖注入）+ KSP
- NanoHTTPD（远程输入 12346 服务）+ ZXing（二维码）

依赖版本口径见 `gradle/libs.versions.toml`（以 `CONTRACT.md` §2 为准，全仓只用一套）。
两处与 CONTRACT §2 不同（原版本在 Maven 上不存在，换为邻近可用版本）：
tv-foundation 1.0.1 → **1.0.0**（androidx.tv:tv-foundation 最高只到 1.0.0，
tv-material 才有 1.0.1）、NanoHTTPD 2.3.2 → **2.3.1**（最新正式版）。
另外 **compileSdk/targetSdk 由 CONTRACT 的 34 改为 35**：CONTRACT 指定的依赖本身要求 35
（tv-foundation 1.0.0 与 core-ktx 1.15.0 的 aar-metadata 都是 minCompileSdk=35，
34 下 `:app:checkDebugAarMetadata` 必失败）；minSdk 仍是 24，电视安装不受影响，待总协调追认。
另两处与 CONTRACT §2 不同：**tv-foundation 依赖已删除**——1.0.0 稳定版删掉了 Tv Lazy Layout
（alpha11 弃用、alpha12 移除，见官方 release notes），本工程改用 Compose BOM 管理的标准
`androidx.compose.foundation`（LazyColumn/LazyRow/LazyVerticalGrid）；**新增
`androidx.hilt:hilt-navigation-compose:1.2.0`**（`hiltViewModel()` 所在 artifact，骨架第一版漏声明）。
另：Coil 3 是 KMP 发布，Android 侧用 **`coil-compose-android`**（`coil-compose` 本体无 AAR，
`AsyncImage` 解析不到）；`coil-network-okhttp` 仍在用（VideoCard 豆瓣防盗链 Referer
靠 `coil3.network.httpHeaders`，删掉会 418）；
导航轨图标 `LiveTv` 只在 **`material-icons-extended`** 里，core 没有，已改用 extended。

## 怎么编

```bash
./gradlew assembleDebug testDebugUnitTest   # 调试包 + 单测
./gradlew assembleRelease                   # 发布包（需签名 Secrets，见下）
```

需要 JDK 17 与 Android SDK（compileSdk 35）。

## Release 流程

1. 总协调核对 `app/build.gradle.kts` 里的版本号与远端 main HEAD 一致。
2. 打 tag `v2.0.0`（只有总协调打 tag，各阶段 agent 不要自己打）。
3. `.github/workflows/release.yml` 自动跑 `assembleRelease`（用 Secrets 里的
   keystore 签名）、校验 APK 大小、创建正式 Release 并上传 APK + `.sha256` 文件。
4. 产物名固定 `orionTV.{version}.apk`，下载地址与 1.7.2 版本接口一致：
   `https://dl.ciweihaha.win/orionTV.{version}.apk`。

签名只在 `ORION_KEYSTORE_BASE64` 存在时启用，本地无 Secrets 也能编 debug。
密钥与密码只放在 GitHub Actions Secrets 里，绝不进仓库。

## 设计基准

界面唯一基准：`ui-design.md`（总协调目录 `~/workspace/oriontv-native-rewrite/`）。

- 结构：左侧 96dp 常驻导航轨（首页/直播/搜索/收藏/设置）+ 右侧内容区；
  内容区安全边距左右 48dp、上下 32dp。
- 首页（2026-11-14 用户决定：**不要横幅**）：顶部分类 chip 栏置顶
  （最近播放 / 热门剧集 / 电视剧 / 电影 / 动漫 / 综艺 / 豆瓣 Top250），
  焦点默认落在“最近播放”上，按下进入内容行；下方依次最近播放行
  （断点续播卡片，带进度条与“剩余 xx 分钟”）→ 热门剧集行 → 各分类行（每行 6 张卡）。
- 颜色：墨黑底 `#0C0E12`、表面 `#171A20`、浮层 `#1F232C`、熔橙品牌色/焦点 `#FF6A3D`、
  LIVE 红 `#FF3B30`、正在播绿 `#34D399`；字号 22/18/15/14/12sp；
  圆角卡片 12dp / 面板 16dp / 按钮与 chip 胶囊。
- 焦点：放大 1.08 + 2dp 品牌色描边 + 阴影 8dp；各页首进有焦点锚点，返回时按条目键恢复上次焦点，
  弹窗打开时抢焦并陷住方向键，状态变化（下载完成/超限/服务拉起）与关闭后也有焦点归宿（第 6 轮修正：关闭后各页回自己默认锚点，设置页回左菜单当前项——原“回导航轨首项，设置页回右栏”是 rebase 前 P1-2 旧方案的宣称，与 focusRestoreTick 实现不符）；按键只处理 KeyUp（真机待验证）。
- 搜索输入：屏上键盘（全键 QWERTY + 9 键多击两档）与手机扫码远程输入；
  远程输入是局域网明文服务（端口 12346），配对码 5 分钟有效（轮换后 60 秒宽限期，界面有倒计时），握手成功后发 24 小时会话 token（绑定签发时设备 IP）。
- 详情页左 2/5 海报 + 右 3/5 信息，选集网格每行 8 个；直播右侧 420dp 频道面板；
  播放控制条 5 秒自隐，跳过片头胶囊默认 90 秒；弹窗 640dp 宽、16dp 圆角、60% 黑遮罩。

## 包结构

```
com.oriontv
├─ MainActivity.kt / OrionTvApp.kt（Hilt 入口）
├─ ui/theme/            OrionTheme（颜色/字号按 ui-design.md §2/§3）
├─ ui/components/       VideoCard、PrimaryButton/SecondaryButton、CategoryChip、NavRail、LoadingAnimation、SectionCard、ConfirmDialog、Toast、BrandProgressBar
├─ navigation/          NavHost（带左侧导航轨）、路由（Home/Live/Detail/Play/Search/Favorites/Settings）
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
