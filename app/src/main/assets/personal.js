/* Plain Sight 360° visual memory: opt-in, on-device matching. */
(()=>{
'use strict';
const KEY='plain-sight-personal-v1';
const CDN='https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.22';
const MODEL='https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite';
let items={},engine=null,loadPromise=null,teaching=false,searchTimer=null;
try{items=JSON.parse(localStorage.getItem(KEY)||'{}')||{}}catch{items={}}
const norm=s=>String(s||'').toLowerCase().replace(/^(please\s+)?(can you\s+)?(find|locate|spot|where is|where are|show me|look for)\s+/,'').replace(/^(my|the|a|an)\s+/,'').replace(/[.!?]/g,'').trim();
const css=document.createElement('style');
css.textContent='#teachOpen{background:#1a364a;color:#9aefff;font-size:25px;min-width:42px;width:42px}'+
'#teachPanel{position:fixed;inset:0;z-index:20;background:linear-gradient(180deg,#07111bfa 0%,#07111bcc 22%,#07111b38 40%,#07111b38 60%,#07111bee 78%,#07111bfa 100%);display:none;flex-direction:column;align-items:center;text-align:center;color:white;padding:30px 15px 20px}'+
'#teachPanel.visible{display:flex}#teachPanel .head{width:100%;display:flex;justify-content:space-between;align-items:center}'+
'#teachPanel .head button{background:#26384c;color:white}#teachName{flex:none;width:min(92%,310px);border:1px solid #567a8b;border-radius:15px;margin-top:18px;text-align:center;background:#172636}'+
'#teachInfo{margin:12px 8px;color:#dceef6;font-size:13px}#teachReticle{flex:1;width:100%;display:flex;justify-content:center;align-items:center;pointer-events:none}'+
'#teachReticle span{width:min(60vw,30vh);aspect-ratio:1;border:3px dashed #82e7ff;border-radius:20px;box-shadow:0 0 0 999px #06162944;display:flex;align-items:center;justify-content:center;color:#97edff;font-size:11px}'+
'#teachProgress{font-size:14px;font-weight:bold;color:#8ce6ff;min-height:28px}#teachCapture{width:min(95%,330px);background:#82eaff}#teachCapture:disabled{opacity:.45}'+
'#savedTitle{font-size:11px;color:#a4bdd0;margin:12px 0 6px}#savedList{display:flex;flex-wrap:wrap;gap:6px;justify-content:center;max-height:92px;overflow:auto}'+
'.savedRow{display:flex;background:#183246;border-radius:22px}.savedRow button{background:none;color:#e4f4ff;height:35px;min-width:auto;padding:0 9px;font-size:12px}'+
'.savedRow button:last-child{color:#ffb4a4;border-left:1px solid #ffffff30;font-size:19px}';
document.head.append(css);
document.getElementById('bottom').insertAdjacentHTML('afterbegin','<button type="button" id="teachOpen" aria-label="Teach an object">+</button>');
document.body.insertAdjacentHTML('beforeend','<section id="teachPanel">'+
'<div class="head"><strong>TEACH PLAIN SIGHT</strong><button type="button" id="teachClose">✕</button></div>'+
'<input id="teachName" placeholder="Name it: car keys, glasses..." maxlength="55">'+
'<div id="teachInfo">Hold the item in the center. Tap LEARN, then turn it slowly while 8 views are captured.</div>'+
'<div id="teachReticle"><span>HOLD ITEM HERE</span></div>'+
'<div id="teachProgress"></div><button type="button" id="teachCapture">LEARN 360° VIEWS</button>'+
'<div id="savedTitle">YOUR SAVED ITEMS</div><div id="savedList"></div></section>');
const panel=document.getElementById('teachPanel'),field=document.getElementById('teachName');
const count=document.getElementById('teachProgress'),teachBtn=document.getElementById('teachCapture');
const saved=document.getElementById('savedList'),patch=document.createElement('canvas'),pctx=patch.getContext('2d');
patch.width=224;patch.height=224;
const pause=ms=>new Promise(done=>setTimeout(done,ms));
function store(){try{localStorage.setItem(KEY,JSON.stringify(items));return true}catch(e){setStatus('Unable to save to phone storage.',true);return false}}
function updateList(){
 saved.replaceChildren();
 for(const [key,item] of Object.entries(items)){
  if(!Array.isArray(item?.views))continue;
  const row=document.createElement('div');row.className='savedRow';
  const pick=document.createElement('button');pick.textContent=item.label+' ('+item.views.length+')';
  pick.onclick=()=>{query.value='Find '+item.label;hide();begin()};
  const del=document.createElement('button');del.textContent='×';del.setAttribute('aria-label','Delete '+item.label);
  del.onclick=()=>{delete items[key];store();updateList();setStatus('Deleted '+item.label)};
  row.append(pick,del);saved.append(row);
 }
 if(!saved.children.length){const m=document.createElement('small');m.textContent='No saved items yet';saved.append(m)}
}
function open(){
 active=false;run++;busy=false;clearHit();panel.classList.add('visible');
 field.value='';count.textContent='';updateList();setStatus('Teach a personal item');
}
function hide(){if(teaching)return;panel.classList.remove('visible');setStatus('Ready')}
document.getElementById('teachOpen').onclick=open;
document.getElementById('teachClose').onclick=hide;
async function ensure(){
 if(engine)return engine;if(loadPromise)return loadPromise;
 loadPromise=(async()=>{
  setStatus('Loading lightweight personal-item AI model; internet required once…');
  const media=await import(CDN+'/vision_bundle.mjs');
  const fs=await media.FilesetResolver.forVisionTasks(CDN+'/wasm');
  engine=await media.ImageEmbedder.createFromOptions(fs,{baseOptions:{modelAssetPath:MODEL,delegate:'CPU'},runningMode:'IMAGE',l2Normalize:true});
  return engine;
 })();
 try{return await loadPromise}catch(e){loadPromise=null;throw e}
}
function vector(){
 const e=engine.embed(patch).embeddings[0];
 if(!e?.floatEmbedding?.length)throw Error('Model returned no image embedding');
 const values=e.floatEmbedding;let size=0;
 for(const n of values)size+=n*n;size=Math.sqrt(size)||1;
 return Array.from(values,n=>Math.round(n/size*10000)/10000);
}
function similar(a,b){
 if(!a||!b||a.length!==b.length)return -1;
 let dot=0,sa=0,sb=0;for(let i=0;i<a.length;i++){dot+=a[i]*b[i];sa+=a[i]*a[i];sb+=b[i]*b[i]}
 return dot/Math.sqrt((sa||1)*(sb||1));
}
teachBtn.onclick=async()=>{
 if(teaching)return;
 const label=field.value.trim(),key=norm(label);
 if(!key){field.focus();setStatus('Name the item first.',true);return}
 if(!cameraReady){setStatus('Camera not ready.',true);return}
 teaching=true;teachBtn.disabled=true;let views=[];
 try{
  await ensure();count.textContent='Start rotating the item slowly';await pause(550);
  for(let i=0;i<8;i++){
   if(!camera.videoWidth)throw Error('Camera unavailable');
   const side=Math.round(Math.min(camera.videoWidth,camera.videoHeight)*.58);
   pctx.drawImage(camera,(camera.videoWidth-side)/2,(camera.videoHeight-side)/2,side,side,0,0,224,224);
   views.push(vector());count.textContent=(i+1)+' / 8 angles saved';await pause(680);
  }
  items[key]={label,views,date:Date.now(),model:'mobilenet-v3-small'};
  if(store()){setStatus('Learned '+label+' from 8 views');count.textContent='SAVED — ready to find!';updateList();query.value='Find '+label}
 }catch(e){setStatus('Learning failed: '+String(e.message||e).slice(0,140),true);count.textContent='Could not save views'}
 finally{teaching=false;teachBtn.disabled=false}
};
function tiles(w,h){
 const choices=[];const small=Math.min(w,h);
 for(const [frac,cols,rows] of [[.25,4,6],[.43,3,5]]){
  const s=Math.round(small*frac);
  for(let y=0;y<rows;y++)for(let x=0;x<cols;x++)
   choices.push({x:Math.round((w-s)*x/(cols-1)),y:Math.round((h-s)*y/(rows-1)),s});
 }
 return choices;
}
async function findSaved(id,item){
 if(busy||!active||!cameraReady)return;
 busy=true;
 try{
  await ensure();if(id!==run)return;
  const ratio=Math.min(1,860/Math.max(camera.videoWidth,camera.videoHeight));
  canvas.width=Math.round(camera.videoWidth*ratio);canvas.height=Math.round(camera.videoHeight*ratio);
  ctx.drawImage(camera,0,0,canvas.width,canvas.height);
  photo.src=canvas.toDataURL('image/jpeg',.85);photo.style.display='block';
  setStatus('Searching your saved '+item.label+'…');
  let best=null;
  for(const t of tiles(canvas.width,canvas.height)){
   if(id!==run)return;
   pctx.drawImage(canvas,t.x,t.y,t.s,t.s,0,0,224,224);
   const v=vector();let score=-1;
   for(const ref of item.views)score=Math.max(score,similar(v,ref));
   if(!best||score>best.score)best={t,score};
  }
  if(id!==run)return;
  // Image similarity is not a calibrated probability: label results as candidates.
  if(best&&best.score>.73){
   active=false;
   const t=best.t;
   displayBox({box:{xmin:t.x,ymin:t.y,xmax:t.x+t.s,ymax:t.y+t.s},score:0},canvas.width,canvas.height);
   found.textContent='POSSIBLE MATCH · '+item.label;
   tip.textContent='Inspect highlighted area · tap AGAIN to search';
   setStatus('Visual match from your saved angles; verify the object.');
  }else{
   photo.style.display='none';
   setStatus('No close match. Try moving closer or changing the angle.');
   tip.textContent='Still searching '+item.label;
  }
 }catch(e){active=false;photo.style.display='none';setStatus('Saved item search error: '+String(e.message||e).slice(0,150),true)}
 finally{busy=false;if(active&&id===run)searchTimer=setTimeout(()=>findSaved(id,item),900)}
}
const genericBegin=begin;
// The original FIND listener captured the old function; replace it explicitly.
document.getElementById('find').removeEventListener('click',genericBegin);
document.getElementById('find').addEventListener('click',()=>begin());
begin=function(){
 if(teaching)return;
 if(panel.classList.contains('visible'))hide();
 const item=items[norm(query.value)];
 if(!item?.views?.length)return genericBegin();
 if(!cameraReady){setStatus('Camera not ready.',true);return}
 current=item.label;run++;active=true;clearHit();query.blur();
 tip.textContent='Searching your saved '+item.label;
 findSaved(run,item);
};
const oldReset=window.resetSearch;
window.resetSearch=()=>{clearTimeout(searchTimer);oldReset();if(!teaching)panel.classList.remove('visible')};
updateList();
})();