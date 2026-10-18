(() => {
document.querySelectorAll('.uml-delete-view').forEach(deleteForm=>{
 deleteForm.addEventListener('submit',event=>{
  if(!window.confirm('确定删除“'+deleteForm.dataset.viewName+'”？\n该图的所有元素和连线将一并删除，无法恢复。关联的用户故事和任务会保留。'))event.preventDefault();
 });
});
const root=document.getElementById('uml-editor');if(!root)return;
const libraryToggle=document.getElementById('uml-library-toggle');
if(libraryToggle)libraryToggle.addEventListener('click',()=>{const library=document.getElementById('uml-project-library');library.hidden=!library.hidden;libraryToggle.setAttribute('aria-expanded',String(!library.hidden));});
const svg=document.getElementById('uml-canvas'),status=document.getElementById('uml-save-status'),form=document.getElementById('uml-node-form'),editable=root.dataset.editable==='true';
const ns='http://www.w3.org/2000/svg';let data,selected=null,selectedEdge=null;
const edgeForm=document.getElementById('uml-edge-form');
let connecting=false,connectionSource=null,busy=false;
async function postAction(action,fields){const body=new URLSearchParams({action,projectId:root.dataset.projectId,viewId:root.dataset.viewId,csrfToken:root.dataset.csrf,...fields});const response=await fetch('/uml',{method:'POST',body});if(!response.ok||new URL(response.url).pathname!=='/uml')throw Error('保存失败，请重新登录或刷新页面');const html=new DOMParser().parseFromString(await response.text(),'text/html');const error=html.querySelector('.notice.error');if(error)throw Error(error.textContent);if(!html.querySelector('#uml-action-success'))throw Error('保存未确认，请刷新');}
function showError(message){status.textContent=message;status.hidden=false;}
function clearError(){status.textContent='';status.hidden=true;}
function toggleConnect(){connecting=!connecting;connectionSource=null;const button=document.getElementById('uml-connect');button.classList.toggle('connecting',connecting);button.setAttribute('aria-pressed',String(connecting));button.textContent=connecting?'取消连线':'点选连线';clearError();}
async function choose(node){if(!connecting){select(node);return;}if(busy)return;if(connectionSource===null){connectionSource=node.id;selected=node.id;render();return;}
busy=true;try{const previous=new Set(data.edges.map(e=>e.id));await postAction('edge',{source:connectionSource,target:node.id,label:data.kind==='SEQUENCE'?'新消息':''});const response=await fetch('/uml/data?viewId='+data.id);if(!response.ok)throw Error('读取失败');data=await response.json();toggleConnect();const created=data.edges.find(e=>!previous.has(e.id));if(created)selectEdge(created,true);else render();}catch(error){showError(error.message);}finally{busy=false;}}
function el(tag,attrs={},parent=svg,text){const n=document.createElementNS(ns,tag);for(const [k,v]of Object.entries(attrs))n.setAttribute(k,v);if(text!==undefined)n.textContent=text;parent.appendChild(n);return n;}
function classLines(node){return (node.details||'').split('\n').filter(line=>line.trim());}
function nodeHeight(node){return node.type==='CLASS'?Math.max(140,64+classLines(node).length*17):140;}
function edgePanel(show){document.getElementById('uml-edge-editor').hidden=!show;document.getElementById('uml-node-selection').hidden=show||(data.kind==='CLASS'&&editable);const panel=document.getElementById('uml-node-panel');if(panel)panel.hidden=show;const actions=document.getElementById('uml-node-actions');if(actions)actions.hidden=show;setWorkspace(show?'edges':'nodes');}
function setWorkspace(mode){const panel=document.querySelector('.uml-panel');panel.dataset.mode=mode;panel.querySelectorAll('[data-workspace]').forEach(b=>{b.setAttribute('aria-pressed',String(b.dataset.workspace===mode));});if(mode==='nodes'&&connecting)toggleConnect();}
function selectEdge(edge,automatic=false){if(connecting||(busy&&!automatic))return;selected=null;selectedEdge=edge.id;edgePanel(true);const from=data.nodes.find(n=>n.id===edge.source),to=data.nodes.find(n=>n.id===edge.target);document.getElementById('uml-edge-selection').textContent=(from?.label||'起点')+' → '+(to?.label||'终点')+(edgeForm?'':'：'+(edge.label||'未命名'));
if(edgeForm){edgeForm.elements.id.value=edge.id;edgeForm.elements.label.value=edge.label||'';}render();}
function select(node){selectedEdge=null;edgePanel(false);selected=node.id;document.getElementById('uml-selection').textContent=node.type==='CLASS'&&editable?'':node.label+(node.details?'\n'+node.details:'');const links=document.getElementById('uml-links');links.replaceChildren();
if(node.storyId||node.taskId)document.getElementById('uml-node-selection').hidden=false;
function link(text,url){const a=document.createElement('a');a.textContent=text;a.href=url;links.appendChild(a);}
if(node.storyId)link('查看关联用户故事 ↗','/backlog?projectId='+data.projectId+'#item-'+node.storyId);
if(node.taskId){link('查看关联任务 ↗',editable?'/manager#task-'+node.taskId:'/my-tasks');link('查看项目甘特图 ↗','/sprints?projectId='+data.projectId);}
if(form){form.elements.id.value=node.id;form.elements.type.value=node.type;form.elements.label.value=node.label;form.elements.details.value=node.details||'';form.elements.storyId.value=node.storyId||'';form.elements.taskId.value=node.taskId||'';document.getElementById('node-form-title').textContent=node.type==='CLASS'?'编辑类：'+node.label:'编辑元素 #'+node.id;}
clearError();
render();}
function point(event){const p=svg.createSVGPoint();p.x=event.clientX;p.y=event.clientY;return p.matrixTransform(svg.getScreenCTM().inverse());}
function render(){svg.replaceChildren();const defs=el('defs');const marker=el('marker',{id:'arrow',viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:7,markerHeight:7,orient:'auto-start-reverse'},defs);el('path',{d:'M0 0L10 5L0 10Z',fill:'#7b97aa'},marker);
const sequence=data.kind==='SEQUENCE';const height=Math.max(1200,...data.nodes.map(n=>n.y+nodeHeight(n)+80),data.edges.length*65+250);const width=Math.max(1800,...data.nodes.map(n=>n.x+260));svg.setAttribute('viewBox','0 0 '+width+' '+height);svg.style.width=width+'px';svg.style.height=height+'px';
if(sequence)data.nodes.forEach(n=>el('line',{x1:n.x+90,y1:100,x2:n.x+90,y2:height-60,stroke:'#aebec8','stroke-dasharray':'6 5'}));
data.edges.forEach((edge,index)=>{const a=data.nodes.find(n=>n.id===edge.source),b=data.nodes.find(n=>n.id===edge.target);if(!a||!b)return;const y=sequence?150+index*65:0;
let x1=a.x+90,y1=sequence?y:a.y+65,x2=b.x+90,y2=sequence?y:b.y+65;
if(!sequence&&a.id!==b.id){const dx=x2-x1,dy=y2-y1;const scale=Math.max(Math.abs(dx)/92,Math.abs(dy)/65);if(scale>2){const ox=dx/scale,oy=dy/scale;x1+=ox;y1+=oy;x2-=ox;y2-=oy;}}
let path;if(a.id===b.id){path='M'+x1+' '+y1+'h55v35h-55';}else path='M'+x1+' '+y1+'L'+x2+' '+y2;
const group=el('g',{class:'uml-edge'+(selectedEdge===edge.id?' selected':''),tabindex:0,role:'button','aria-label':(sequence?'消息：':'连线：')+(edge.label||'未命名')});
el('path',{d:path,fill:'none',stroke:'transparent','stroke-width':16,'pointer-events':'stroke'},group);
el('path',{d:path,fill:'none',class:'edge-line','stroke-width':1.6,'marker-end':'url(#arrow)'},group);
el('text',{x:a.id===b.id?x1+60:(x1+x2)/2,y:(y1+y2)/2-8,'text-anchor':a.id===b.id?'start':'middle',class:'edge-label'},group,(sequence?(index+1)+'. ':'')+edge.label);
el('title',{},group,edge.label||'点击修改连线名称');group.addEventListener('click',()=>selectEdge(edge));group.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();selectEdge(edge);}});
});
data.nodes.forEach(n=>{const y=sequence?35:n.y;const g=el('g',{class:'uml-node'+(selected===n.id?' selected':''),transform:'translate('+n.x+','+y+')',tabindex:0,role:'button','aria-label':n.label});let pressedField='label';
if(n.type==='ACTOR'){el('circle',{cx:90,cy:20,r:13,class:'node-shape'},g);el('path',{d:'M90 33V75M60 48H120M90 75L65 100M90 75L115 100',stroke:'#7c9caf','stroke-width':2,fill:'none'},g);}
else if(n.type==='USECASE')el('ellipse',{cx:90,cy:55,rx:88,ry:42,class:'node-shape'},g);
else if(n.type==='DECISION')el('polygon',{points:'90,0 180,55 90,110 0,55',class:'node-shape'},g);
else if(n.type==='START'||n.type==='END'){el('circle',{cx:90,cy:40,r:18,class:'node-shape'},g);if(n.type==='END')el('circle',{cx:90,cy:40,r:11,fill:'#718da0'},g);}
else el('rect',{width:180,height:n.type==='CLASS'?nodeHeight(n):sequence?55:100,rx:n.type==='ACTIVITY'?16:5,class:'node-shape'},g);
const titleY=n.type==='ACTOR'?120:n.type==='CLASS'||sequence?26:n.type==='START'||n.type==='END'?85:55;
el('text',{x:90,y:titleY,'text-anchor':'middle',class:'node-title'},g,n.label.length>20?n.label.slice(0,19)+'…':n.label);
if(n.type==='CLASS'){el('line',{x1:0,y1:40,x2:180,y2:40,stroke:'#b4c8d5'},g);classLines(n).forEach((line,i)=>{const member=el('text',{x:10,y:61+i*17,class:'class-member'},g,line.length>23?line.slice(0,22)+'…':line);el('title',{},member,line);});}
if(n.storyId||n.taskId)el('text',{x:90,y:n.type==='CLASS'?nodeHeight(n)+20:n.type==='ACTOR'?140:sequence?75:130,'text-anchor':'middle',class:'node-linked'},g,'↗ 已关联'+(n.storyId?'故事 ':'')+(n.taskId?'任务':''));
g.addEventListener('click',event=>{const field=n.type==='CLASS'&&(pressedField==='details'||event.target.classList.contains('class-member'))?'details':'label';const directClassEdit=n.type==='CLASS'&&!connecting;choose(n);if(directClassEdit&&form){form.elements[field].focus();if(field==='label')form.elements.label.select();}});g.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(n);}});
if(editable)g.addEventListener('pointerdown',e=>{if(e.button!==0||connecting)return;pressedField=e.target.classList.contains('class-member')?'details':'label';const initial=point(e),ox=n.x,oy=n.y;let moved=false;g.setPointerCapture(e.pointerId);
const move=ev=>{const p=point(ev);if(Math.abs(p.x-initial.x)+Math.abs(p.y-initial.y)>4)moved=true;n.x=Math.max(0,Math.min(1600,Math.round(ox+p.x-initial.x)));n.y=sequence?oy:Math.max(0,Math.min(1600,Math.round(oy+p.y-initial.y)));g.setAttribute('transform','translate('+n.x+','+(sequence?35:n.y)+')');};
const end=async()=>{g.removeEventListener('pointermove',move);g.removeEventListener('pointerup',end);g.removeEventListener('pointercancel',cancel);if(!moved)return;
clearError();try{const body=new URLSearchParams({viewId:data.id,id:n.id,x:n.x,y:n.y,csrfToken:root.dataset.csrf});const response=await fetch('/uml/move',{method:'POST',body});if(!response.ok)throw Error();const result=await response.json();if(result.status!=='saved')throw Error();}catch{n.x=ox;n.y=oy;showError('保存失败，位置已恢复');}render();};
const cancel=()=>{n.x=ox;n.y=oy;g.removeEventListener('pointermove',move);g.removeEventListener('pointerup',end);g.removeEventListener('pointercancel',cancel);render();};
g.addEventListener('pointermove',move);g.addEventListener('pointerup',end);g.addEventListener('pointercancel',cancel);
});
});document.getElementById('uml-empty-canvas').hidden=data.nodes.length>0;
}
const clear=document.getElementById('uml-clear');if(clear)clear.addEventListener('click',()=>{form.reset();form.elements.id.value='';selected=null;selectedEdge=null;edgePanel(false);document.getElementById('node-form-title').textContent=data.kind==='CLASS'?'新增类':'新增元素';document.getElementById('uml-selection').textContent='选择一个节点查看关联。';document.getElementById('uml-links').replaceChildren();render();});
const edgeCancel=document.getElementById('uml-edge-cancel');if(edgeCancel)edgeCancel.addEventListener('click',()=>{selectedEdge=null;edgePanel(false);render();});
const connectButton=document.getElementById('uml-connect');if(connectButton)connectButton.addEventListener('click',toggleConnect);
if(form){
 const panel=document.querySelector('.uml-panel');panel.classList.add('uml-inspector');panel.dataset.mode='nodes';
 const tools=panel.querySelector('.uml-tools'),actions=document.getElementById('uml-node-actions');
 const edgeSettings=Array.from(panel.children).find(n=>n.tagName==='DETAILS');edgeSettings.classList.add('uml-edge-settings');edgeSettings.open=true;
 const tabs=document.createElement('div');tabs.className='uml-inspector-tabs';tabs.setAttribute('aria-label','编辑工作区');
 for(const [mode,title] of [['nodes','元素'],['edges','连线']]){const button=document.createElement('button');button.type='button';button.textContent=title;button.dataset.workspace=mode;button.setAttribute('aria-pressed',String(mode==='nodes'));button.addEventListener('click',()=>{if(mode==='nodes'){selectedEdge=null;edgePanel(false);if(data)render();}else setWorkspace(mode);});tabs.appendChild(button);}
 const body=document.createElement('div');body.className='uml-inspector-body';while(panel.firstChild)body.appendChild(panel.firstChild);
 panel.append(tabs,body,actions);body.prepend(tools);
 form.querySelectorAll('.uml-advanced').forEach(d=>d.open=false);
 document.getElementById('uml-clear').addEventListener('click',()=>{form.querySelectorAll('.uml-advanced').forEach(d=>d.open=false);body.scrollTop=0;form.elements.label.focus();});
}
fetch('/uml/data?viewId='+encodeURIComponent(root.dataset.viewId)).then(r=>{if(!r.ok)throw Error();return r.json();}).then(d=>{data=d;render();if(form){const tools=document.getElementById('uml-quick-nodes');for(const option of form.elements.type.options){const button=document.createElement('button');button.type='button';button.className='notion-button';button.textContent='＋ '+option.textContent;button.addEventListener('click',async()=>{if(busy)return;busy=true;button.disabled=true;clearError();try{await postAction('node',{type:option.value,label:'新'+option.textContent,details:''});location.reload();}catch(error){showError(error.message);busy=false;button.disabled=false;}});tools.appendChild(button);}}}).catch(()=>{showError('加载失败，请刷新页面重试');});
})();
