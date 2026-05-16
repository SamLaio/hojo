# Hojo-Crosspoint

Hojo-Crosspoint 是一個針對 CrossPoint 中文閱讀器調整的 Android companion app，用來連線裝置、管理檔案、轉換 EPUB、轉換字型，以及把內容上傳到閱讀器。

目前版本：`cpChTyZh.V1.5`

> 這是以 Hojo 為基礎改作的社群版本，並非 CrossPoint 或原 Hojo 專案的官方發行版。請自行評估風險後使用。

## 目前定位

這個版本主要服務 CrossPoint 中文閱讀器使用流程：

- 預設連線到 `http://crosspoint.local/`
- 若預設網址無法連線，會跳出手動輸入網址對話框
- 手動輸入的網址會被記住，下次預設網址連不上時會自動嘗試
- 支援 device-bound network client，避免手機同時有網際網路與裝置區網時 API 走錯網路
- Android 11 以上可安裝使用
- GitHub 原始碼：`https://github.com/SamLaio/hojo`

## 主要功能

### 連線管理

- 首頁顯示目前連線狀態
- 連線成功後，原本的連線按鈕會切換成「離線」
- 按下「離線」會中斷目前裝置連線，並把畫面狀態重置為未連線
- 底部連線狀態列會顯示「已連線 / 未連線」，右側提供「重新連線」
- 首頁向下拉動可重新檢查連線狀態
- 下拉檢查觸發時，畫面中央會顯示 loading 圖示
- 連線失敗時會清除錯誤的已連線狀態，避免裝置斷線但 UI 仍顯示已連線

### 檔案管理

- 瀏覽 CrossPoint 裝置上的檔案與資料夾
- 新增資料夾
- 上傳檔案
- 下載檔案
- 重新命名
- 移動
- 刪除
- File Manager 相關 HTTP API 會優先使用 device-bound client
- WebSocket 上傳也會優先使用 device-bound client
- 若找不到裝置網路，會 fallback 到原本的 OkHttpClient
- WebSocket 上傳會分片並等待送出佇列消化，避免大檔案直接塞滿送出佇列
- 上傳任務會顯示進行中、完成、失敗與重試狀態，常見的未連線錯誤會顯示本地化訊息

### EPUB 轉換

- 首頁功能名稱為「EPUB轉換」
- 選擇 EPUB 檔案後轉換為 CrossPoint 2-bit 灰階書本格式 `.xtch`
- 可切換 1-bit 黑白模式輸出 `.xtc`
- 轉換固定使用 CrossPoint X4 尺寸 `480x800`，方向可選 `0° / 90° / 180° / 270°`
- EPUB 檔案選擇器會允許從 Downloads 等位置選取被系統標錯 MIME 的 `.epub`，並以檔名驗證副檔名
- 支援預覽轉換結果
- 可儲存到 Downloads
- 可上傳到裝置根目錄
- 預覽頁上傳時會先顯示已加入任務，上傳完成或失敗後會在原頁面顯示結果
- 轉換中的進度顯示為 `0%` 到 `100%`
- 轉換時會優先註冊 Android 系統內建 CJK 字型作為 fallback，減少中文被渲染成 `?` 的情況
- 自訂字型匯入後會正確套用為 EPUB 轉換字型
- 自訂字型與系統 CJK 字型會以分片方式傳入 WebView / WASM，避免大型字型因單次 JavaScript 字串過長而失敗
- 字型選單新增 `Noto Sans CJK TC`

### 字型轉換

- 首頁新增「字型轉換」功能，位置在 EPUB 轉換後、設定前
- 支援選擇 OTF、TTF、TTC 字型檔
- 轉換輸出 CrossPoint 可用的 `.epdfont` V1 binary 格式
- 預設字型大小為 `18`
- 預設輸出檔名為 `原字型名-字型大小.epdfont`，例如 `MyFont-18.epdfont`
- 選擇字型後不會自動產生，需按下「開始」才會轉換
- 「開始」按鈕位於選擇字型與選擇字集按鈕下方，並使用滿版寬度
- 可透過「選擇字集」多選要產生的字元範圍
- 內建教育部 `4808` 個常用國字與 `6343` 個次常用國字字表，並可離線使用
- 字集選項包含：正體中文常用字、正體中文次常用字、日文、簡體中文、注音符號、英文、拉丁字母、韓文、希臘文與西里爾文、符號/箭頭/數學
- 產字時會固定加入數字、基本半形符號、全形符號、中文標點、直排標點，以及橫排/直排破折號
- 英文字母需勾選「英文」才會加入
- 轉換完成後會先產生 app 暫存結果，不會自動寫回原資料夾
- 結果頁提供「儲存」與「上傳」選項
- 按下「儲存」會輸出到 Downloads
- 按下「上傳」會把 `.epdfont` 加入上傳任務，目標為裝置根目錄
- 上傳中會在字型轉換頁顯示進度，完成或失敗後會顯示結果
- 字型大小可在選檔畫面調整
- 字型大小旁有 `-` 與 `+` 按鈕
- 上次輸入的字型大小會被記錄，下次開啟會自動帶入

