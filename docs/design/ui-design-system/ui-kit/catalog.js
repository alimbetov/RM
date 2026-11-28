(()=>{
'use strict';
const names='add edit remove save search filter refresh close check warning error info menu back forward chevron-down home orders customers warehouse inventory delivery finance fx-shield intelligence radar report calendar clock bell settings lock user workspace download upload export eye link quality missing trend-up trend-down chevron-left chevron-right chevron-up first-page last-page more panel-left list grid sort reset help logout'.split(' ');
const ns='http://www.w3.org/2000/svg';
for(const name of names){const tile=document.createElement('div');tile.className='icon-tile';const svg=document.createElementNS(ns,'svg');svg.setAttribute('class','rm-icon');svg.setAttribute('aria-hidden','true');const use=document.createElementNS(ns,'use');use.setAttribute('href','icons.svg#rm-'+name);svg.append(use);const label=document.createElement('span');label.textContent=name;tile.append(svg,label);document.getElementById('icon-grid').append(tile);}
const {t}=RM_I18N;let message=null;function refresh(){if(message)document.getElementById('demo-message').textContent=t(message[0],...message.slice(1).map(key=>t(key)));}
function show(key,...args){message=[key,...args];refresh();}
document.addEventListener('rm-locale-change',refresh);
const opener=document.getElementById('open-dialog'),dialog=document.getElementById('demo-dialog');
opener.addEventListener('click',()=>dialog.showModal());
dialog.addEventListener('close',()=>{opener.focus();show('Диалог закрыт: демонстрация без изменения данных.');});
for(const button of document.querySelectorAll('[data-demo]')){const key=button.getAttribute('aria-label')||button.textContent.trim();button.addEventListener('click',()=>show('Демонстрация: {0}',key));}
})();
