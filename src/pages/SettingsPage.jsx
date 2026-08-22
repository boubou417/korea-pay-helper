import React from 'react';
import SettingsCenter from '../components/settings/SettingsCenter';
import AutoCapturePanel from '../components/autocapture/AutoCapturePanel';

export default function SettingsPage(props) {
  return (
    <>
      <AutoCapturePanel darkMode={props.darkMode} />
      <SettingsCenter {...props} />
    </>
  );
}
