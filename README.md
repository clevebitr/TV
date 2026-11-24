# 影視TV

Android TV、手機、平板都能用，支援本地檔案與網址播放。

[使用指南](https://fongmi.github.io/TV) · [討論群組](https://t.me/fongmi_official)

## 主要功能

- **瀏覽**：分類篩選、跨站搜尋、收藏、播放記錄與無痕模式。
- **播放**：倍速、字幕、彈幕、片段跳過、畫中畫與背景音訊。
- **直播**：分組選台、節目表、頻道收藏與線路切換。
- **操作**：電視遙控器、手機手勢、DLNA 投放與接收。
- **互通**：網頁遙控、檔案傳輸、記錄同步與 Android Auto。
- **一起看**：手機版播放器可連接 [Syncplay](https://syncplay.pl/) 伺服器（內建官方線路，也可自訂），建立或加入房間後同步播放、暫停與進度。

爬蟲開發說明與 Java、Python、QuickJS 範例見 [CatVodSpider](https://github.com/FongMi/CatVodSpider)。

## 一起看（Syncplay）

手機版播放器的操作列新增「一起看」入口：

- 伺服器可從 `syncplay.pl:8995~8999` 中選擇，或自行輸入 `host:port`；自訂伺服器可勾選 STARTTLS。
- 面板開啟時與切換伺服器時會自動測試連線延遲，連線後面板顯示 Syncplay 即時延遲。
- 輸入暱稱與房間名稱即可「加入房間」或「建立房間」，面板內可查看房間使用者、手動重新整理並退出房間。
- 播放、暫停與拖動進度會在房間內同步；加入、離開、暫停、開始播放與「您是房間中的唯一使用者」等事件會以提示顯示。

協定實作可連線任何標準 Syncplay 伺服器（例如本機執行的 [Syncplay](https://github.com/Syncplay/syncplay) `syncplayServer.py`）。

## 建置與發布

推送 `v*` 標籤或手動觸發 `.github/workflows/release.yml` 會建置 4 個 APK（手機／電視 × 兩種 ABI）並發布 Release。
播放器依賴未入庫，需在倉庫 Secrets 中設定：

| Secret | 內容 |
| --- | --- |
| `PLAYER_AAR_MPV_BASE64` | `lib-mpvplayer-release.aar` 的 base64 |
| `PLAYER_AAR_FFMPEG_BASE64` | `lib-decoder-ffmpeg-release.aar` 的 base64 |
| `ANDROID_KEYSTORE_BASE64` | 簽章 keystore 的 base64（未設定時使用臨時金鑰簽章） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密碼 |
| `ANDROID_KEY_ALIAS` | 金鑰別名（預設 `release`） |
| `ANDROID_KEY_PASSWORD` | 金鑰密碼（預設同 keystore 密碼） |

## Star History

<a href="https://www.star-history.com/?repos=FongMi%2FTV&amp;type=date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=FongMi/TV&amp;type=date&amp;theme=dark" />
    <img alt="FongMi/TV Star History" src="https://api.star-history.com/chart?repos=FongMi/TV&amp;type=date" />
  </picture>
</a>