### 快速連結

- 可輸入網址，app 會先切到可上網的網路並用隱藏 WebView 載入頁面
- 透過 bundled `readability-lite.js` 擷取文章主文
- 會嘗試將 4 MB 以下的圖片轉成 base64 data URL，讓後續離線轉換可保留圖片
- 擷取出的 HTML 會包成最小 EPUB，再轉換為 2-bit `.xtch` 裝置可讀內容
- 轉換完成後會切回 CrossPoint 裝置網路並顯示預覽
- 可將預覽檔加入上傳任務並送到裝置根目錄
- 適合快速把網頁文章丟到閱讀器；需要登入、反爬或高度動態載入的網頁可能不適合

### 桌布

- 方向選擇頁左上角提供返回箭頭
- 選擇圖片
- 裁切與調整顯示效果
- 支援直向與橫向
- 可調整灰階、對比、亮度、飽和度
- 輸出為 CrossPoint 可讀取的 `/sleep_mask.png`

### XTC 渲染器

- 可選擇 `.xtc` 或 `.xtch` 檔案進行檢視
- 支援頁面預覽與翻頁
- 用於檢查轉換結果

### 裝置設定

- Device Settings 會透過 device-bound client 讀取 CrossPoint 設定
- 針對裝置回傳 HTML 或非預期 JSON 時，會用較安全的錯誤處理，避免直接崩潰

### 應用程式設定

- 主題：跟隨系統、淺色、深色
- 語言：正體中文、英文
- 預設語言為正體中文
- App 名稱：`Hojo-Crosspoint`
- GitHub 原始碼連結：`https://github.com/SamLaio/hojo`

## 連線行為

預設連線流程：

1. 先嘗試 `http://crosspoint.local/`
2. 如果曾經手動輸入過網址，預設網址失敗後會再嘗試該網址
3. 如果仍失敗，顯示手動輸入網址對話框
4. 使用者輸入的網址會被標準化並保存
5. 連線成功後，檔案管理與裝置設定會優先走裝置所在網路

可手動輸入的範例：

```text
http://192.168.50.202
192.168.50.202
```

如果沒有輸入 `http://` 或 `https://`，app 會自動補上 `http://`。

## 建置環境

### 需求

- Android Studio Hedgehog 或更新版本
- JDK 17 或更新版本
- Android SDK 35
- Gradle wrapper 使用專案內建的 `gradlew` / `gradlew.bat`

### Android 設定

目前 Android 設定：

```kotlin
applicationId = "wtf.anurag.hojo.crosspoint"
minSdk = 30
targetSdk = 35
compileSdk = 35
versionCode = 6
versionName = "cpChTyZh.V1.5"
```

`minSdk = 30` 代表 Android 11 以上可安裝。

### local.properties

如果 Android Studio 沒有自動產生 `local.properties`，請在專案根目錄建立：

```properties
sdk.dir=C:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

請依照自己電腦上的 Android SDK 路徑調整。

## 建置指令

Windows：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug --no-daemon
```

Debug APK 位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK：

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

Release APK 位置：

```text
app/build/outputs/apk/release/
```

## 權限

此 app 會使用下列 Android 權限：

- `INTERNET`：與 CrossPoint 裝置 API 通訊
- `WAKE_LOCK`：上傳或背景工作期間維持流程
- `ACCESS_WIFI_STATE`：讀取 Wi-Fi 狀態
- `CHANGE_WIFI_STATE`：協助連線裝置 Wi-Fi
- `CHANGE_WIFI_MULTICAST_STATE`：網路探索相關操作
- `ACCESS_NETWORK_STATE`：監控目前網路狀態
- `CHANGE_NETWORK_STATE`：綁定特定網路
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`：Android 12 以下 Wi-Fi 掃描需要
- `NEARBY_WIFI_DEVICES`：Android 13 以上 Wi-Fi 裝置探索需要
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`：背景上傳任務
- `POST_NOTIFICATIONS`：上傳通知

## 專案結構

