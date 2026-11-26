const {app,BrowserWindow,protocol,net,session,shell,globalShortcut,ipcMain,nativeImage}=require('electron');
const path=require('path');const {pathToFileURL}=require('url');
app.commandLine.appendSwitch('enable-features','WinrtGeolocationImplementation');
protocol.registerSchemesAsPrivileged([{scheme:'aether',privileges:{standard:true,secure:true,supportFetchAPI:true,corsEnabled:true,stream:true,allowServiceWorkers:true}}]);
if(!app.requestSingleInstanceLock())app.quit();
let win=null;
app.on('second-instance',()=>{if(win){if(win.isMinimized())win.restore();win.focus();}});
app.whenReady().then(()=>{
  const root=path.join(__dirname,'www');
  protocol.handle('aether',req=>{
    let p=decodeURIComponent(new URL(req.url).pathname);if(!p||p==='/')p='/index.html';
    const f=path.normalize(path.join(root,p));
    if(!f.startsWith(root))return new Response('forbidden',{status:403});
    return net.fetch(pathToFileURL(f).toString());
  });
  const ok=['geolocation','media','notifications','clipboard-sanitized-write','fullscreen','speaker-selection'];
  session.defaultSession.setPermissionRequestHandler((wc,perm,cb)=>cb(ok.includes(perm)));
  session.defaultSession.setPermissionCheckHandler((wc,perm)=>ok.includes(perm));
  win=new BrowserWindow({width:1280,height:820,minWidth:380,minHeight:600,title:'AETHER',icon:path.join(__dirname,'icon.png'),backgroundColor:'#000000',autoHideMenuBar:true,
    webPreferences:{contextIsolation:true,sandbox:true,spellcheck:false,preload:path.join(__dirname,'preload.js')}});
  win.removeMenu();
  win.webContents.setWindowOpenHandler(({url})=>{if(/^(https?|mailto|tel|sms):/.test(url))shell.openExternal(url);return {action:'deny'};});
  win.webContents.on('will-navigate',(e,url)=>{if(!url.startsWith('aether://')){e.preventDefault();if(/^(https?|mailto|tel|sms):/.test(url))shell.openExternal(url);}});
  win.webContents.on('before-input-event',(e,i)=>{
    if(i.type!=='keyDown')return;
    if(i.key==='F11'){win.setFullScreen(!win.isFullScreen());e.preventDefault();}
    else if(i.key==='Escape'&&win.isFullScreen()){win.setFullScreen(false);}
    else if(i.key==='F5'||(i.control&&i.key.toLowerCase()==='r')){win.webContents.reload();e.preventDefault();}
  });
  win.loadURL('aether://app/index.html');
  let mini=null;
  ipcMain.on('aether-mini',(e,on)=>{
    if(!on){if(mini&&!mini.isDestroyed())mini.close();mini=null;return;}
    if(mini&&!mini.isDestroyed()){mini.show();mini.focus();return;}
    mini=new BrowserWindow({width:360,height:440,alwaysOnTop:true,title:'AETHER Abfahrten',icon:path.join(__dirname,'icon.png'),autoHideMenuBar:true,webPreferences:{contextIsolation:true,sandbox:true,preload:path.join(__dirname,'preload.js')}});
    mini.removeMenu();mini.setAlwaysOnTop(true,'floating');mini.loadURL('aether://app/index.html#mini');mini.on('closed',()=>{mini=null;});
  });
  ipcMain.on('aether-icon',(e,url)=>{try{const img=nativeImage.createFromDataURL(url);if(!img.isEmpty()&&win&&!win.isDestroyed())win.setIcon(img);}catch(x){}});
});
// ---- auto update of the PC app itself (web updates come through the app) ----
async function autoUpdate(){
  try{
    const fs=require('fs'),os=require('os'),{spawn}=require('child_process');
    const r=await net.fetch('https://raw.githubusercontent.com/pralle7747-max/AETHER-Navigation/ccr-cb7b3725-tx5sew/docs/version.json?t='+Date.now(),{cache:'no-store'});
    const m=await r.json(),mine=parseInt(app.getVersion(),10)||0;
    if(!(m.exe>mine)||!m.setupUrl)return;
    const res=await net.fetch(m.setupUrl);if(!res.ok)return;
    const buf=Buffer.from(await res.arrayBuffer());if(buf.length<20e6)return;
    const f=path.join(os.tmpdir(),'AETHER-Setup-v'+m.exe+'.exe');fs.writeFileSync(f,buf);
    const go=()=>{spawn(f,['/S'],{detached:true,stdio:'ignore'}).unref();app.quit();};
    if(win&&!win.isDestroyed()){
      const {dialog}=require('electron');
      const c=await dialog.showMessageBox(win,{type:'info',buttons:['Jetzt neu starten','Beim Beenden'],defaultId:0,title:'AETHER Update',message:'AETHER Version '+m.exe+' ist bereit.',detail:m.notes||''});
      if(c.response===0)go();else app.once('before-quit',()=>{spawn(f,['/S'],{detached:true,stdio:'ignore'}).unref();});
    }
  }catch(e){}
}
app.whenReady().then(()=>setTimeout(autoUpdate,8000));
app.on('window-all-closed',()=>app.quit());
