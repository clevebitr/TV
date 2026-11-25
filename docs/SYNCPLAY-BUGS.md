# 一起看（Syncplay）已知问题

> 记录时间：2026-11-25 · 基线：`syncplay-5.5.6` 分支（App 5.5.6 + 一起看）
> 发布产物：`v5.5.9-syncplay`（4 个 APK，versionCode 559，已用仓库 Secrets 中的密钥签名）

---

## BUG-1 房间用户列表不显示 — 已修复（`5.5.7-syncplay` 待验证）

**现象**：已连接房间、进度能同步，但「一起看」面板里的「房间用户」区域看不到任何用户（甚至连自己也没有）。

**协议层已验证正常**（本地跑 `repos/syncplay` 的 `syncplayServer.py`，用 JVM 测试直连）：

```
USERS(1)
   [roomA] tester file=测试剧集 第3集 ready=false
```

双客户端测试里也打印了 `users: alice bob`，说明 `List` 请求/解析/回调链路是通的。

**相关代码**

| 位置 | 作用 |
| --- | --- |
| `sync/SyncPlayClient#requestList()` | 发送 `{"List": null}` |
| `sync/SyncPlayClient#handleList()` | 解析 `{"List":{room:{user:{...}}}}` |
| `sync/SyncPlayManager.Session#onUsers()` | 过滤空名 → `users` → `dispatchUsers()` |
| `SyncPlayDialog#render()` | `adapter.setItems(...)` + 控制 `recycler`/`empty` 可见性 |
| `ui/adapter/SyncPlayUserAdapter` | 简单 `RecyclerView.Adapter` |

**可能原因（按怀疑度排序）**

1. `CustomRecyclerView` 在 `NestedScrollView` 里 `wrap_content` 被测量成 0 高 —— 此时 `info`、`leave` 按钮都正常，只有列表区域空白，最容易误判为“没数据”。
   - 验证：临时给 `recycler` 加固定高度（如 `android:layout_height="120dp"`）或把列表改成普通 `LinearLayoutCompat`（代码里手动 addView），能显示即为测量问题。
2. `getUsers()` 返回空：请求 `List` 的时机早于服务端登录（`Hello` 尚未处理）而被丢弃。
   - 验证：在 `render()` 里打日志 `manager.getUsers().size()`；或点面板右上「刷新」按钮再观察。
3. 双方不在同一房间（「创建房间」在房间名为空时会各生成随机名），此时列表里只有自己。
   - 验证：面板 `info` 行显示的房名是否一致。

**已实施修复**

- 用户列表不再用 `RecyclerView`：`dialog_sync_play.xml` 换成 `LinearLayoutCompat#users`，`SyncPlayDialog#renderUsers()` 用 `AdapterSyncPlayUserBinding` 逐行 inflate，彻底规避 `NestedScrollView` 内的测量问题。
- `SyncPlayManager` 增加 join/leave 兜底：收到 `Set.user` 的 `joined`/`left` 事件时直接增删用户；打开面板且已连接时再主动 `requestUsers()` 一次。
- 删除不再使用的 `SyncPlayUserAdapter`。
- 本地实测（真服务器 + JVM 驱动）：客户端加入后列表显示 `alice bob`；`alice` 断线后立即变为 `bob`（不等 `List` 刷新）。

**原分析（保留）**

- 最省事：把 `recycler` 换成 `LinearLayoutCompat` + `LayoutInflater` 逐行填充，彻底规避滚动容器里的测量问题（用户量本身很小）。
- 或给 `recycler` 设置 `android:layout_height="wrap_content"` + `android:nestedScrollingEnabled="false"` 之外，再显式 `setHasFixedSize(true)` 并在 `roomGroup` 可见后 `requestLayout()`。
- 顺带：`Session#onRoomEvent("joined"|"left")` 后已经会 `requestUsers()`，如果确认是时机问题，可在 `onHello` 之后延迟 300ms 再请求一次。

---

## BUG-2 只能同步同一视频的进度，不能同步选集 — 已修复（`5.5.7-syncplay` 待验证）

**现象**：A 换集后，B 仍停留在原视频（只同步播放/暂停/进度）。

**现状设计**：`Set.file` 已经在收发，但接收端只弹提示，不切集。

