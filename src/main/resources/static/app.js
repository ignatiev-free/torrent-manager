'use strict';
const $ = id => document.getElementById(id);
let settings = null, queueState = null, settingsDirty = false, queueDirty = false;
let overview = null, poll = null, refreshing = false, toastTimer;
let reportSending = false;
const headings = {
  overview: ['ДОМАШНЯЯ МЕДИАТЕКА', 'Всё под контролем', 'Состояние загрузок и быстрые действия.', 'Обзор'],
  queue: ['РЕСУРСЫ И ПРИОРИТЕТЫ', 'Очередь и скорость', 'Настройте, сколько торрентов работает и какую полосу они занимают.', 'Очередь и скорость'],
  settings: ['ПОВЕДЕНИЕ ПРИЛОЖЕНИЯ', 'Настройки менеджера', 'Ротация, уведомления и отчёты — без перезапуска.', 'Настройки'],
  connections: ['ВАША ИНФРАСТРУКТУРА', 'Подключения и диски', 'qBittorrent, почта и хранилище медиатеки.', 'Подключения и диски']
};
function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function notify(message, error = false) {
  clearTimeout(toastTimer); $('toast').textContent = message;
  $('toast').className = error ? 'error-toast' : ''; $('toast').hidden = false;
  toastTimer = setTimeout(() => $('toast').hidden = true, error ? 10000 : 5000);
}
async function api(path, options = {}) {
  const response = await fetch('/api/admin' + path, {
    ...options, cache: 'no-store', headers: {'X-Torrent-Manager': '1', 'Content-Type': 'application/json', ...options.headers}
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message || body.detail || 'Не удалось выполнить запрос. Проверьте подключение.');
  }
  return body;
}
async function action(button, callback) {
  button.disabled = true;
  try { await callback(); } catch (error) { notify(error.message, true); }
  finally { button.disabled = false; }
}
try { if (localStorage.getItem('tm-theme')) document.documentElement.dataset.theme = localStorage.getItem('tm-theme'); } catch (_) { }
$('theme').onclick = () => {
  const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  try { localStorage.setItem('tm-theme', theme); } catch (_) { }
};
function route() {
  const page = location.hash.slice(1) in headings ? location.hash.slice(1) : 'overview';
  const h = headings[page];
  $('page-kicker').textContent = h[0]; $('page-title').textContent = h[1];
  $('page-subtitle').textContent = h[2]; $('breadcrumb').textContent = h[3];
  document.querySelectorAll('.page').forEach(node => node.hidden = node.id !== page + '-page');
  document.querySelectorAll('nav a').forEach(node => node.classList.toggle('active', node.dataset.page === page));
  $('settings-save-bar').hidden = !['settings', 'connections'].includes(page);
  window.scrollTo({top:0, behavior:'instant'});
  if (settings && page === 'queue' && !queueDirty) loadQueue().catch(error => queueError(error.message));
}
window.addEventListener('hashchange', route);
window.addEventListener('beforeunload', event => { if (settingsDirty || queueDirty) { event.preventDefault(); event.returnValue = ''; } });
function bytes(value, speed = false) {
  if (value === undefined || value === null) return '—';
  const units = speed ? ['Б/с', 'КиБ/с', 'МиБ/с', 'ГиБ/с'] : ['Б', 'КиБ', 'МиБ', 'ГиБ', 'ТиБ'];
  let index = 0, number = Math.abs(value);
  while (number >= 1024 && index < units.length - 1) { number /= 1024; index++; }
  return (value < 0 ? '−' : '') + number.toLocaleString('ru-RU', {maximumFractionDigits: index ? 1 : 0}) + ' ' + units[index];
}
function date(value) { return value ? new Date(value).toLocaleString('ru-RU', {day:'2-digit', month:'short', hour:'2-digit', minute:'2-digit'}) : '—'; }
function badge(id, text, kind) { $(id).textContent = text; $(id).className = 'badge ' + kind; }
async function refreshOverview() {
  overview = await api('/overview');
  badge('connection-badge', overview.connected ? '● qBittorrent подключён' : '● Нет подключения', overview.connected ? 'good' : 'warn');
  $('connection-error').hidden = overview.connected;
  $('connection-error').textContent = overview.connectionError || '';
  $('download-speed').textContent = overview.connected ? bytes(overview.downloadSpeed, true) : '—';
  $('upload-speed').textContent = overview.connected ? bytes(overview.uploadSpeed, true) : '—';
  $('download-limit').textContent = !overview.connected ? 'Нет данных' : overview.downloadLimit > 0 ? 'Лимит: ' + bytes(overview.downloadLimit, true) : 'Без ограничения скорости';
  $('upload-limit').textContent = !overview.connected ? 'Нет данных' : overview.uploadLimit > 0 ? 'Лимит: ' + bytes(overview.uploadLimit, true) : 'Без ограничения скорости';
  $('active-count').textContent = overview.active ?? '—'; $('seeding-count').textContent = overview.seeding ?? '—';
  $('queued-count').textContent = 'В очереди: ' + (overview.queued ?? '—');
  badge('manager-badge', !overview.managerEnabled ? 'На паузе' : overview.dryRun ? 'Наблюдение' : 'Работает', overview.managerEnabled ? 'good' : 'warn');
  $('manager-description').textContent = !overview.managerEnabled ? 'Автоматические действия приостановлены. Торренты продолжают работать.' : overview.dryRun ? 'Менеджер наблюдает за очередью и записывает решения, не останавливая и не запуская торренты.' : 'Медленные загрузки уступают место другим. Старые торренты получают повторные попытки.';
  $('manager-toggle').textContent = overview.managerEnabled ? 'Приостановить менеджер' : 'Возобновить менеджер';
  badge('notification-badge', overview.notificationsAllowed ? 'Включены' : 'Тишина', overview.notificationsAllowed ? 'good' : 'warn');
  $('notification-description').textContent = overview.notificationsAllowed ? 'Письма о завершениях, предупреждения и отчёты отправляются согласно настройкам.' : overview.mutedUntil && new Date(overview.mutedUntil) > new Date() ? 'Уведомления заглушены до ' + date(overview.mutedUntil) + '. Пропущенные письма не будут досланы.' : 'Почтовые уведомления выключены. История загрузок продолжает сохраняться.';
  $('timezone').textContent = overview.timezone;
  renderActivity('completions', overview.completions, 'Здесь появятся новые завершённые загрузки.', item => [item.name, 'Загрузка завершена', item.completedAt, '✓']);
  const labels = {STOPPED:'Приостановлен менеджером', RESUMED:'Повторная попытка', FAIR_ROTATION:'Справедливая ротация'};
  renderActivity('actions', overview.actions, 'За последние сутки действий ещё не было.', item => [item.name, labels[item.type] || item.type, item.at, '⇄']);
  const report = overview.report;
  $('report-description').textContent = 'Ежедневный: ' + (report?.lastDailyReport || 'ещё не отправлялся')
    + ' · Ручной: ' + (report?.lastManualReport || 'ещё не отправлялся')
    + (report?.manualUnavailableReason ? '. ' + report.manualUnavailableReason : '');
  updateReportButton();
  $('refreshed').textContent = 'Обновлено в ' + new Date().toLocaleTimeString('ru-RU') + ' · автоматически каждые 15 секунд';
}
function renderActivity(id, items, empty, describe) {
  $(id).replaceChildren();
  if (!items?.length) { $(id).append(element('p', empty, 'empty')); return; }
  items.forEach(item => {
    const [name, description, at, icon] = describe(item), row = element('div', undefined, 'activity-row');
    const content = element('div', undefined, 'activity-content'); content.append(element('strong', name), element('small', description));
    const time = element('time', date(at)); time.dateTime = at;
    row.append(element('span', icon, 'activity-icon'), content, time); $(id).append(row);
  });
}
async function refresh() {
  if (!settings || document.hidden || refreshing) return;
  refreshing = true;
  try {
    await refreshOverview();
    if (location.hash === '#queue' && !queueDirty) await loadQueue();
  } catch (error) { notify(error.message, true); }
  finally { refreshing = false; }
}
function requireSaved() { if (settingsDirty) throw new Error('Сначала сохраните или отмените изменения настроек.'); }
$('manager-toggle').onclick = event => action(event.currentTarget, async () => {
  requireSaved(); settings = await api('/manager/' + (overview?.managerEnabled ? 'pause' : 'resume'), {method:'POST'});
  renderSettings(); await refreshOverview(); notify('Состояние менеджера изменено');
});
document.querySelectorAll('[data-mute]').forEach(button => button.onclick = () => action(button, async () => {
  requireSaved(); settings = await api('/notifications/' + button.dataset.mute, {method:'POST'});
  renderSettings(); await refreshOverview(); notify('Настройки уведомлений применены');
}));
$('send-report').onclick = event => action(event.currentTarget, async () => {
  reportSending = true;
  try {
    const result = await api('/reports/send', {method:'POST'}); notify(result.message);
  } finally {
    reportSending = false;
    await refreshOverview();
    setTimeout(updateReportButton, 0);
  }
});
function updateReportButton() {
  const report = overview?.report;
  if (!report) return;
  const button = $('send-report');
  const remaining = report.nextManualReportAt ? Math.max(0, Math.ceil((Date.parse(report.nextManualReportAt) - Date.now()) / 1000)) : 0;
  button.disabled = reportSending || !overview.notificationsAllowed || remaining > 0;
  button.textContent = reportSending ? 'Отправляем…' : !overview.notificationsAllowed ? 'Уведомления выключены'
    : remaining > 0 ? 'Повтор через ' + Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2,'0')
    : 'Отправить на почту ↗';
  button.title = report.manualUnavailableReason || '';
}
setInterval(updateReportButton, 1000);
const sectionNames = {manager:'Управление загрузками', notifications:'Уведомления', reports:'Отчёты', connections:'qBittorrent', mail:'Почта · SMTP'};
function renderSettings() {
  $('settings-fields').replaceChildren(); $('connection-fields').replaceChildren(); $('storage-fields').replaceChildren();
  Object.entries(sectionNames).forEach(([section, title]) => {
    const card = element('article', undefined, 'card'), heading = element('div', undefined, 'card-heading');
    heading.append(element('h2', title)); card.append(heading);
    const grid = element('div', undefined, 'form-grid');
    settings.fields.filter(f => f.section === section).forEach(field => {
      const control = settingControl(field);
      if (field.type === 'boolean') card.append(control); else grid.append(control);
    });
    card.append(grid);
    if (section === 'connections' || section === 'mail') {
      const test = element('button', section === 'mail' ? 'Проверить SMTP без отправки письма' : 'Проверить подключение', 'secondary');
      test.type = 'button'; test.style.marginTop = '22px'; test.onclick = () => action(test, async () => {
        requireSaved(); const result = await api('/test/' + (section === 'mail' ? 'smtp' : 'qbittorrent'), {method:'POST'}); notify(result.message);
      }); card.append(test);
    }
    $(section === 'connections' || section === 'mail' ? 'connection-fields' : 'settings-fields').append(card);
  });
  $('storage-fields').append(settingControl(settings.fields.find(f => f.key === 'storage-report.enabled')));
  $('disks').replaceChildren(); settings.disks.forEach(addDisk);
  setSettingsDirty(false);
}
function settingControl(field) {
  const label = element('label', undefined, field.type === 'boolean' ? 'switch-row' : '');
  const input = element('input'); input.name = field.key; input.dataset.setting = field.key;
  if (field.type === 'boolean') {
    input.type = 'checkbox'; input.setAttribute('role', 'switch'); input.checked = settings.values[field.key] === 'true';
    const description = element('span'); description.append(element('b', field.label), element('small', field.help)); label.append(description, input);
  } else {
    label.append(element('span', field.label)); input.type = ['password','number','email','url','time'].includes(field.type) ? field.type : 'text';
    input.value = settings.values[field.key] || '';
    if (field.type === 'password') { input.autocomplete = 'new-password'; input.placeholder = settings.configuredSecrets.includes(field.key) ? 'Пароль сохранён · оставьте пустым' : 'Ещё не задан'; }
    if (field.type === 'number') { input.min = field.key.endsWith('fair-rotation-slots') ? '0' : '1'; input.step = '1'; }
    label.append(input, element('small', field.help));
  }
  return label;
}
function addDisk(disk = {name:'', mountPath:'', qbitPaths:[]}) {
  const card = element('div', undefined, 'disk'), heading = element('div', undefined, 'disk-heading');
  const remove = element('button', 'Убрать из отчёта', 'quiet'); remove.type = 'button';
  remove.onclick = () => { card.remove(); setSettingsDirty(true); };
  heading.append(element('strong', 'Диск медиатеки'), remove); card.append(heading);
  const grid = element('div', undefined, 'form-grid');
  [['name','Название',disk.name,'Например: Медиатека · 18 ТБ'], ['mountPath','Путь в контейнере менеджера',disk.mountPath,'Например: /storage/hdd1'], ['qbitPaths','Пути в qBittorrent',disk.qbitPaths.join(', '),'Через запятую: /downloads1, /media1']].forEach(([name,title,value,hint]) => {
    const label = element('label', title, name === 'qbitPaths' ? 'wide' : '');
    const input = element('input'); input.type='text'; input.dataset.diskField=name; input.value=value; input.required=true;
    label.append(input, element('small', hint)); grid.append(label);
  }); card.append(grid); $('disks').append(card);
}
function readDisks() {
  return [...document.querySelectorAll('.disk')].map(card => {
    const values = Object.fromEntries([...card.querySelectorAll('input')].map(i => [i.dataset.diskField,i.value.trim()]));
    return {...values, qbitPaths:values.qbitPaths.split(',').map(p=>p.trim()).filter(Boolean)};
  });
}
function setSettingsDirty(dirty) {
  settingsDirty = dirty; $('settings-dirty').textContent = dirty ? 'Есть несохранённые изменения' : 'Все изменения сохранены';
  $('settings-save-bar').classList.toggle('unsaved', dirty);
}
$('settings-form').addEventListener('input', () => setSettingsDirty(true));
$('add-disk').onclick = () => { addDisk(); setSettingsDirty(true); };
$('discard-settings').onclick = event => action(event.currentTarget, async () => { settings = await api('/settings'); renderSettings(); notify('Загружены действующие настройки'); });
$('settings-form').addEventListener('submit', event => {
  event.preventDefault(); action(event.submitter, async () => {
    const values = {};
    document.querySelectorAll('[data-setting]').forEach(input => {
      const value = input.type === 'checkbox' ? String(input.checked) : input.value;
      if (value !== settings.values[input.name]) values[input.name] = value;
    });
    const disks = readDisks(), update = {revision:settings.revision, values};
    if (JSON.stringify(disks) !== JSON.stringify(settings.disks)) update.disks = disks;
    settings = await api('/settings', {method:'PUT', body:JSON.stringify(update)}); renderSettings();
    notify('Настройки сохранены и применены'); await refreshOverview();
  });
});
$('check-storage').onclick = event => action(event.currentTarget, async () => {
  requireSaved(); const result = await api('/storage'); $('storage-result').replaceChildren();
  if (!result.enabled) { $('storage-result').append(element('p','Сначала включите диски в отчёте и сохраните настройки.','notice subtle')); return; }
  if (result.error) $('storage-result').append(element('p', result.error, 'notice warning'));
  result.disks.forEach(disk => $('storage-result').append(element('p', disk.name + ': ' + (disk.error || ('свободно ' + bytes(disk.freeBytes) + ' из ' + bytes(disk.totalBytes) + ' · осталось скачать ' + bytes(disk.remainingBytes))), 'notice ' + (disk.error ? 'warning' : 'subtle'))));
  if (result.unmatchedTorrents) $('storage-result').append(element('p', 'Не сопоставлены с дисками: ' + result.unmatchedTorrents + ' торрентов.', 'notice warning'));
});
function queueError(message) {
  $('queue-error').textContent = message; $('queue-error').hidden = false;
  if (!queueState) $('save-queue').disabled = true;
}
async function loadQueue() {
  const state = await api('/queue');
  if (queueDirty) return; // A background request must never replace edits made while it was in flight.
  queueState = state; const form = $('queue-form');
  Object.entries(state.preferences).forEach(([key, value]) => {
    const input = form.elements.namedItem(key); if (!input) return;
    if (input.type === 'checkbox') input.checked = value;
    else input.value = key.endsWith('limit') ? value / 1024 : value;
  });
  form.elements.namedItem('alternativeMode').checked = state.alternativeMode;
  $('scheduler-note').textContent = state.preferences.scheduler_enabled ? 'В qBittorrent включено расписание: оно может автоматически переключить режим скорости.' : 'Расписание альтернативных лимитов в qBittorrent выключено.';
  $('queue-error').hidden = true; $('save-queue').disabled = false;
  $('queue-dirty').textContent = 'Значения из qBittorrent · ' + new Date().toLocaleTimeString('ru-RU');
  $('queue-form').querySelector('.save-bar').classList.remove('unsaved');
}
$('queue-form').addEventListener('input', () => {
  queueDirty = true; $('queue-dirty').textContent = 'Есть несохранённые изменения';
  $('queue-form').querySelector('.save-bar').classList.add('unsaved');
});
$('reload-queue').onclick = event => action(event.currentTarget, async () => {
  if (queueDirty && !confirm('Заменить несохранённые значения текущими настройками qBittorrent?')) return;
  queueDirty = false; await loadQueue();
});
$('queue-form').addEventListener('submit', event => {
  event.preventDefault(); action(event.submitter, async () => {
    if (!queueState) throw new Error('Сначала загрузите действующие лимиты.');
    const preferences = {...queueState.preferences};
    [...$('queue-form').elements].filter(i => i.name && i.name !== 'alternativeMode').forEach(input => {
      preferences[input.name] = input.type === 'checkbox' ? input.checked : input.name.endsWith('limit') ? Math.round(Number(input.value) * 1024) : Number(input.value);
    });
    const desired = {preferences, alternativeMode:$('queue-form').elements.namedItem('alternativeMode').checked};
    queueState = await api('/queue', {method:'PUT', body:JSON.stringify({expected:queueState, desired})});
    queueDirty = false; await loadQueue(); notify('Лимиты применены в qBittorrent'); await refreshOverview();
  });
});
route();

async function initialize() {
  try {
    settings = await api('/settings'); renderSettings(); route(); await refreshOverview();
    clearInterval(poll); poll = setInterval(refresh, 15000);
  } catch (error) {
    notify(error.message, true);
    setTimeout(initialize, 10000);
  }
}
initialize();
