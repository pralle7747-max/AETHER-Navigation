const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('aetherPC',{mini:on=>ipcRenderer.send('aether-mini',!!on),icon:url=>ipcRenderer.send('aether-icon',String(url))});
