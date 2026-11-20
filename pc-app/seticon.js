// usage: node seticon.js exe ico version
const fs=require('fs'),ResEdit=require('resedit'),PE=require('pe-library');
const [exe,ico,ver]=process.argv.slice(2);
const e=PE.NtExecutable.from(fs.readFileSync(exe)),res=PE.NtExecutableResource.from(e);
const iconFile=ResEdit.Data.IconFile.from(fs.readFileSync(ico));
const groups=ResEdit.Resource.IconGroupEntry.fromEntries(res.entries);
const gid=groups.length?groups[0].id:1,lang=groups.length?groups[0].lang:1033;
ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries,gid,lang,iconFile.icons.map(i=>i.data));
const vi=ResEdit.Resource.VersionInfo.fromEntries(res.entries)[0];
const v=(ver+'.0.0.0').split('.').slice(0,4).map(Number);
vi.setFileVersion(...v,1033);vi.setProductVersion(...v,1033);
vi.setStringValues({lang:1033,codepage:1200},{FileDescription:'AETHER Navigation',ProductName:'AETHER',InternalName:'AETHER',OriginalFilename:'AETHER.exe',CompanyName:'AETHER',LegalCopyright:''});
vi.outputToResourceEntries(res.entries);
res.outputResource(e);fs.writeFileSync(exe,Buffer.from(e.generate()));
console.log('icon+version set');
