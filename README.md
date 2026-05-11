# Hojo-Crosspoint

Hojo-Crosspoint 是一個針對 CrossPoint 中文閱讀器調整的 Android companion app，用來連線裝置、管理檔案、轉換 EPUB、轉換字型，以及把內容上傳到閱讀器。

目前版本：`cpChTyZh.V1.2`

> 這是以 Hojo 為基礎改作的社群版本，並非 CrossPoint 或原 Hojo 專案的官方發行版。請自行評估風險後使用。

## 目前定位

這個版本主要服務 CrossPoint 中文閱讀器使用流程：

- 預設連線到 `http://crosspoint.local/`
- 若預設網址無法連線，會跳出手動輸入網址對話框
- 手動輸入的網址會被記住，下次預設網址連不上時會自動嘗試
- 支援 device-bound network client，避免手機同時有網際網路與裝置區網時 API 走錯網路
- Android 11 以上可安裝使用

## 主要功能

### 連線管理

- 首頁顯示目前連線狀態
- 連線成功後，原本的連線按鈕會切換成「離線」
- 按下「離線」會中斷目前裝置連線，並把畫面狀態重置為未連線
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

### EPUB 轉換

- 首頁功能名稱為「EPUB轉換」
- 選擇 EPUB 檔案後轉換為 XTC
- 支援預覽轉換結果
- 可儲存到 Downloads
- 可上傳到裝置
- 保留原本轉換流程與任務紀錄

### 字型轉換

- 首頁新增「字型轉換」功能，位置在 EPUB 轉換後、設定前
- 支援選擇 OTF、TTF、TTC 字型檔
- 轉換輸出 CrossPoint 可用的 `.epdfont`
- 預設輸出檔名為 `原字型名.epdfont`
- 預設會輸出到選擇前字型檔所在位置
- 如果原資料夾無法寫入，會自動輸出到 Downloads
- 轉換完成後會在畫面上顯示輸出檔案名稱與位置
- 字型大小可在選檔畫面調整
- 字型大小旁有 `-` 與 `+` 按鈕
- 上次輸入的字型大小會被記錄，下次開啟會自動帶入

### 快速連結

- 可輸入網址並轉換為裝置可讀內容
- 轉換完成後可送到裝置
- 適合快速把網頁文章丟到閱讀器

### 桌布

- 選擇圖片
- 裁切與調整顯示效果
- 支援直向與橫向
- 可調整灰階、對比、亮度、飽和度

### XTC 渲染器

- 可選擇 `.xtc` 檔案進行檢視
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
versionCode = 3
versionName = "cpChTyZh.V1.2"
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
- 字型轉換的輸出位置取決於 Android 檔案權限；不能寫回原位置時會改存到 Downloads

## 版本摘要

`cpChTyZh.V1.2` 包含：

- App 名稱改為 Hojo-Crosspoint
- 預設 CrossPoint host 改為 `http://crosspoint.local/`
- 支援手動輸入與記錄 CrossPoint LAN 網址
- 全介面支援正體中文 / 英文切換
- 預設語言為正體中文
- 首頁轉換功能改名為 EPUB轉換
- 新增字型轉換功能，輸出 `.epdfont`
- 字型大小可調整並記住上次設定
- 修正連線狀態可能顯示錯誤的問題
- 新增離線按鈕
- 新增首頁下拉重新檢查連線與中央 loading
- 檔案管理、裝置設定與上傳流程改用 device-bound client
- Java/Kotlin 編譯目標升級至 17

## License

請參考原專案授權設定。此分支是針對 CrossPoint 中文閱讀器的客製化版本。