| 位置 | 现状 |
| --- | --- |
| `SyncPlayManager#syncFile()` | 连接后/换集时发送 `Set.file`，名字取 `PlayerManager#getMediaTitle()`（剧集标题） |
| `SyncPlayManager.Session#onFileChanged()` | 只 `toast("%s 切换到 %s")`，不做切换 |
| `PlayerManager#getMediaTitle()` | 返回 `MediaMetadata.title`，拿不到 `Episode` 对象 |

**要实现的行为**：一端换集 → 另一端自动切到对应剧集，并继续保持进度同步。

**已实施修复**

- `SyncPlayManager` 新增 `EpisodeHandler { getEpisodeName(); switchEpisode(name) }`；`syncFile()` 优先用当前剧集名（回退到媒体标题）；收到他人 `Set.file` 且与本地不同时，先提示再回调 `switchEpisode`。
- 手机版 / 电视版 `VideoActivity` 在 `onResume/onPause` 注册、注销 `EpisodeHandler`：按 `Episode#getName()` 在 `mEpisodeAdapter.getItems()` 里匹配后调用 `mVod.selectEpisode(item)`；已选中则直接返回，避免回环。
- 电视版 `EpisodeAdapter` 补了 `getItems()`。
- 换集后本地 `syncFile()` 会再发一次同名（对端按“同名忽略”处理）。
- 本地实测（真服务器 + JVM 驱动）：对端发 `Set.file=第3集` → `[HANDLER] switchEpisode(第3集)` + 提示；重复发同名不再触发；本端换集后 `getEpisodeName()` 会上报新名字。

**原方案（保留供参考）**

1. **发送端拿稳定标识**
   - 在 `PlayerManager` 暴露当前剧集信息（例如 `getEpisodeName()` / `getEpisodeUrl()`），或在 `VodPlaybackController#selectEpisode()` 时把当前 `Episode` 记到 `PlaySpec`/`PlayerManager`。
   - `syncFile()` 发送 `episode.getName()`（同源同剧集名一致），必要时用 `vodId + "#" + urlHash` 兜底（Syncplay 文件名上限 250 字符，URL 可能超限，建议存 hash）。
2. **接收端加回调接口**
   ```java
   public interface EpisodeHandler { boolean switchEpisode(String file); }
   // SyncPlayManager: setEpisodeHandler/clearEpisodeHandler
   ```
   - `mobile/VideoActivity`（和 `leanback/VideoActivity`）在 `onResume/onPause` 注册/注销：
     ```java
     private boolean onSyncEpisode(String file) {
         Episode item = mEpisodeAdapter.getItems().stream()
             .filter(e -> file.equals(e.getName())).findFirst().orElse(null);
         if (item == null || item.isSelected()) return false;
         mVod.selectEpisode(item);   // 主线程
         return true;
     }
     ```
   - 匹配顺序建议：先按 `Episode#getUrl()`（最精确），再按 `Episode#getName()`。
3. **防回环 / 防抖动**
   - 收到的 file 与本地当前 file 相同 → 直接忽略。
   - 远端切集后本地会 `syncFile()` 再发一次同名（对端忽略），无需额外处理。
   - 切集后服务端会下发带 `doSeek` 的 `State`，沿用现有 `REMOTE_WINDOW`（1.5s）窗口即可避免被立刻拉回旧进度。
4. **提示文案**：`sync_play_file` 已有（`%1$s 切换到 %2$s`），命中切换时可再加一句“已为你切换”。

**已知局限**

- 双方必须使用**同一站点/同一剧集列表**才能按名称或 URL 匹配；不同源时无法自动切集（可退化为仅提示）。
- 直播（Live）不适用。

**后续验证清单**

- [ ] 两端进同一房间，A 点下一集 → B 3 秒内切到同一集，且进度对齐
- [ ] B 切集后 A 不会反向跳回
- [ ] 单端换集不会引发反复来回切集（防回环）
- [ ] 换集期间的暂停/播放状态与房间一致
- [ ] 用户列表在 1 人 / 2 人 / 3 人时都正确（BUG-1 回归）

## BUG-3 换集只用剧集名匹配，跨播放源时会静默失败 — 已修复

**现象**：两端播放源（爬虫站点）不同时，A 换集 B 没反应，也没有任何提示。

**实现**

