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

    // A scheduled run imports first, reloads so React state sees the new records,
    // then this fresh mount sends Android HOME. This leaves the phone on its launcher
    // so the normal screen timeout can sleep it.
    if(sessionStorage.getItem(NIGHTLY_HOME_FLAG)==='1'){
      sessionStorage.removeItem(NIGHTLY_HOME_FLAG);
      window.setTimeout(()=>AutoCapture.finishScheduledRun().catch(console.warn),700);
    }

    const poll=async()=>{
      if(finishing.current)return;
      try{
        const status=await AutoCapture.getStatus();
        if(status?.unifiedSyncRunning){wasRunning.current=true;return;}
        if(!wasRunning.current)return;
        wasRunning.current=false;
        finishing.current=true;
        const scheduled=Boolean(status?.unifiedSyncScheduledRun);
        await importCapturedTransactions();
        // Always reopen the refreshed app on Taiwan/Home so newly imported rows and
        // updated reward usage are immediately reflected in React state.
        localStorage.setItem('country','TW');
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
