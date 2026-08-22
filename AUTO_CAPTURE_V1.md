# Pay Helper Android Auto Capture V1

整合分支：`agent/android-auto-capture-v1`

這一版把原本 NotificationPayTest 的 Android Accessibility 擷取能力整合進 Pay Helper（Capacitor Android），讓 Pay Helper 成為主要 App。

## 已整合來源

- 街口支付：正式快速同步
- LINE Pay：正式快速同步
- Pi 拍錢包：正式快速同步
- Google Wallet / Google Pay：交易紀錄 Accessibility 診斷版

Google Wallet 不以付款通知為主要來源。這一版直接開啟 Google Wallet，保存「查看更多交易」與單筆交易明細的 Accessibility Tree。取得第一份真實 Tree 後，再升級為正式快速同步 parser。

## Android 第一次使用

1. 安裝 APK 並開啟 Pay Helper。
2. 到「設定」→「自動消費同步（Android）」。
3. 按「開啟 Pay Helper 無障礙服務」。
4. 在 Android 無障礙設定啟用「Pay Helper 自動消費同步」。
5. 回到 Pay Helper。

## 街口 / LINE Pay / Pi

分別按：

- 同步街口最新交易
- 同步 LINE Pay 最新交易
- 同步 Pi 拍錢包最新交易

完成後回到 Pay Helper，按「匯入自動消費到台灣紀錄」。資料會去重後寫入 `historyMap.TW`，並依日期排序。

自動匯入記錄會保留：平台、店家、金額、銀行、卡號末四碼、交易編號等可取得欄位。

Pi 舊交易若超過 30 天、交易編號等主要明細已取得，但舊版 Accessibility 本身沒有提供銀行/末四碼，會視為已處理，避免每天快速同步反覆追舊資料。

## Google Wallet / Google Pay 第一次診斷

1. Pay Helper 設定 →「Google Wallet：掃交易記錄（診斷）」。
2. Google Wallet 開啟後，進入「查看更多交易 / 更多交易」。
3. 讓交易列表停留一下。
4. 點開至少一筆實體感應付款交易。
5. 最好再點開一筆「網路 Google Pay / 虛擬卡」交易（如果 Wallet 中看得到）。
6. 回 Pay Helper。
7. 按「匯出 Google Wallet 診斷 JSON」。
8. 將 JSON 提供給開發端，下一版即可依真實 row/detail Tree 做正式 parser。

## 資料設計

Pay Helper React 端統一使用：

- `source`: `JKOPAY` / `LINE_PAY` / `PI_WALLET` / 後續 `GOOGLE_WALLET`
- `sourceLabel`
- `shop`
- `amount`
- `date`
- `paymentMethod`
- `paymentAccount`
- `bank`
- `cardLast4`
- `transactionId`
- `detailChecked`

匯入 `historyMap.TW` 後會加上 `autoCaptureKey` 去重。

## 目前限制

- 第一版不自動把擷取到的信用卡交易累加到 Pay Helper 設定中的 `payments[].used`，因為不同平台的卡片名稱格式不同；後續會以末四碼/卡名建立可確認的對應規則。
- Google Wallet 第一版是 Tree 診斷，不宣稱已能完整解析所有 Google Pay 交易。
- Android Accessibility UI 可能因 App 版本更新而改變，因此保留診斷 Tree 能力供後續維護。
