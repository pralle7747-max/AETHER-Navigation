const {app,BrowserWindow,protocol,net,session,shell,globalShortcut}=require('electron');
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
    webPreferences:{contextIsolation:true,sandbox:true,spellcheck:false}});
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
});
app.on('window-all-closed',()=>app.quit());