```text
app/src/main/java/wtf/anurag/hojo/
├── connectivity/
│   ├── EpaperConnectivityManager.kt
│   └── NetworkBoundClientFactory.kt
├── data/
│   ├── DefaultConnectivityRepository.kt
│   ├── FileManagerRepository.kt
│   ├── DeviceSettingsRepository.kt
│   └── model/
├── di/
│   └── AppModule.kt
├── service/
│   └── UploadService.kt
├── ui/
│   ├── MainScreen.kt
│   ├── apps/
│   │   ├── converter/
│   │   ├── devicesettings/
│   │   ├── filemanager/
│   │   ├── fontconverter/
│   │   ├── quicklink/
│   │   ├── renderer/
│   │   ├── settings/
│   │   ├── tasks/
│   │   └── wallpaper/
│   ├── components/
│   ├── i18n/
│   ├── theme/
│   └── viewmodels/
├── HojoApplication.kt
└── MainActivity.kt
```

## 技術棧

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- Hilt
- StateFlow / ViewModel
- OkHttp
- Gson
- Android Storage Access Framework
- WebView / bundled conversion assets

## 使用注意

- CrossPoint 裝置 API 回傳格式可能會依韌體不同而變動
- 如果檔案列表或設定頁出現 HTML / 字串而不是 JSON，app 會顯示錯誤訊息而不是直接崩潰
- 手動網址會保存在 app preferences 中
- 若切換不同 CrossPoint 裝置，必要時可重新輸入新的 LAN 網址
- 字型轉換完成後會先保留在 app cache；需要保留檔案時請按「儲存」輸出到 Downloads
- 書本、字型與快速連結上傳會加入任務佇列；上傳完成或失敗可在原功能頁或任務頁查看

## 版本摘要

`cpChTyZh.V1.5` 包含：

- 版本號更新為 `cpChTyZh.V1.5`
- `versionCode` 更新為 `6`
- EPUB 轉換新增 Android 系統 CJK 字型 fallback，改善中文正文顯示為 `?` 的問題
- EPUB 轉換修正自訂字型匯入後未真正套用的問題
- EPUB 轉換改用分片方式註冊自訂字型與系統 CJK 字型，降低大型字型傳入 WebView 失敗機率
- EPUB 轉換新增 `Noto Sans CJK TC` 字型選項
- EPUB 小檔案單一 chunk 載入流程修正，避免 EPUB 尚未載入完成就啟動轉換
- 字型轉換改為「選擇字型」與「開始」分離，避免選檔後立刻產生
- 字型轉換新增多選字集對話框
- 字型轉換新增教育部 `4808` 個常用國字與 `6343` 個次常用國字內建字表
- 字型轉換新增日文、簡體中文、注音、英文、拉丁字母、韓文、希臘文/西里爾文、符號/箭頭/數學等字集選項
- 字型轉換固定加入數字、基本半形符號、全形符號、中文標點、直排標點與橫/直破折號
- 字型轉換的英文字母改為需勾選「英文」才會加入
- 設定頁 GitHub 原始碼連結改為 `https://github.com/SamLaio/hojo`
- App 名稱改為 Hojo-Crosspoint
- 預設 CrossPoint host 改為 `http://crosspoint.local/`
- 支援手動輸入與記錄 CrossPoint LAN 網址
- 全介面支援正體中文 / 英文切換
- 預設語言為正體中文
- 首頁轉換功能改名為 EPUB轉換
- 新增字型轉換功能，輸出 `.epdfont`
- 字型大小可調整並記住上次設定，預設對齊 CrossPoint 工具的 `18`
- 字型轉換結果頁新增「儲存」與「上傳」
- EPUB 與快速連結轉換預設使用 2-bit `.xtch`
- EPUB 轉換移除 X3 型號選項，固定以 X4 `480x800` 輸出
- EPUB 檔案選擇改為支援 MIME 標錯的 Downloads 檔案，並以 `.epub` 副檔名驗證
- EPUB 轉換進度改為顯示 `0%` 到 `100%`
- 圖片工具會輸出 `/sleep_mask.png`，對齊 CrossPoint 睡眠圖片格式
- 桌布方向選擇頁新增返回箭頭
- 修正連線狀態可能顯示錯誤的問題
- 新增離線按鈕
- 底部連線列新增「重新連線」按鈕
- 新增首頁下拉重新檢查連線與中央 loading
- 檔案管理、裝置設定與上傳流程改用 device-bound client
- 上傳改為延後啟動 foreground service，避免快速失敗造成 foreground service timeout 閃退
- WebSocket 上傳加入分片與送出佇列節流，避免 `WebSocket send queue full`
- 書本與字型上傳會在原功能頁顯示進行中、成功或失敗
- Java/Kotlin 編譯目標升級至 17

## License

請參考原專案授權設定。此分支是針對 CrossPoint 中文閱讀器的客製化版本。
