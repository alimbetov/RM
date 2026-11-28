'use strict';
const $=id=>document.getElementById(id);
const mq=matchMedia('(min-width:768px)'),toggle=$('nav-toggle'),side=$('side');
let mobileOpen=false;
function syncNav(){const mobile=!mq.matches;const active=document.activeElement;toggle.hidden=!mobile;side.hidden=mobile&&!mobileOpen;toggle.setAttribute('aria-expanded',String(mobile&&mobileOpen));if(side.hidden&&side.contains(active))toggle.focus();if(toggle.hidden&&active===toggle)$('heading').focus();}
function closeNav(){mobileOpen=false;syncNav();}
toggle.addEventListener('click',()=>{mobileOpen=!mobileOpen;syncNav();});
mq.addEventListener('change',()=>{mobileOpen=false;syncNav();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!mq.matches&&mobileOpen){closeNav();toggle.focus();}});
for(const button of side.querySelectorAll('[data-section]'))button.addEventListener('click',()=>{for(const b of side.querySelectorAll('[data-section]'))b.removeAttribute('aria-current');button.setAttribute('aria-current','page');const label=button.querySelector('span').textContent;$('heading').textContent=label;$('crumb').textContent=label;closeNav();$('heading').focus();});
const tabs=[...document.querySelectorAll('[role=tab]')];
function selectTab(tab,focus=false){for(const t of tabs){const active=t===tab;t.setAttribute('aria-selected',String(active));t.tabIndex=active?0:-1;$(t.getAttribute('aria-controls')).hidden=!active;}if(focus)tab.focus();}
for(const t of tabs){t.addEventListener('click',()=>selectTab(t));t.addEventListener('keydown',e=>{const i=tabs.indexOf(t);const n={ArrowRight:(i+1)%tabs.length,ArrowLeft:(i+tabs.length-1)%tabs.length,Home:0,End:tabs.length-1}[e.key];if(n!==undefined){e.preventDefault();selectTab(tabs[n],true);}});}
const records=[['RM-001','Демонстрация A','ready'],['RM-002','Демонстрация B','pending'],['RM-003','Ұзын атауы бар ұйым / Long organization name','ready'],['RM-004','Демонстрация D','pending'],['RM-005','Демонстрация E','ready'],['RM-006','Демонстрация F','pending'],['RM-007','Демонстрация G','ready']];
const size=3;let page=1,query='',state='';
function render(){const data=records.filter(r=>(!state||r[2]===state)&&r.slice(0,2).join(' ').toLocaleLowerCase().includes(query));const pages=Math.ceil(data.length/size);page=Math.max(1,Math.min(page,pages||1));$('rows').replaceChildren();for(const r of data.slice((page-1)*size,page*size)){const tr=document.createElement('tr');for(const value of [r[0],r[1],r[2]==='ready'?'Готово':'Ожидает']){const td=document.createElement('td');td.textContent=value;tr.append(td);}$('rows').append(tr);}if(!data.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=3;td.textContent='Нет записей';tr.append(td);$('rows').append(tr);}$('result-count').textContent='Найдено: '+data.length;$('page-label').textContent=pages?'Страница '+page+' из '+pages:'Нет страниц';$('prev').disabled=page<=1;$('next').disabled=page>=pages;$('total').textContent=String(data.length);}
function resetFilters(){query='';state='';page=1;render();}
$('filters').addEventListener('submit',e=>{e.preventDefault();query=$('query').value.trim().toLocaleLowerCase();state=$('state').value;page=1;render();});
$('filters').addEventListener('reset',resetFilters);
$('prev').addEventListener('click',()=>{page--;render();});$('next').addEventListener('click',()=>{page++;render();});
$('workspace').addEventListener('change',()=>{$('filters').reset();selectTab(tabs[0]);$('shell-status').textContent='Выбран '+$('workspace').selectedOptions[0].textContent+'; только визуальная демонстрация.';});
$('notifications').addEventListener('click',()=>{$('shell-status').textContent='Демо уведомления: данные не подключены.';});
syncNav();render();
