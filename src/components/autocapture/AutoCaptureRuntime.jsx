import { useEffect, useRef } from 'react';
import { AutoCapture, ensureNightlySync, importCapturedTransactions, isAutoCaptureAvailable } from '../../services/autoCapture';

const NIGHTLY_HOME_FLAG='payhelper.nightly.home.after.reload';

export default function AutoCaptureRuntime(){
  const native=isAutoCaptureAvailable();
  const wasRunning=useRef(false);
  const finishing=useRef(false);

  useEffect(()=>{
    if(!native)return;
    ensureNightlySync().catch(console.warn);

    // After a scheduled run has already imported and reloaded, move from Pay Helper
    // to the Android launcher. The phone's own screen timeout can then sleep normally.
    if(sessionStorage.getItem(NIGHTLY_HOME_FLAG)==='1'){
      sessionStorage.removeItem(NIGHTLY_HOME_FLAG);
      window.setTimeout(()=>AutoCapture.goDeviceHome().catch(console.warn),700);
    }

    const poll=async()=>{
      if(finishing.current)return;
      try{
        const status=await AutoCapture.getStatus();
        if(status?.unifiedSyncRunning){wasRunning.current=true;return;}

        // A nightly run can spend almost all of its time with the WebView backgrounded,
        // so JS may never observe running=true. Native stage=4 is therefore also a
        // completion signal and guarantees the final import still happens.
        const completed=wasRunning.current || Number(status?.unifiedSyncStage)===4;
        if(!completed)return;

        wasRunning.current=false;
        finishing.current=true;
        const scheduled=Boolean(status?.unifiedSyncScheduledRun);
        await importCapturedTransactions();
        localStorage.setItem('country','TW');
        await AutoCapture.acknowledgeImportedRun();
        if(scheduled)sessionStorage.setItem(NIGHTLY_HOME_FLAG,'1');
        window.setTimeout(()=>window.location.reload(),180);
      }catch(error){
        console.warn('AutoCaptureRuntime:',error);
        finishing.current=false;
      }
    };
    const timer=window.setInterval(poll,900);
    const onVisible=()=>{if(!document.hidden)poll();};
    document.addEventListener('visibilitychange',onVisible);
    window.addEventListener('focus',poll);
    poll();
    return()=>{window.clearInterval(timer);document.removeEventListener('visibilitychange',onVisible);window.removeEventListener('focus',poll);};
  },[native]);

  return null;
}