- `Set.file` 载荷升级为 `剧集名\u0001站点key\u0001vodId`（`SyncPlayItem`，≤240 字符；对非本 App 客户端仍可读，用户列表/提示只显示剧集名）。
- 接收端按优先级处理：
  1. 同站点（或对端未携带源）→ 在本地剧集列表按名字匹配后 `mVod.selectEpisode(item)`；
  2. 不同站点 → 用 `VodConfig.get().getSite(key)` 解析站点，构造 `Vod(site, vodId)` 调 `mVod.selectSource(vod)`，并记下待选剧集，等详情返回后（`renderEpisodes`）自动选中；提示「已切换到播放源 X」；
  3. 站点不存在 / 剧集找不到 → 提示「无法切换到 X：未找到该剧集或播放源」（不再静默）。
- 防抖动：15 秒内不会反复切回刚离开的站点（避免两端互相来回切源）；`getCurrentItem()` 只有在 `History.siteKey == 当前站点` 时才上报 `vodId`，避免发出错配的 site/vod 组合。
- 本地实测（真服务器 + JVM 驱动）：
  ```
  [alice] file by bob raw=第1集|siteA|vod1
  [HANDLER] switchTo(第3集, site=siteB, vod=vod9)
  [NOTIFY] alice switched to 第3集
  [HANDLER] switchTo(第9集, site=siteB, vod=vod9)
  [NOTIFY] SWITCH FAILED 第9集   ← 失败反馈
  ```

**注意**：自动切源依赖目标站点存在于本机配置（同一个配置源/站点列表）；不同配置会提示失败。

**补充（`5.5.9-syncplay`）：让换集真正可靠**

对齐 Kazumi 的关键点，并修掉我们自己的问题：

1. **发送时机**：Kazumi 在「播放器完成初始化」时对比 `currentFileName` 再发 `Set.file`。我们原来只靠 `PlayerManager.onMediaItemTransition`，不可靠 → 现在在起播入口 `startPlayback()`（每次起播，含换集/切源）显式调用 `SyncPlayManager#onLocalMediaChanged()`，并同时推送一个 `doSeek` 的 `State` 把房间时间轴切到新集；`onMediaItemTransition` 保留作兜底。
2. **剧集标识**：除剧集名外增加 **index**（当前剧集列表序号）与站点/vodId，接收端匹配顺序 `名字 → index → 切源 → 失败提示`。
3. **加入房间不要抢房主**：原来连接后立刻发布自己的 file，新人一进房就把房主拉回自己的集数。现在连接阶段**不再发布 file**：
   - 空房间（`init` 无 user）→ 发自己的 file + `doSeek`（成为房主）；
   - 有人房间（`init` 有 user）→ 记住房主，`List` 回来后用房主的 file 自动切到同一集（`adoptAuthority()`）。

   本地实测：
   ```
   [NOTIFY] follow alice
   [HANDLER] switchTo(第5集 idx=4 site=siteA)   ← 入房即同步房主剧集
   --- alice switches episode
   [HANDLER] switchTo(第6集 idx=5 site=siteA)   ← 换集跟随
   ```
4. 剧集实体在 `renderEpisodeSelection` / `startPlayback` 时缓存（`syncEpisode`），不再依赖 adapter 的即时选中态。

**注意**：自动切源依赖目标站点存在于本机配置（同一个配置源/站点列表）；不同配置会提示失败。

---

## BUG-4 换集跟随在实际设备上仍失败 — 已修复（`5.5.10-syncplay`）

**现象**：A 换集后，B 收到 `Set.file` 但停留在原集（或提示「无法切换」），只在部分场景（同站点、双方都在播放页前台、剧集列表已加载）才偶发成功。

**根因（按影响排序）**

