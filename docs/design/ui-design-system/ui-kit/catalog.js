(()=>{
'use strict';
const names='add edit remove save search filter refresh close check warning error info menu back forward chevron-down home orders customers warehouse inventory delivery finance fx-shield intelligence radar report calendar clock bell settings lock user workspace download upload export eye link quality missing trend-up trend-down chevron-left chevron-right chevron-up first-page last-page more panel-left list grid sort reset help logout'.split(' ');
const ns='http://www.w3.org/2000/svg';
for(const name of names){const tile=document.createElement('div');tile.className='icon-tile';const svg=document.createElementNS(ns,'svg');svg.setAttribute('class','rm-icon');svg.setAttribute('aria-hidden','true');const use=document.createElementNS(ns,'use');use.setAttribute('href','icons.svg#rm-'+name);svg.append(use);const label=document.createElement('span');label.textContent=name;tile.append(svg,label);document.getElementById('icon-grid').append(tile);}
const opener=document.getElementById('open-dialog'),dialog=document.getElementById('demo-dialog');
opener.addEventListener('click',()=>dialog.showModal());
dialog.addEventListener('close',()=>{opener.focus();document.getElementById('demo-message').textContent='Диалог закрыт: демонстрация без изменения данных.';});
for(const button of document.querySelectorAll('[data-demo]'))button.addEventListener('click',()=>{document.getElementById('demo-message').textContent='Демонстрация: '+(button.getAttribute('aria-label')||button.textContent.trim());});
})();
