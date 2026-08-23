package app.torrentmanager.report;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"daily-report.enabled", "email-notifications.enabled"},
        havingValue = "true")
public class ReportPageController {
    @GetMapping(value = "/reports", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Torrent Manager — отчёты</title>
                  <style>
                    :root { color-scheme: dark; font-family: system-ui, sans-serif; }
                    body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                           background: #111827; color: #e5e7eb; }
                    main { width: min(560px, calc(100% - 32px)); background: #1f2937;
                           border: 1px solid #374151; border-radius: 16px; padding: 24px;
                           box-shadow: 0 18px 45px #0006; }
                    h1 { margin-top: 0; font-size: 1.35rem; }
                    dl { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; }
                    dt { color: #9ca3af; } dd { margin: 0; text-align: right; }
                    button { width: 100%; margin-top: 20px; border: 0; border-radius: 10px;
                             padding: 12px; background: #2563eb; color: white; font-weight: 650;
                             cursor: pointer; }
                    button:disabled { opacity: .55; cursor: wait; }
                    #message { min-height: 1.5em; margin-top: 14px; color: #93c5fd; }
                  </style>
                </head>
                <body><main>
                  <h1>Отчёт Torrent Manager</h1>
                  <dl>
                    <dt>Плановый отчёт</dt><dd id="daily">—</dd>
                    <dt>Отчёт по запросу</dt><dd id="manual">—</dd>
                    <dt>Последний статус</dt><dd id="status">—</dd>
                  </dl>
                  <button id="send">Сформировать и отправить отчёт</button>
                  <div id="message"></div>
                </main><script>
                  const message = document.querySelector('#message');
                  const button = document.querySelector('#send');
                  async function refresh() {
                    const response = await fetch('/api/reports/status');
                    const data = await response.json();
                    document.querySelector('#daily').textContent = data.lastDailyReport;
                    document.querySelector('#manual').textContent = data.lastManualReport;
                    document.querySelector('#status').textContent = data.lastDeliveryStatus;
                  }
                  button.addEventListener('click', async () => {
                    let token = localStorage.getItem('torrentReportToken');
                    if (!token) {
                      token = prompt('Введите токен ручного отчёта');
                      if (!token) return;
                      localStorage.setItem('torrentReportToken', token);
                    }
                    button.disabled = true;
                    message.textContent = 'Формируем и отправляем отчёт…';
                    try {
                      const response = await fetch('/api/reports/send', {
                        method: 'POST', headers: {Authorization: `Bearer ${token}`}
                      });
                      if (response.status === 401) {
                        localStorage.removeItem('torrentReportToken');
                        throw new Error('Неверный токен. Нажмите кнопку и введите его снова.');
                      }
                      if (!response.ok) throw new Error(`Ошибка отправки: HTTP ${response.status}`);
                      message.textContent = 'Отчёт успешно отправлен.';
                      await refresh();
                    } catch (error) { message.textContent = error.message; }
                    finally { button.disabled = false; }
                  });
                  refresh().catch(() => message.textContent = 'Не удалось получить статус отчётов.');
                </script></body></html>
                """;
    }
}