1. **跨源时不先匹配本地剧集**：`VideoActivity` 的 `switchTo()` 只在「对端站点 == 本地站点」时才在本地列表按名字找集；两端用了同一个剧的不同站点/不同搜索条目时，B 会直接走切源分支。目标站点在 B 的配置里不存在 → 直接失败（虽然 B 本地明明有同一集）。这与 BUG-3 里写的「先本地按名字匹配，再切源」不一致。
2. **`Set.user.file` 没有回写用户列表缓存**：`SyncPlayManager.users` 只在 `List` 回复时更新。房主换集后缓存里仍是旧文件，`adoptAuthority()` 会用旧集把已经跟到新集的 B **拉回旧集**（本地 JVM 驱动已复现：`switchTo(第2集)` 后紧跟 `switchTo(第1集)`）。
3. **播放页不在前台时文件事件直接丢失**：`EpisodeHandler` 只在 `onResume/onPause` 注册；B 在后台/PiP/播放暂挂时收到换集，`handler == null` 就只弹一个失败提示，恢复后也不会补切。
4. **发布集数依赖 handler 注册**：`startPlayback()` 里调无参 `onLocalMediaChanged()`，`getLocalItem()` 在 handler 为 null 时回退到 `PlayerManager.getMediaTitle()`，而它是**剧名**（每集相同），于是 B 认为文件没变。
5. **只搜索当前线路（flag）**：A 切到另一条线路的剧集时，B 的 `EpisodeAdapter` 只有当前线路的集数，找不到就失败。

**已实施修复**

- `SyncPlayItem` 增加第 5 段 `vodName`（剧名；超长时自动退回 4 段/纯集名），接收端据此判断是否同一部剧；仍兼容旧的 3/4 段格式。
- `VodPlaybackController#selectEpisode(name, index)`：按**名字 → 序号**在**所有线路**里查找，必要时先切线路再选集；找不到返回 false。
- 两端 `VideoActivity#switchTo()` 改为：先本地（同一部剧）按名字/序号选集，成功即返回；找不到且对端带源时才切源。
- `SyncPlayManager` 增加 `localItem` / `pendingRemote` / `flushPendingRemote()`：
  - 收到的远端换集若当时 handler 不可用，先缓存，`onStart` 注册 handler 或剧集列表刷新后自动补切；
  - `Set.user.file` 即时更新用户列表缓存，`adoptAuthority()` 不再用旧集覆盖；
  - `EpisodeHandler` 从 `onResume/onPause` 挪到 `onStart/onStop`（PiP、权限弹窗等 onPause-only 场景不再丢事件）；
  - `startPlayback()` 显式 `onLocalMediaChanged(getCurrentItem())`，不再回退到剧名；
  - 关键路径加 `Log.d("SyncPlay", ...)`（`adb logcat -s SyncPlay` 可看收发/切换过程）。
- 跨源待选集数改用 `selectEpisode(name, index)`，并保留「找不到」提示。

**本地验证（真 Syncplay 服务器 + JVM 驱动，含 manager 真实代码）**

```
--- A switches to ep2 while B has no handler
[SyncPlay] file from alice: 第2集|siteA|vod1|1|测试剧集
B local=第1集 (handler 未注册，先缓存)
--- B screen resumes
[SyncPlay] apply remote 第2集...
[B] switchTo(第2集 idx=1 site=siteA vodName=测试剧集)
B local=第2集
--- A switches to ep3
[B] switchTo(第3集 idx=2 ...)
B local=第3集
ALL OK
```

**回归验证清单**

- [ ] 两端同站点：A 换集 → B 3 秒内跟进，且不会被拉回
- [ ] 两端不同站点/不同搜索条目（同一部剧）：B 按本地同名/同序号集切换，不再误切源
- [ ] B 在后台/PiP 时 A 换集，B 回到播放页后自动跟到当前集
- [ ] A 切到另一条线路的剧集，B 也能跟到
- [ ] 用户在播放页内连续换集不会来回跳（防回环）

---

## 附：本轮发布信息

- 分支：`syncplay-5.5.6`（`fongmi` 保持 5.8.9；旧状态在 `backup/5.8.9-syncplay`）
- 本次版本：`5.5.10-syncplay`（versionCode `560`，tag `v5.5.10-syncplay`）
- CI：`.github/workflows/release.yml`（先从 `FongMi/media` 的 `3c2cbe8ac7` 构建 20 个 media3 AAR，再打 4 个 APK 并发布）
  - 发布步骤加了 `target_commitish: ${{ github.sha }}`：之前手动触发时 tag 被建在默认分支，`v5.5.6`~`v5.5.9` 四个 tag 全部指向 `2a3115ee6`；现在保证 tag 指向实际构建的提交。
- 签名：仓库 Secrets `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`（keystore 备份在维护者本机 `~/.tv-signing/`）
- 已修：`Player is accessed on the wrong thread`（连接前在主线程快照播放器状态）；自定义服务器/昵称/房间持久化；输入框文字贴边
