'use strict';
if (new URLSearchParams(location.search).get('embed') === '1') document.documentElement.classList.add('embedded');
const reportButton = document.getElementById('send-report');
const reportResult = document.getElementById('result');
let reportStatus = null, sendingReport = false, refreshingReport = false;
async function reportApi(path, options = {}) {
  const response = await fetch('/api/admin/reports/' + path, {
    ...options, cache:'no-store', headers:{'X-Torrent-Manager':'1','Content-Type':'application/json'}
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || body.detail || 'Не удалось выполнить запрос. Обновите страницу.');
  return body;
}
function renderReport() {
  if (!reportStatus) return;
  const remaining = reportStatus.nextManualReportAt ? Math.max(0, Math.ceil((Date.parse(reportStatus.nextManualReportAt) - Date.now()) / 1000)) : 0;
  reportButton.disabled = sendingReport || !reportStatus.manualAvailable;
  reportButton.textContent = sendingReport ? 'Отправляем…' : remaining > 0
    ? 'Повтор через ' + Math.floor(remaining / 60) + ':' + String(remaining % 60).padStart(2,'0')
    : reportStatus.manualAvailable ? 'Сформировать и отправить' : 'Отправка недоступна';
  reportButton.title = reportStatus.manualUnavailableReason || '';
  document.getElementById('last-report').textContent = 'Последний ручной: ' + reportStatus.lastManualReport;
  if (!sendingReport && !reportStatus.manualAvailable && remaining === 0) {
    reportResult.textContent = reportStatus.manualUnavailableReason || '';
  }
}
async function refreshReport() {
  if (refreshingReport) return;
  refreshingReport = true;
  try { reportStatus = await reportApi('status'); renderReport(); }
  catch (error) { reportResult.className='error'; reportResult.textContent=error.message; }
  finally { refreshingReport = false; }
}
reportButton.addEventListener('click', async () => {
  if (sendingReport) return;
  sendingReport = true; reportResult.className=''; reportResult.textContent='Формируем отчёт…'; renderReport();
  try {
    const result = await reportApi('send', {method:'POST'});
    reportResult.textContent = result.message;
  } catch (error) { reportResult.className='error'; reportResult.textContent=error.message; }
  finally { sendingReport=false; await refreshReport(); }
});
// Page loads, iframe reloads and polling only read status. POST is tied to the user's click.
refreshReport();
setInterval(refreshReport, 15000);
setInterval(renderReport, 1000);
