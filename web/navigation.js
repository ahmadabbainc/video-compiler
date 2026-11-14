(function(){
'use strict';
const menu=document.getElementById('menuBtn'),drawer=document.getElementById('appDrawer'),backdrop=document.getElementById('navBackdrop'),close=document.getElementById('drawerClose');
function setOpen(open){drawer.classList.toggle('open',open);backdrop.classList.toggle('open',open);menu.setAttribute('aria-expanded',String(open));}
menu.addEventListener('click',()=>setOpen(!drawer.classList.contains('open')));
close.addEventListener('click',()=>setOpen(false));
backdrop.addEventListener('click',()=>setOpen(false));
document.querySelectorAll('.nav-item').forEach(btn=>btn.addEventListener('click',()=>{const id=btn.dataset.view;document.querySelectorAll('.workspace-view').forEach(v=>v.classList.toggle('active',v.id===id));document.querySelectorAll('.nav-item').forEach(b=>b.classList.toggle('active',b===btn));setOpen(false);window.scrollTo({top:0,behavior:'smooth'});}));
document.addEventListener('keydown',e=>{if(e.key==='Escape')setOpen(false);});
})();