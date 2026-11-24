# 開發者文件

基於 [CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) 的開源 Android 影音應用程式，同時支援 **Android TV 大螢幕**與**手機**兩種使用情境，並且透過外部配置靈活擴展內容。

[討論群組](https://t.me/fongmi_official) | [發布頻道](https://t.me/fongmi_release)

[![Star History Chart](https://api.star-history.com/svg?repos=FongMi/TV&type=Date)](https://www.star-history.com/#FongMi/TV&Date)

---

## 目錄

- [專案架構](#專案架構)
- [播放器](#播放器)
- [點播功能](#點播功能)
- [直播功能](#直播功能)
- [爬蟲引擎](#爬蟲引擎)
- [網路功能](#網路功能)
- [DLNA 投放](#dlna-投放)
- [Android Auto](#android-auto)
- [遠端控制](#遠端控制)
- [配置說明](#配置說明)
- [延伸閱讀](#延伸閱讀)

---

## 專案架構

| 項目      | 值                             |
|---------|-------------------------------|
| package | `com.fongmi.android.tv`       |
| minSdk  | 24（Android 7.0 Nougat）        |
| abi     | `arm64-v8a`、`armeabi-v7a`     |
| flavor  | `leanback`（電視版）、`mobile`（手機版） |

```
TV/
├── app/            主應用程式（含兩套 UI Flavor）
├── catvod/         爬蟲抽象層（Spider 介面、OkHttp 網路棧）
├── quickjs/        QuickJS JavaScript 引擎
├── chaquo/         Chaquopy Python 引擎
```

`app/src/main/` 為兩個版本共用的業務邏輯，`app/src/leanback/` 與 `app/src/mobile/` 各自實作對應 UI。

---

## 播放器

- **核心**：ExoPlayer（Media3）+ FFmpeg 軟解，硬解 / 軟解自動降級切換
- **渲染**：SurfaceView / TextureView
- **DRM**：Widevine、PlayReady、ClearKey，支援 `#KODIPROP` 宣告
- **彈幕**：DanmakuFlameMaster，與播放時間軸精確同步，支援遠端推送
- **字幕**：SRT / SSA / ASS 外掛字幕、系統 CaptioningManager、遠端即時注入
- **其他**：倍速、多縮放比例、畫中畫（PiP）、背景音訊、片頭 / 片尾自動跳過

---

## 點播功能

- 多站點分類瀏覽，Filter 篩選（年份 / 地區 / 類型等）
- 多站點**並行搜尋**，關鍵字自動繁轉簡提升相容性
- 播放失敗自動換源：解析器 → 線路 → 搜尋其他站 → 下一站點
- 觀看記錄（保留 60 天）、收藏、無痕模式
- 電視版使用遙控器操作；手機版支援手勢（亮度 / 音量 / 進度）、上下滑切集、螢幕旋轉與鎖定

---

## 直播功能

- 支援 M3U、TXT（`#genre#` 分組）、JSON 三種直播源格式
- **EPG**：XMLTV 格式（支援 `.gz`），每 6 小時自動刷新
- **追看 / 時移**：`append`、`pltv` 等多種類型
- 頻道收藏、隱藏分組密碼保護
- 特殊引擎：TVBus、ForceTech

---

## 爬蟲引擎

支援三種語言撰寫爬蟲：

- Java JAR（DexClassLoader）
- JavaScript（QuickJS）
- Python（Chaquopy）

透過 `api` 欄位指定爬蟲，`ext` 欄位傳入初始化參數。完整 API 規格見 [SPIDER.md](docs/SPIDER.md)。

---

## 網路功能

- **DoH**：DNS over HTTPS，支援 Bootstrap IP
- **代理**：HTTP / HTTPS / SOCKS4 / SOCKS5，依 host 正則規則動態選擇
- **Hosts**：DNS 解析覆蓋，支援萬用字元 `*`
- **CORS 注入**：依 host 規則在回應中注入自訂標頭
- **廣告攔截**：`ads` 黑名單，符合域名直接攔截
- **WebView 嗅探**：Sniffer 以 regex 攔截媒體 URL；支援 UA 偽裝

---

## DLNA 投放

- **DMC（投放端）**：手機版，掃描區域網路 DLNA 設備並投放媒體
- **DMR（被投放端）**：電視版，作為 DLNA Renderer 接收其他設備投放

使用 JUPnP 3.0.4（UPnP），支援 play / pause / stop / seek / next / repeat 控制，可傳遞自訂 HTTP 標頭（User-Agent、Referer 等）至目標串流。

---

## Android Auto

電視版支援 Android Auto，PlaybackService 實作 MediaLibraryService，可在車機上瀏覽播放記錄與直播頻道：

- **點播**：歷史記錄條目可直接續播，恢復上次進度
- **直播**：依分組瀏覽頻道，可直接選台
- **播放控制**：支援車機端 play / pause / prev / next / stop
- **懶加載**：App 退出後 Auto 仍保持連線，配置自動重新載入

---

## 遠端控制

應用啟動後綁定本地 HTTP 伺服器（NanoHTTPD），埠號從 **9978** 起自動偵測至 **9998**，可用於播放控制、推送字幕 / 彈幕、多裝置同步等。完整端點說明見 [LOCAL.md](docs/LOCAL.md)。

---

## 一起看（Syncplay）

手機版播放器操作列新增「一起看」入口，透過標準 [Syncplay](https://syncplay.pl/about/protocol/) 協定與房間同步播放：

- 伺服器可從 `syncplay.pl:8995~8999` 選擇，或輸入自訂 `host:port` 並勾選 STARTTLS。
- 開啟面板與切換伺服器時自動測試連線延遲，連線後顯示即時 RTT。
- 輸入暱稱與房間名稱即可「加入房間」或「建立房間」，面板可查看房間使用者、重新整理與退出。
- 播放／暫停／拖動進度會在房間內同步；加入、離開、暫停、開始與「您是房間中的唯一使用者」等事件以提示顯示。

可連線任何標準 Syncplay 伺服器，例如本機執行的 [Syncplay](https://github.com/Syncplay/syncplay) `syncplayServer.py`。

## 建置與發布

播放器依賴來自 [FongMi/media](https://github.com/FongMi/media)（`release-1.11.0-fongmi` 分支），未入庫。
推送 `v*` 標籤或手動觸發 `.github/workflows/release.yml` 時，CI 會先建置這些 media3 AAR 再產生 4 個 APK（手機／電視 × 兩種 ABI）並發布 Release。

CI 簽章金鑰可選，未設定時使用臨時金鑰：

| Secret | 內容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 簽章 keystore 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密碼 |
| `ANDROID_KEY_ALIAS` | 金鑰別名（預設 `release`） |
| `ANDROID_KEY_PASSWORD` | 金鑰密碼（預設同 keystore 密碼） |

本機建置時，先把 media3 AAR 放進 `app/libs`（`lib-*.aar` 預設忽略，可直接放入），並準備 `local.properties`（`sdk.dir`、`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。

## 配置說明

Vod 配置為應用主要入口，透過 URL 或本地路徑載入，頂層欄位定義：

- 點播站點（`sites`）、解析規則（`parses`）
- 直播來源（`lives`）
- 網路設定（`doh`、`proxy`、`hosts`、`ads`）

Live 配置可內嵌或獨立存放。完整欄位說明見 [CONFIG.md](docs/CONFIG.md)。

---

## 延伸閱讀

| 文件                          | 說明                   |
|-----------------------------|----------------------|
| [CONFIG.md](docs/CONFIG.md) | Vod / Live 完整配置欄位說明  |
| [SPIDER.md](docs/SPIDER.md) | Spider 所有方法規格與回傳格式   |
| [LOCAL.md](docs/LOCAL.md)   | 本地 HTTP API 所有端點完整說明 |
| [LIVE.md](docs/LIVE.md)     | 直播來源格式完整說明           |
