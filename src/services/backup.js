import { Capacitor } from '@capacitor/core';
import { Directory, Encoding, Filesystem } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';

const buildFileName = () => {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');

  return `PayHelper_${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}.json`;
};

const exportOnWeb = (jsonText, fileName) => {
  const blob = new Blob([jsonText], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');

  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();

  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

const exportOnNative = async (jsonText, fileName) => {
  // 只要這一步成功，就代表備份檔已建立成功。
  const result = await Filesystem.writeFile({
    path: fileName,
    data: jsonText,
    directory: Directory.Cache,
    encoding: Encoding.UTF8,
    recursive: true
  });

  let shareOpened = false;

  // 部分 Android 手機在分享成功、取消或關閉面板後，
  // Share.share() 仍可能 reject。分享只是附加步驟，
  // 不應把已成功建立的備份誤判為匯出失敗。
  try {
    await Share.share({
      title: 'Pay Helper 備份',
      text: '請選擇「儲存到檔案」或其他檔案管理 App 來保存備份。',
      files: [result.uri],
      dialogTitle: '匯出 Pay Helper 備份'
    });
    shareOpened = true;
  } catch (error) {
    console.warn('Android 分享面板已關閉或回傳例外，但備份檔已成功建立：', error);
  }

  return {
    uri: result.uri,
    shareOpened
  };
};

export async function exportBackup(data) {
  const fileName = buildFileName();
  const jsonText = JSON.stringify(data, null, 2);

  if (Capacitor.isNativePlatform()) {
    const nativeResult = await exportOnNative(jsonText, fileName);

    return {
      fileName,
      uri: nativeResult.uri,
      platform: 'native',
      shareOpened: nativeResult.shareOpened
    };
  }

  exportOnWeb(jsonText, fileName);

  return {
    fileName,
    uri: null,
    platform: 'web',
    shareOpened: false
  };
}

export async function readBackupFile(file) {
  if (!file) {
    throw new Error('NO_FILE');
  }

  const text = await file.text();
  const parsed = JSON.parse(text);

  if (!parsed.settingsMap || !parsed.historyMap) {
    throw new Error('INVALID_BACKUP');
  }

  return parsed;
}
