Korea Pay Helper V5 Alpha 1

本版內容：
1. 全新設定中心
2. 國家設定頁
3. 支付方式管理頁（依韓國／日本／台灣收合）
4. 匯率管理頁（可分別修改各國匯率）
5. 資料管理頁（匯出／匯入／清除）
6. 深色模式移入設定中心
7. 關於頁
8. 保留既有首頁、拆單、記錄、統計、LocalStorage 與 APK 備份功能

安裝：
1. 解壓縮後開啟 PowerShell
2. 執行 npm config set registry https://registry.npmjs.org/
3. 執行 npm install
4. 執行 npm run build
5. 執行 npx cap sync android
6. 執行 npx cap open android

注意：
- 此 ZIP 不含 node_modules，避免帶入其他電腦或內部 registry 路徑。
- 第一次 npm install 後才會安裝 @capacitor/filesystem 與 @capacitor/share。
