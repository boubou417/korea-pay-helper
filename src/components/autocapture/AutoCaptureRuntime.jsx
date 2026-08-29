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

    if(sessionStorage.getItem(NIGHTLY_HOME_FLAG)==='1'){
      sessionStorage.removeItem(NIGHTLY_HOME_FLAG);
      window.setTimeout(()=>AutoCapture.goDeviceHome().catch(console.warn),700);
    }

    const poll=async()=>{
      if(finishing.current)return;
      try{
        const status=await AutoCapture.getStatus();
        if(status?.unifiedSyncRunning){wasRunning.current=true;return;}
        const completed=wasRunning.current || Number(status?.unifiedSyncStage)===4;
        if(!completed)return;

        wasRunning.current=false;
        finishing.current=true;
        const scheduled=Boolean(status?.unifiedSyncScheduledRun);
        const merged=await importCapturedTransactions();
        await AutoCapture.recordImportResult({added:Number(merged?.added||0),matched:Number(merged?.matched||0),unmatched:Number(merged?.unmatched||0)}).catch(console.warn);
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
