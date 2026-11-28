'use strict';
// UI-owned text only; stable business identifiers and user values are never translated.
const RM_I18N=(()=>{
const dictionary=new Map(`К содержимому|Skip to content|Мазмұнға өту
Демо workspace|Demo workspace|Демо жұмыс кеңістігі
Workspace A|Workspace A|Жұмыс кеңістігі A
Workspace B — Ұйым|Workspace B — Organization|Жұмыс кеңістігі B — Ұйым
РАБОЧАЯ ОБЛАСТЬ|WORKSPACE|ЖҰМЫС КЕҢІСТІГІ
Обзор|Overview|Шолу
Обзор бизнеса|Business overview|Бизнеске шолу
Реестр|Registry|Тізілім
Формы|Forms|Нысандар
Детализация|Details|Толық мәлімет
Тема|Theme|Тақырып
Светлая|Light|Ашық
Тёмная|Dark|Қараңғы
Taupe — тёплая|Taupe — warm|Taupe — жылы
Sage — зелёная|Sage — green|Sage — жасыл
Плотность|Density|Тығыздық
Комфортная|Comfortable|Ыңғайлы
Компактная|Compact|Ықшам
Компактность — для точного указателя на широком экране.|Compact density is for fine pointers on wide screens.|Ықшам режим кең экрандағы дәл меңзерге арналған.
Демонстрационные данные|Demo data|Демо деректер
Язык|Language|Тіл
Выбрать все записи страницы|Select all records on this page|Беттегі барлық жазбаны таңдау
Состояние, динамика и действия · демонстрация без API|Status, trends and actions · demo without API|Күй, динамика және әрекеттер · API-сыз демо
ДЕМО|DEMO|ДЕМО
Контроль бизнеса|Business control|Бизнесті бақылау
Реальные проверки не подключены. Состояние рисков и защиты не подтверждено.|Live checks are not connected. Risk and protection status is unverified.|Нақты тексерулер қосылмаған. Тәуекелдер мен қорғаныс күйі расталмаған.
Нет подтверждения|Unverified|Расталмаған
Доступность проверок|Check availability|Тексерулердің қолжетімділігі
Не подключено|Not connected|Қосылмаған
Область: демонстрационный workspace. Время последней проверки и первичные доказательства недоступны.|Scope: demo workspace. Last check time and primary evidence are unavailable.|Ауқымы: демо жұмыс кеңістігі. Соңғы тексеру уақыты мен бастапқы дәлелдер қолжетімсіз.
Количество за P1|Count for P1|P1 кезеңіндегі саны
Синтетическое значение · единиц|Synthetic value · units|Синтетикалық мән · бірлік
Качество истории|History quality|Тарих сапасы
Не квалифицировано|Not qualified|Сапасы расталмаған
RM Intelligence · динамика|RM Intelligence · trend|RM Intelligence · динамика
Демонстрационный ряд · P1–P5|Demo series · P1–P5|Демо қатар · P1–P5
Диаграмма|Chart|Диаграмма
Столбчатая|Bar|Бағандық
Линейная|Line|Сызықтық
Синтетический ряд. Пропуск не заменяется нулём.|Synthetic series. Missing values are not replaced with zero.|Синтетикалық қатар. Жоқ мән нөлмен алмастырылмайды.
Наблюдение по демо-данным|Demo data observation|Демо деректер бойынша байқау
P3: значение отсутствует. Пропуск не означает нулевой результат; причины изменения не установлены.|P3: value is missing. Missing does not mean zero; causes of changes are unknown.|P3: мән жоқ. Жоқ мән нөлді білдірмейді; өзгеріс себептері анықталмаған.
Значения и источник|Values and source|Мәндер мен дереккөз
Источник: синтетический ряд P1–P5. Реальные первичные операции и квалифицированный снимок отсутствуют.|Source: synthetic P1–P5 series. Actual source transactions and a qualified snapshot are unavailable.|Дереккөз: P1–P5 синтетикалық қатары. Нақты бастапқы операциялар мен расталған деректер кесіндісі жоқ.
Период|Period|Кезең
Количество|Quantity|Саны
Записи|Records|Жазбалар
Список|List|Тізім
Сводка|Summary|Жиынтық
Поиск|Search|Іздеу
Статус|Status|Мәртебе
Все|All|Барлығы
Готово|Ready|Дайын
Ожидает|Pending|Күтуде
Применить|Apply|Қолдану
Сбросить|Reset|Қалпына келтіру
Смоделировать ошибку|Simulate error|Қатені модельдеу
Повторить|Retry|Қайталау
Подтвердить выбранные|Confirm selected|Таңдалғандарды растау
Если таблица не помещается, прокрутите её горизонтально.|Scroll horizontally if the table does not fit.|Кесте сыймаса, көлденең жылжытыңыз.
Страница|Page|Бет
Номер|Number|Нөмір
Название|Name|Атауы
Сумма, KZT|Amount, KZT|Сома, KZT
Действие|Action|Әрекет
Записей по фильтру|Filtered records|Сүзілген жазбалар
Параметры операции|Operation parameters|Операция параметрлері
Неотрицательная сумма, до 2 знаков после разделителя.|Nonnegative amount, up to 2 decimal places.|Теріс емес сома, бөлгіштен кейін 2 таңбаға дейін.
Дата начала|Start date|Басталу күні
Дата окончания|End date|Аяқталу күні
День|Day|Күн
Неделя|Week|Апта
Месяц|Month|Ай
Квартал|Quarter|Тоқсан
Год|Year|Жыл
Группа A|Group A|A тобы
Группа B|Group B|B тобы
Активно|Active|Белсенді
Документ (локальный выбор)|Document (local selection)|Құжат (жергілікті таңдау)
Файл не отправляется и не читается.|File is neither uploaded nor read.|Файл жіберілмейді және оқылмайды.
Проверить и сохранить демо|Validate and save demo|Демоны тексеру және сақтау
Карточка записи|Record details|Жазба карточкасы
Выберите запись в таблице.|Select a record in the table.|Кестеден жазбаны таңдаңыз.
К списку|Back to list|Тізімге оралу
01. Foundations|01. Foundations|01. Негіздер
02. Buttons|02. Buttons|02. Батырмалар
03. Inputs & Controls|03. Inputs & Controls|03. Енгізу және басқару
04. Status & Feedback|04. Status & Feedback|04. Мәртебе және кері байланыс
05. Data Table|05. Data Table|05. Деректер кестесі
06. Metric Cards|06. Metric Cards|06. Көрсеткіш карточкалары
07. RM SVG Icons|07. RM SVG Icons|07. RM SVG белгішелері
Primary|Primary|Негізгі
Text|Text|Мәтін
Canvas|Canvas|Жұмыс фоны
Success|Success|Сәтті
Warning|Warning|Ескерту
Danger|Danger|Қауіп
Типографика: 12 / 14 / 16 / 20 / 24 px при базовом размере 16 px. Отступы: 4 / 8 / 12 / 16 / 24 / 32 px.|Typography: 12 / 14 / 16 / 20 / 24 px at a 16 px base. Spacing: 4 / 8 / 12 / 16 / 24 / 32 px.|Типография: негізгі 16 px өлшемінде 12 / 14 / 16 / 20 / 24 px. Аралықтар: 4 / 8 / 12 / 16 / 24 / 32 px.
Сохранить|Save|Сақтау
Отмена|Cancel|Бас тарту
Подробнее|More details|Толығырақ
Удалить|Delete|Жою
Недоступно|Unavailable|Қолжетімсіз
Загрузка…|Loading…|Жүктелуде…
Демонстрационные действия не меняют данные.|Demo actions do not change data.|Демо әрекеттер деректерді өзгертпейді.
Наименование|Name|Атауы
Все статусы|All statuses|Барлық мәртебелер
Подтверждён|Confirmed|Расталды
Завершён|Completed|Аяқталды
Введите допустимую сумму (пример ошибки).|Enter a valid amount (error example).|Жарамды соманы енгізіңіз (қате үлгісі).
Комментарий|Comment|Түсініктеме
Недоступное поле|Disabled field|Қолжетімсіз өріс
Настройки|Settings|Баптаулар
Уведомления|Notifications|Хабарландырулар
Только активные|Active only|Тек белсенділер
Требует внимания|Needs attention|Назар аудару қажет
Ошибка|Error|Қате
Черновик|Draft|Жоба
Операция выполнена.|Operation completed.|Операция аяқталды.
Данные за период неполные.|Period data is incomplete.|Кезең деректері толық емес.
Не удалось загрузить данные. Повторите попытку.|Could not load data. Try again.|Деректер жүктелмеді. Қайталап көріңіз.
Загрузка|Loading|Жүктеу
Пустой результат|Empty result|Бос нәтиже
Нет записей по выбранным фильтрам.|No records match the filters.|Сүзгілерге сәйкес жазбалар жоқ.
Заказ|Order|Тапсырыс
Клиент|Customer|Клиент
Компания A|Company A|A компаниясы
Компания B|Company B|B компаниясы
Ұзын атауы бар компания / Long company name|Long company name|Ұзын атауы бар компания
Заказы · пример|Orders · example|Тапсырыстар · үлгі
+8,2% к предыдущему периоду|+8.2% vs previous period|Алдыңғы кезеңмен салыстырғанда +8,2%
Квалифицированный ноль · пример|Qualified zero · example|Расталған нөл · үлгі
Данные полные|Data complete|Деректер толық
Историческая статистика · пример|Historical statistics · example|Тарихи статистика · үлгі
Данные не квалифицированы|Data not qualified|Деректер сапасы расталмаған
Жалпы айналым / Total turnover · KZT|Total turnover · KZT|Жалпы айналым · KZT
Демонстрационное значение|Demo value|Демо мән
24 × 24 viewBox · currentColor · stroke 1.75 · без сторонних пакетов|24 × 24 viewBox · currentColor · stroke 1.75 · no third-party packages|24 × 24 viewBox · currentColor · stroke 1.75 · бөгде пакеттерсіз
Детализация записи|Record details|Жазба мәліметтері
Закрыть|Close|Жабу
Полная страница|Full page|Толық бет
Подтвердить выбранные?|Confirm selected?|Таңдалғандарды растау керек пе?
Только демонстрация, записи не изменяются.|Demo only; records are not changed.|Тек демо; жазбалар өзгертілмейді.
Подтвердить|Confirm|Растау
Подтверждение действия|Confirm action|Әрекетті растау
Демонстрационный диалог. Реальные данные не изменяются.|Demo dialog. Actual data is not changed.|Демо диалог. Нақты деректер өзгертілмейді.
Меню разделов|Section menu|Бөлімдер мәзірі
Демо уведомления|Demo notifications|Демо хабарландырулар
Разделы UI Kit|UI Kit sections|UI Kit бөлімдері
Настройки представления|Display settings|Көрініс баптаулары
Хлебные крошки|Breadcrumbs|Навигация жолы
Демонстрационная динамика, значения в таблице ниже|Demo trend, values in the table below|Демо динамика, мәндер төмендегі кестеде
Данные диаграммы|Chart data|Диаграмма деректері
Представление реестра|Registry view|Тізілім көрінісі
Таблица записей|Records table|Жазбалар кестесі
Пагинация|Pagination|Беттер бойынша өту
Предыдущая страница|Previous page|Алдыңғы бет
Следующая страница|Next page|Келесі бет
Обновить образец|Refresh sample|Үлгіні жаңарту
Введите название|Enter name|Атауын енгізіңіз
Найти заказ|Find order|Тапсырысты табу
Дополнительная информация|Additional information|Қосымша ақпарат
Демонстрационная таблица заказов|Demo orders table|Демо тапсырыстар кестесі
Пример|Example|Үлгі
Нет данных|No data|Дерек жоқ
Нет записей|No records|Жазба жоқ
Ошибка демонстрационного адаптера|Demo adapter error|Демо адаптер қатесі
Выбрать {0}|Select {0}|{0} таңдау
Открыть|Open|Ашу
Открыть {0}|Open {0}|{0} ашу
Демо {0} / {1}|Demo {0} / {1}|Демо {0} / {1}
Страница {0} из {1}|Page {0} of {1}|{0}/{1} бет
Найдено: {0}|Found: {0}|Табылды: {0}
Подтверждено демо: {0}|Demo confirmed: {0}|Демо расталды: {0}
Окончание раньше начала|End date precedes start date|Аяқталу күні басталу күнінен ерте
{0} · {1} bytes · не отправлен|{0} · {1} bytes · not uploaded|{0} · {1} байт · жіберілмеді
Демо сохранено: {0} KZT; количество {1}|Demo saved: {0} KZT; quantity {1}|Демо сақталды: {0} KZT; саны {1}
Переключён демонстрационный workspace {0}|Switched demo workspace {0}|Демо жұмыс кеңістігі {0} таңдалды
Демо уведомления: данные не подключены.|Demo notifications: data is not connected.|Демо хабарландырулар: деректер қосылмаған.
Диалог закрыт: демонстрация без изменения данных.|Dialog closed: demo without data changes.|Диалог жабылды: деректер өзгертілмеген демо.
Демонстрация: {0}|Demo: {0}|Демо: {0}`.split('\n').map(line=>{const [ru,en,kk]=line.split('|');return [ru,{en,kk}];}));
let locale='ru';
function translate(lang,key,...args){const template=lang==='ru'?key:dictionary.get(key)?.[lang]??key;return template.replace(/\{(\d+)\}/g,(_,i)=>String(args[Number(i)]??''));}
const t=(key,...args)=>translate(locale,key,...args);
function money(raw){const [whole,frac='']=String(raw).replace(',','.').split('.');const tag=locale==='kk'?'kk-KZ':locale==='en'?'en-US':'ru-RU';if(Intl.NumberFormat.supportedLocalesOf([tag]).length===0){const grouped=BigInt(whole).toString().replace(/\B(?=(\d{3})+(?!\d))/g,locale==='en'?',':'\u00a0');return grouped+(locale==='en'?'.':',')+frac.padEnd(2,'0');}const n=new Intl.NumberFormat(tag);const separator=n.formatToParts(1.1).find(p=>p.type==='decimal').value;return n.format(BigInt(whole))+separator+frac.padEnd(2,'0');}
const staticText=[],staticAttrs=[];
const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);while(walker.nextNode()){const node=walker.currentNode,key=node.nodeValue.trim();if(node.parentElement.closest('script,style')||!dictionary.has(key))continue;staticText.push([node,key,node.nodeValue]);}
for(const el of document.querySelectorAll('[aria-label],[placeholder],#disabled-field'))for(const attr of ['aria-label','placeholder','value']){const key=el.getAttribute(attr);if(dictionary.has(key))staticAttrs.push([el,attr,key]);}
const amounts=[...document.querySelectorAll('#catalog .num,#catalog .value')].filter(el=>/^\d[\d ]*,\d{2}$/.test(el.textContent.trim())).map(el=>[el,el.textContent.trim().replaceAll(' ','').replace(',','.')]);
function set(next){if(!['ru','kk','en'].includes(next))throw Error('Unsupported locale');locale=next;document.documentElement.lang=next;for(const [node,key,original] of staticText)if(node.isConnected)node.nodeValue=original.replace(key,t(key));for(const [el,attr,key] of staticAttrs)el.setAttribute(attr,t(key));for(const [el,raw] of amounts)el.textContent=money(raw);}
return {t,translate,money,set,get locale(){return locale;},has:key=>dictionary.has(key)};
})();
