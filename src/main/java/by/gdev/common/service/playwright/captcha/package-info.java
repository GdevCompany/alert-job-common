/**
 * Прохождение Yandex SmartCaptcha в сессии Playwright.
 *
 * <h2>Два вида задания на YouDo</h2>
 * Чекбокс «Я не робот» живёт в {@code checkbox-iframe} / {@code backend.captcha}.
 * Его закрывает локальный клик или внешний smart-token ({@code YandexSmartCaptchaTask}).
 * Калейдоскоп ({@code AdvancedCaptcha_kaleidoscope}, canvas, ползунок {@code #captcha-slider})
 * этим токеном не решается: нужен тот же браузер, в котором открыт пазл.
 * Вход — {@link by.gdev.common.service.playwright.captcha.CaptchaService#solveYouDoBrowserSessionKaleidoscope}.
 *
 * <h2>Один кадр калейдоскопа</h2>
 * <ol>
 *   <li>Страница шлёт {@code POST https://smartcaptcha.cloud.yandex.ru/check}.
 *       В теле — массив {@code captcha.task} (десятки целых) и ссылка на картинку
 *       {@code img.smartcaptcha.yandexcloud.net/image?key=…}. Слушатель вешается
 *       в начале логина: ответ часто приходит раньше, чем солвер смотрит на экран.</li>
 *   <li>Картинка приводится к PNG 320×180. Сначала берётся оригинал с CDN,
 *       снимок canvas — запасной вариант ({@code captcha.yandex.pazl-original-image}).</li>
 *   <li>{@link by.gdev.common.service.playwright.captcha.TwoCaptchaClient} создаёт
 *       {@code PazlCaptchaTask}. Ответ — число кликов по стрелке «вправо», не координата на картинке.</li>
 *   <li>Клики делает Playwright по кнопке стрелки. Событие из {@code element.click()} в JS
 *       капча отбрасывает как недоверенное. На время серии кликов слушатель ответов снимается:
 *       {@code response.text()} в {@code onResponse} на том же соединении дедлочит {@code locator.click}.</li>
 *   <li>{@code button[data-testid=submit]} — «Отправить». Тело нового {@code /check}
 *       читается уже на потоке солвера, из очереди, а не внутри слушателя.</li>
 * </ol>
 *
 * <h2>Почему кадров бывает несколько</h2>
 * Верный ответ не обязан закрывать виджет. После «Отправить» Яндекс либо отдаёт
 * {@code {"status":"ok","spravka":"…"}} и пазл исчезает, либо сразу открывает следующий кадр
 * с подписью «Дополнительная проверка»: новый {@code task}, новая картинка, ползунок снова в 0.
 * Это новый принятый пазл, а не повтор неверного. Предыдущую задачу 2Captcha помечаем
 * {@code reportCorrect} и берём task/картинку только с индексов после отправки, чтобы не решить
 * заново первый кадр. Цепочка ограничена шестью раундами. Обрыв на середине оставляет виджет,
 * если Яндекс ещё ждёт следующий кадр.
 * Сброс того же пазла (подпись повтора и ползунок в нуле без нового {@code /check}) —
 * {@code reportIncorrect}. Откат ползунка назад делается только если кадр не сменился.
 *
 * <h2>Уже лежащий в DOM токен</h2>
 * Длинный {@code smart-token} или {@code getResponse()} не отменяет видимый пазл, advanced-iframe
 * или чекбокс. Дополнительная проверка как раз появляется после того, как первый кадр уже засчитан.
 * Скрытая оболочка виджета после принятого токена экран не блокирует.
 *
 * <p>Аудио ({@link by.gdev.common.service.playwright.captcha.YandexSmartCaptchaAudioSolver})
 * включается, когда Pazl вернул {@code solution=0}, и когда пазла на экране уже нет.
 * Пока калейдоскоп виден и Pazl просто не решён, тип задания на аудио не переключается.
 */
package by.gdev.common.service.playwright.captcha;
