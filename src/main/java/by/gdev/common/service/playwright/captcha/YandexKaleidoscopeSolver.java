package by.gdev.common.service.playwright.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Слайдер-пазл Yandex SmartCaptcha (калейдоскоп) в текущей сессии Playwright.
 *
 * <p>Виджет: {@code .AdvancedCaptcha_kaleidoscope}, canvas
 * {@code .AdvancedCaptcha-KaleidoscopeCanvas}, ползунок {@code #captcha-slider}
 * ({@code aria-valuemax} обычно 28), стрелка «Сдвинуть ползунок вправо»,
 * кнопка {@code button[data-testid=submit]}. Чекбокс «Я не робот» сюда не входит:
 * крошечный canvas 1×1 в advanced-frame пазлом не считается.
 *
 * <p>Один раунд {@link #solve}:
 * <ol>
 *   <li>Из ответа {@code POST …/smartcaptcha.cloud.yandex.ru/check} берутся массив
 *       {@code captcha.task} и ссылка на картинку. Тело в {@code onResponse} не читается:
 *       {@code response.text()} на соединении Playwright дедлочит следующий {@code locator.click}.
 *       Слушатель только кладёт {@link Response} в очередь; {@code drainPendingChecks} разбирает
 *       её на потоке солвера, вне кликов. Картинки и voice/html/js в очередь не попадают.</li>
 *   <li>В 2Captcha уходит {@code PazlCaptchaTask}: PNG 320×180 (оригинал CDN, иначе снимок canvas)
 *       и JSON-массив task. Ответ — число кликов по правой стрелке.</li>
 *   <li>Стрелка нажимается доверенным кликом Playwright. На серию кликов перехват
 *       {@code onResponse} снимается, пауза — через {@code Thread.sleep}, не {@code page.waitForTimeout}.</li>
 *   <li>После «Отправить» снимок индексов task/картинок делается до клика по кнопке.
 *       Исход читается до 5 секунд.</li>
 * </ol>
 *
 * <p>Исход «Отправить»:
 * <ul>
 *   <li>{@code GONE} — пазл не виден около 1.2 с и нового {@code /check} нет.
 *       Последнее тело часто {@code {"status":"ok","spravka":"…"}}. {@code reportCorrect}.</li>
 *   <li>{@code ADDITIONAL} — подпись «Дополнительная проверка» или выросли task/картинка.
 *       Яндекс засчитал кадр и выдал следующий: новый task, новая картинка, ползунок в 0.
 *       Это не ошибка решения. {@code reportCorrect}, ползунок назад не крутим,
 *       следующий раунд читает task и картинку только после прежних индексов
 *       ({@code taskUseFrom} / {@code imageUseFrom}). Если картинка уже новая, а тело task
 *       ещё не разобрано, первый пазл повторно не отправляется.</li>
 *   <li>{@code RESET} — подпись повтора и ползунок в нуле без нового захвата. {@code reportIncorrect}.</li>
 *   <li>{@code STUCK} — тот же кадр остался. {@code reportIncorrect} и откат слайдера.</li>
 * </ul>
 * Раундов не больше шести. На втором и следующих ждём, пока ползунок встанет в 0 (до 6 с).
 * {@code solution=0} от 2Captcha прекращает Pazl и поднимает {@link #lastPazlAbortedForAudio()}.
 *
 * <p>Перехват нужно включить {@link #beginNetworkCapture} до клика, который показывает капчу
 * (на YouDo — с начала логина, до «Далее»): task уходит в сеть раньше, чем солвер смотрит DOM.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexKaleidoscopeSolver {

    private static final int PAZL_WIDTH = 320;
    private static final int PAZL_HEIGHT = 180;
    private static final Pattern ARRAY_IN_TEXT = Pattern.compile("\\[\\s*(?:\\d+\\s*,\\s*){15,}\\d+\\s*\\]");

    private static final String KALEIDOSCOPE_MARKER =
            ".AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha-KaleidoscopeCanvas, #captcha-slider";

    /** Чекбокс «Я не робот» иногда оставляет 1×1 canvas в advanced-frame — не пазл. */
    private static final double MIN_KALEIDOSCOPE_CANVAS_W = 80;
    private static final double MIN_KALEIDOSCOPE_CANVAS_H = 40;
    private static final int MIN_PAZL_IMAGE_BASE64_LEN = 4_000;
    private static final int MIN_PAZL_IMAGE_BYTES = 3_000;

    private volatile boolean lastPazlAbortedForAudio;
    /** Индексы network-захвата: после «Отправить» второй пазл не должен брать task и картинку первого. */
    private int taskUseFrom;
    private int payloadUseFrom;
    private int imageUseFrom;

    /** Оригинал кадра из ответа {@code /check} / CDN SmartCaptcha, не screenshot canvas (рекомендация 2Captcha). */
    @Value("${captcha.yandex.pazl-original-image:true}")
    private boolean pazlPreferOriginalImage;

    private static final String[] SLIDER_ARROW_SELECTORS = {
            ".CaptchaSlider-Button_direction_right",
            ".AdvancedCaptcha-SliderControl-Button_direction_right",
            "button[aria-label*='вправо']",
            "button[aria-label*='Right']",
            "[data-testid='slider-control-forward']",
    };

    /** Помечает кнопку «вправо» внутри iframe. Событие click из JS капча игнорирует (не trusted). */
    private static final String MARK_RIGHT_ARROW_JS = """
            () => {
              document.querySelectorAll('[data-gdev-slider-right]').forEach((el) => {
                el.removeAttribute('data-gdev-slider-right');
              });
              const visible = (el) => {
                if (!el || el.disabled) return false;
                const r = el.getBoundingClientRect();
                const st = getComputedStyle(el);
                return r.width > 2 && r.height > 2
                  && st.display !== 'none' && st.visibility !== 'hidden' && st.pointerEvents !== 'none';
              };
              const nodes = [
                ...document.querySelectorAll('button, [role="button"], [class*="direction_right"], [class*="SliderControl"]'),
              ].filter(visible);
              const score = (el) => {
                const cls = String(el.className || '');
                const aria = (el.getAttribute('aria-label') || '').toLowerCase();
                const testid = (el.getAttribute('data-testid') || '').toLowerCase();
                const blob = cls + ' ' + aria + ' ' + testid;
                let s = 0;
                if (/direction_right|button_right|_right\\b/.test(cls)) s += 80;
                if (/вправ|вперёд|вперед|след|right|forward|next/.test(aria + ' ' + testid)) s += 60;
                if (/direction_left|влев|\\bleft\\b|back|prev/.test(blob)) s -= 120;
                const r = el.getBoundingClientRect();
                s += r.x / 50;
                return s;
              };
              let best = null;
              let bestScore = -1e9;
              for (const el of nodes) {
                const s = score(el);
                if (s > bestScore) {
                  bestScore = s;
                  best = el;
                }
              }
              if (!best || bestScore < 15) {
                return { ok: false };
              }
              best.setAttribute('data-gdev-slider-right', '1');
              return {
                ok: true,
                aria: best.getAttribute('aria-label') || '',
                className: String(best.className || '').slice(0, 180),
              };
            }
            """;

    private static final String MARK_LEFT_ARROW_JS = """
            () => {
              document.querySelectorAll('[data-gdev-slider-left]').forEach((el) => {
                el.removeAttribute('data-gdev-slider-left');
              });
              const visible = (el) => {
                if (!el || el.disabled) return false;
                const r = el.getBoundingClientRect();
                const st = getComputedStyle(el);
                return r.width > 2 && r.height > 2
                  && st.display !== 'none' && st.visibility !== 'hidden' && st.pointerEvents !== 'none';
              };
              const nodes = [...document.querySelectorAll('button, [role="button"]')].filter(visible);
              let best = null;
              let bestScore = -1e9;
              for (const el of nodes) {
                const aria = (el.getAttribute('aria-label') || '').toLowerCase();
                const cls = String(el.className || '');
                let s = 0;
                if (/влев|left|назад|prev|back/.test(aria + ' ' + cls)) s += 80;
                if (/вправ|right|вперёд|вперед/.test(aria)) s -= 120;
                if (s > bestScore) {
                  bestScore = s;
                  best = el;
                }
              }
              if (!best || bestScore < 15) return { ok: false };
              best.setAttribute('data-gdev-slider-left', '1');
              return { ok: true };
            }
            """;

    private static final String SLIDER_SNAPSHOT_JS = """
            () => {
              const slider = document.querySelector('#captcha-slider, [role="slider"], .CaptchaSlider');
              let now = null;
              if (slider) {
                const v = slider.getAttribute('aria-valuenow');
                if (v != null && v !== '') {
                  const n = parseInt(v, 10);
                  if (Number.isFinite(n)) now = n;
                }
              }
              const thumb = document.querySelector(
                '.CaptchaSlider-Thumb, [class*="Thumb"], [class*="thumb"]');
              let thumbPos = '';
              if (thumb) {
                const st = getComputedStyle(thumb);
                thumbPos = (thumb.style.left || '') + '|' + (st.transform || '') + '|' + (st.left || '');
              }
              let canvas = null;
              const c = document.querySelector('.AdvancedCaptcha-KaleidoscopeCanvas, canvas');
              if (c && c.width > 10 && c.height > 10) {
                try {
                  const ctx = c.getContext('2d');
                  if (ctx) {
                    const w = c.width;
                    const h = c.height;
                    const pts = [[0.25, 0.4], [0.5, 0.55], [0.75, 0.7]];
                    const parts = [];
                    for (const [px, py] of pts) {
                      const d = ctx.getImageData(Math.floor(w * px), Math.floor(h * py), 1, 1).data;
                      parts.push(d[0] + ',' + d[1] + ',' + d[2]);
                    }
                    canvas = parts.join(';');
                  }
                } catch (e) {
                  canvas = null;
                }
              }
              return { now, thumb: thumbPos, canvas };
            }
            """;

    private final TwoCaptchaClient twoCaptchaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<BrowserContext, NetworkCaptureSession> networkCaptures =
            new ConcurrentHashMap<>();

    /**
     * Слушать ответы SmartCaptcha до {@link #solve}. На YouDo вызывается в начале логина:
     * {@code /check} с task и картинкой часто приходит на клике «Далее», до того как пазл решают.
     * Повторный вызов на том же {@link BrowserContext} слушатель не дублирует.
     */
    public void beginNetworkCapture(Page page) {
        if (page == null) {
            return;
        }
        BrowserContext ctx = page.context();
        networkCaptures.computeIfAbsent(ctx, c -> {
            NetworkCaptureSession session = new NetworkCaptureSession();
            session.tasks = new CopyOnWriteArrayList<>();
            session.payloads = new CopyOnWriteArrayList<>();
            session.puzzleImageUrls = new CopyOnWriteArrayList<>();
            session.listener = captureTaskFromResponse(session);
            ctx.onResponse(session.listener);
            log.debug("Kaleidoscope: перехват network запущен (context)");
            return session;
        });
    }

    /** Снимает слушатель {@code onResponse}, повешенный {@link #beginNetworkCapture}. */
    public void endNetworkCapture(Page page) {
        if (page == null) {
            return;
        }
        BrowserContext ctx = page.context();
        NetworkCaptureSession session = networkCaptures.remove(ctx);
        if (session != null) {
            try {
                ctx.offResponse(session.listener);
            } catch (Exception e) {
                log.debug("Kaleidoscope: offResponse: {}", e.getMessage());
            }
            log.debug("Kaleidoscope: перехват network остановлен, task-событий={}", session.tasks.size());
        }
    }

    /** URL поля {@code voice} из ответа {@code /check} (load-captcha-voice или прямой voice?key). */
    public Optional<String> getLastVoiceUrl(Page page) {
        if (page == null) {
            return Optional.empty();
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.lastVoiceUrl == null || session.lastVoiceUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(session.lastVoiceUrl);
    }

    /**
     * В захвате уже есть валидный массив {@code captcha.task}.
     * Перед проверкой разбирает очередь {@code /check}: task мог прийти, пока шли клики.
     */
    public boolean hasCapturedPuzzleTask(Page page) {
        if (page == null) {
            return false;
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null) {
            return false;
        }
        drainPendingChecks(session);
        if (session.tasks == null || session.tasks.isEmpty()) {
            return false;
        }
        return pickValidatedNetworkTask(session.tasks, 0).isPresent();
    }

    /** Сброс task из /check после внешнего smart-token — иначе YouDo снова уходит в Pazl без canvas. */
    public void clearCapturedPuzzleTasks(Page page) {
        if (page == null) {
            return;
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session != null && session.tasks != null) {
            int n = session.tasks.size();
            session.tasks.clear();
            if (session.payloads != null) {
                session.payloads.clear();
            }
            if (session.puzzleImageUrls != null) {
                session.puzzleImageUrls.clear();
            }
            if (n > 0) {
                log.debug("Kaleidoscope: очищено network task-событий: {}", n);
            }
        }
    }

    public boolean isVisible(Page page) {
        return isKaleidoscopePuzzleVisible(page);
    }

    /** Слайдер-пазл (canvas), не чекбокс «Я не робот». */
    public boolean isKaleidoscopePuzzleVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (Frame f : page.frames()) {
                try {
                    Locator canvas = f.locator(".AdvancedCaptcha-KaleidoscopeCanvas").first();
                    if (canvas.count() > 0 && canvas.isVisible()) {
                        BoundingBoxSafe box = boundingBox(canvas);
                        if (box != null && box.onScreen() && box.isKaleidoscopeSize()) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (hasKaleidoscopeInFrame(f)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: visibility check: {}", e.getMessage());
        }
        return false;
    }

    /** Последний {@link #solve}: 2Captcha вернула Pazl {@code solution=0}, дальше нужен audio. */
    public boolean lastPazlAbortedForAudio() {
        return lastPazlAbortedForAudio;
    }

    /** URL аудио из ответа {@code /check} (то же, что открываешь в браузере). */
    public Optional<String> getLastCapturedVoiceUrl(Page page) {
        if (page == null) {
            return Optional.empty();
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.lastVoiceUrl == null || session.lastVoiceUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(session.lastVoiceUrl);
    }

    /**
     * Решает видимый калейдоскоп или уже пойманный task из {@code /check}.
     *
     * @return {@code true}, если пазла не было или после «Отправить» виджет снят
     *         ({@code status=ok} / пазл исчез и нового кадра нет).
     *         {@code false}, если за 6 раундов кадр остался, iframe пропал при живом task
     *         или 2Captcha вернула {@code solution=0} (тогда смотреть {@link #lastPazlAbortedForAudio()}).
     *         Дополнительная проверка сама по себе не делает раунд неуспешным: она запускает следующий.
     */
    public boolean solve(Page page) {
        lastPazlAbortedForAudio = false;
        taskUseFrom = 0;
        payloadUseFrom = 0;
        imageUseFrom = 0;
        boolean puzzleVisible = isVisible(page);
        boolean hasNetworkTask = hasCapturedPuzzleTask(page);
        if (!puzzleVisible && !hasNetworkTask) {
            return true;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("Kaleidoscope: 2Captcha не настроен");
            return false;
        }

        NetworkCaptureSession existing = networkCaptures.get(page.context());
        List<String> networkTasks;
        Consumer<Response> extraListener = null;
        final NetworkCaptureSession captureSession;
        if (existing != null) {
            captureSession = existing;
            networkTasks = existing.tasks;
        } else {
            NetworkCaptureSession temp = new NetworkCaptureSession();
            temp.tasks = new CopyOnWriteArrayList<>();
            temp.payloads = new CopyOnWriteArrayList<>();
            temp.puzzleImageUrls = new CopyOnWriteArrayList<>();
            captureSession = temp;
            networkTasks = temp.tasks;
            extraListener = captureTaskFromResponse(temp);
            page.context().onResponse(extraListener);
        }
        try {
            for (int round = 1; round <= 6; round++) {
                drainPendingChecks(captureSession);
                if (!isVisible(page) && !hasCapturedPuzzleTask(page)) {
                    log.info("Kaleidoscope: challenge снят (round={})", round);
                    return true;
                }
                log.info("Kaleidoscope: попытка {}/6", round);
                Optional<Frame> frame = findKaleidoscopeFrame(page);
                if (frame.isEmpty()) {
                    log.warn("Kaleidoscope: iframe/frame с canvas не найден (networkTasks={})", networkTasks.size());
                    sleepQuiet(800);
                    continue;
                }
                Frame captchaFrame = frame.get();
                log.info("Kaleidoscope: frame для solve url={}", truncate(safeFrameUrl(captchaFrame), 100));
                if (round > 1) {
                    waitUntilAdditionalPuzzleReady(captchaFrame, 6_000);
                    drainPendingChecks(captureSession);
                }

                Optional<String> taskJson = extractTaskJson(page, captchaFrame, networkTasks);
                if (taskJson.isEmpty()) {
                    log.warn("Kaleidoscope: массив task не извлечён (frame={}, networkTasks={}) — ждём",
                            truncate(safeFrameUrl(captchaFrame), 90), networkTasks.size());
                    sleepQuiet(1200);
                    continue;
                }
                log.info("Kaleidoscope: task для 2Captcha preview={} (источник: network или DOM)",
                        TwoCaptchaClient.summarizeTaskArray(taskJson.get()));

                waitForPuzzleCanvasReady(captchaFrame, 8_000);
                Optional<String> imageBase64 = pazlPreferOriginalImage
                        ? captureOriginalPuzzleImageBase64(page, captchaFrame, captureSession, taskJson.get())
                        : Optional.empty();
                String imageSource = imageBase64.isPresent() ? "network-original" : null;
                if (imageBase64.isEmpty()) {
                    imageBase64 = captureCanvasPngBase64(captchaFrame, page);
                    imageSource = imageBase64.isPresent() ? "canvas-screenshot" : null;
                }
                if (imageBase64.isEmpty()) {
                    log.warn("Kaleidoscope: не удалось получить image для PazlCaptchaTask (ни оригинал, ни canvas)");
                    sleepQuiet(800);
                    continue;
                }
                int imageLen = imageBase64.get().length();
                log.info("Kaleidoscope: Pazl image source={} base64 len={}",
                        imageSource, imageLen);
                if (imageLen < MIN_PAZL_IMAGE_BASE64_LEN) {
                    log.warn("Kaleidoscope: картинка ещё маленькая ({}, source={}) — ждём кадр, audio не включаем",
                            imageLen, imageSource);
                    sleepQuiet(1500);
                    continue;
                }

                int sliderMax = readSliderMax(captchaFrame);
                log.info("Kaleidoscope: → 2Captcha solvePazlCaptcha sliderMax={}", sliderMax);
                TwoCaptchaClient.PazlCaptchaInvokeResult pazlResult = twoCaptchaClient.solvePazlCaptcha(
                        imageBase64.get(), taskJson.get(), sliderMax, PAZL_WIDTH);
                if (pazlResult.audioFallback()) {
                    lastPazlAbortedForAudio = true;
                    log.warn("Kaleidoscope: Pazl solution=0 — прекращаем Pazl, переключаемся на audio");
                    break;
                }
                Optional<TwoCaptchaClient.PazlSolution> solution = pazlResult.solution();
                if (solution.isEmpty() || !solution.get().hasClicks() || solution.get().clicks() <= 0) {
                    log.warn("Kaleidoscope: 2Captcha не вернула положительное число кликов (sliderMax={})",
                            sliderMax);
                    if (clickRetryIfPresent(captchaFrame)) {
                        sleepQuiet(1500);
                    }
                    continue;
                }
                TwoCaptchaClient.PazlSolution pazl = solution.get();
                int clicks = pazl.clicks();
                long taskId = pazl.twoCaptchaTaskId();
                try {
                    page.bringToFront();
                } catch (Exception ignored) {
                }
                Optional<Frame> frameBeforeApply = findKaleidoscopeFrame(page);
                if (frameBeforeApply.isEmpty()) {
                    log.warn("Kaleidoscope: iframe пазла пропал перед apply {} кликов (taskId={})", clicks, taskId);
                    continue;
                }
                captchaFrame = frameBeforeApply.get();
                log.info("Kaleidoscope: применяем {} кликов по слайдеру (taskId={})", clicks, taskId);
                setNetworkCapturePaused(page, true);
                if (extraListener != null) {
                    try {
                        page.context().offResponse(extraListener);
                    } catch (Exception ignored) {
                    }
                }
                boolean applied;
                try {
                    applied = applySliderClicks(captchaFrame, page, clicks);
                } finally {
                    setNetworkCapturePaused(page, false);
                    if (extraListener != null) {
                        try {
                            page.context().onResponse(extraListener);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!applied) {
                    log.warn("Kaleidoscope: не удалось кликнуть по стрелке слайдера");
                    continue;
                }
                sleepQuiet(400);
                int tasksBefore = captureSize(page, CaptureSlot.TASKS);
                int payloadsBefore = captureSize(page, CaptureSlot.PAYLOADS);
                int imagesBefore = captureSize(page, CaptureSlot.IMAGES);
                boolean submitted = clickKaleidoscopeSubmit(captchaFrame);
                AfterSubmit outcome = waitAfterSubmit(page, captchaFrame, tasksBefore, imagesBefore, captureSession);
                if (outcome == AfterSubmit.GONE) {
                    log.info("Kaleidoscope: пройдено после «Отправить» ({} кликов, taskId={})", clicks, taskId);
                    twoCaptchaClient.reportCorrect(taskId);
                    return true;
                }
                if (outcome == AfterSubmit.ADDITIONAL) {
                    log.info("Kaleidoscope: после «Отправить» дополнительная проверка — новый пазл, taskId={} не помечаем неверным",
                            taskId);
                    twoCaptchaClient.reportCorrect(taskId);
                    armFreshPuzzle(page, tasksBefore, payloadsBefore, imagesBefore, captureSession);
                    continue;
                }
                if (!submitted && !isVisible(page)) {
                    log.info("Kaleidoscope: пройдено после {} кликов (taskId={})", clicks, taskId);
                    twoCaptchaClient.reportCorrect(taskId);
                    return true;
                }
                if (outcome == AfterSubmit.RESET || sliderAtStart(captchaFrame)) {
                    log.warn("Kaleidoscope: пазл сброшен после {} кликов — новый кадр, без отката, taskId={}",
                            clicks, taskId);
                    twoCaptchaClient.reportIncorrect(taskId);
                    armFreshPuzzle(page, tasksBefore, payloadsBefore, imagesBefore, captureSession);
                    continue;
                }
                if (captureGrew(page, tasksBefore, imagesBefore)) {
                    log.info("Kaleidoscope: после «Отправить» пришёл новый кадр — это доп. проверка, откат не делаем, taskId={}",
                            taskId);
                    twoCaptchaClient.reportCorrect(taskId);
                    armFreshPuzzle(page, tasksBefore, payloadsBefore, imagesBefore, captureSession);
                    continue;
                }
                log.warn("Kaleidoscope: капча осталась после {} кликов — reportIncorrect taskId={}",
                        clicks, taskId);
                twoCaptchaClient.reportIncorrect(taskId);
                rewindSliderToStart(captchaFrame, clicks);
            }
            if (lastPazlAbortedForAudio) {
                return false;
            }
            boolean gone = !isVisible(page);
            if (!gone) {
                log.warn("Kaleidoscope: не пройден за 6 раундов");
            }
            if (!gone && hasCapturedPuzzleTask(page) && findKaleidoscopeFrame(page).isEmpty()) {
                log.warn("Kaleidoscope: есть network task, но canvas/iframe пазла нет — считаем неуспехом");
                return false;
            }
            return gone;
        } finally {
            if (extraListener != null) {
                page.context().offResponse(extraListener);
            }
        }
    }

    private static String safeFrameUrl(Frame frame) {
        try {
            return frame != null ? frame.url() : "null";
        } catch (Exception e) {
            return "?";
        }
    }

    private record CapturedPuzzle(String taskJson, String imageRef) {
    }

    private static final class NetworkCaptureSession {
        CopyOnWriteArrayList<String> tasks;
        CopyOnWriteArrayList<CapturedPuzzle> payloads;
        CopyOnWriteArrayList<String> puzzleImageUrls;
        Consumer<Response> listener;
        volatile String lastVoiceUrl;
        volatile boolean paused;
        /** Тела {@code /check} читает только поток solve: {@code response.text()} в onResponse дедлочит клик. */
        final ConcurrentLinkedQueue<Response> pendingChecks = new ConcurrentLinkedQueue<>();
    }

    /**
     * mouse.click / locator.click дедлочится с {@code response.text()} в onResponse.
     * На время серии кликов слушатель снимаем — иначе до нужного числа нажатий не доходит.
     */
    private void setNetworkCapturePaused(Page page, boolean paused) {
        if (page == null) {
            return;
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.listener == null) {
            return;
        }
        try {
            if (paused && !session.paused) {
                page.context().offResponse(session.listener);
                session.paused = true;
                log.info("Kaleidoscope: перехват network на паузе (клики слайдера)");
            } else if (!paused && session.paused) {
                page.context().onResponse(session.listener);
                session.paused = false;
            }
        } catch (Exception e) {
            log.warn("Kaleidoscope: пауза перехвата network: {}", e.getMessage());
        }
    }

    private boolean hasKaleidoscopeInFrame(Frame frame) {
        try {
            Locator loc = frame.locator(KALEIDOSCOPE_MARKER);
            int n = loc.count();
            for (int i = 0; i < Math.min(n, 4); i++) {
                Locator one = loc.nth(i);
                if (one.isVisible()) {
                    BoundingBoxSafe box = boundingBox(one);
                    if (box != null && box.onScreen() && box.isKaleidoscopeSize()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private Optional<Frame> findKaleidoscopeFrame(Page page) {
        Frame advancedUrlFrame = null;
        Frame withCanvas = null;
        Frame withSlider = null;
        for (Frame f : page.frames()) {
            String url = safeFrameUrl(f);
            if (url.contains("smartcaptcha.cloud.yandex.ru") && url.contains("/advanced")) {
                advancedUrlFrame = f;
            }
            if (!hasKaleidoscopeInFrame(f)) {
                continue;
            }
            try {
                if (f.locator(".AdvancedCaptcha-KaleidoscopeCanvas, canvas").count() > 0) {
                    withCanvas = f;
                }
                if (f.locator("#captcha-slider, [data-testid='slider']").count() > 0) {
                    withSlider = f;
                }
            } catch (Exception ignored) {
            }
        }
        if (withCanvas != null) {
            return Optional.of(withCanvas);
        }
        if (advancedUrlFrame != null && hasKaleidoscopeInFrame(advancedUrlFrame)) {
            return Optional.of(advancedUrlFrame);
        }
        if (withSlider != null) {
            return Optional.of(withSlider);
        }
        return Optional.empty();
    }

    private Consumer<Response> captureTaskFromResponse(NetworkCaptureSession session) {
        return response -> {
            try {
                if (session.payloads == null) {
                    session.payloads = new CopyOnWriteArrayList<>();
                }
                if (session.puzzleImageUrls == null) {
                    session.puzzleImageUrls = new CopyOnWriteArrayList<>();
                }
                String url = response.url();
                if (url == null || (!url.contains("smartcaptcha") && !url.contains("captcha.yandex"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 300) {
                    return;
                }
                String contentType = response.headers().get("content-type");
                if (isPuzzleImageResponseUrl(url, contentType)) {
                    session.puzzleImageUrls.add(url);
                    log.info("Kaleidoscope: puzzle image URL из network {}", truncate(url, 120));
                    return;
                }
                // MP3 / voice / html / js: response.text() в onResponse вешает клик «Отправить» и доп. проверку.
                if (isSmartCaptchaBinaryVoiceResponse(url, contentType) || !url.contains("/check")) {
                    return;
                }
                session.pendingChecks.offer(response);
            } catch (Exception e) {
                log.debug("Kaleidoscope: network capture: {}", e.getMessage());
            }
        };
    }

    /**
     * Разбирает до 8 тел {@code /check}, отложенных из {@code onResponse}.
     * Только поток {@link #solve}, и не во время {@code locator.click}: чтение тела в слушателе
     * дедлочит клик на том же соединении Playwright.
     * Из тела забираются voice URL, массив task и ссылка на картинку.
     * {@code status=ok} без массива task в список пазлов не попадает — это снятие капчи, не новый кадр.
     */
    private void drainPendingChecks(NetworkCaptureSession session) {
        if (session == null) {
            return;
        }
        Response response;
        int n = 0;
        while ((response = session.pendingChecks.poll()) != null && n < 8) {
            n++;
            try {
                String url = response.url();
                String body = response.text();
                if (body == null || body.isBlank()) {
                    continue;
                }
                extractVoiceUrlFromCheckBody(body).ifPresent(voice -> {
                    session.lastVoiceUrl = voice;
                    log.info("Kaleidoscope: voice из /check {}", truncate(voice, 120));
                });
                Optional<String> imageRef = extractPuzzleImageRefFromJson(body);
                findTaskArrayInJson(body).ifPresentOrElse(task -> {
                    if (session.tasks == null) {
                        session.tasks = new CopyOnWriteArrayList<>();
                    }
                    if (session.payloads == null) {
                        session.payloads = new CopyOnWriteArrayList<>();
                    }
                    session.tasks.add(task);
                    String img = imageRef.orElse(null);
                    session.payloads.add(new CapturedPuzzle(task, img));
                    if (img != null) {
                        log.info("Kaleidoscope: puzzle image ref из /check (с task) {}", truncate(img, 120));
                    }
                    log.info("Kaleidoscope: task из network {} len={}", truncate(url, 80),
                            TwoCaptchaClient.summarizeTaskArray(task));
                }, () -> {
                    imageRef.ifPresent(ref -> {
                        session.puzzleImageUrls.add(ref);
                        log.info("Kaleidoscope: puzzle image ref из JSON (без task) {}", truncate(ref, 120));
                    });
                    log.debug("Kaleidoscope: /check без task url={} bodyPreview={}",
                            truncate(url, 70), truncate(body.replaceAll("\\s+", " "), 120));
                });
            } catch (Exception e) {
                log.debug("Kaleidoscope: разбор /check: {}", e.getMessage());
            }
        }
    }

    private Optional<String> extractTaskJson(Page page, Frame frame, List<String> networkTasks) {
        if (taskUseFrom > 0) {
            Optional<String> fresh = pickValidatedNetworkTask(networkTasks, taskUseFrom);
            if (fresh.isPresent()) {
                return fresh;
            }
            Optional<String> fromDom = extractTaskJsonFromFrames(page, frame);
            Optional<String> previous = pickValidatedNetworkTask(networkTasks, 0);
            if (fromDom.isPresent() && (previous.isEmpty() || !fromDom.get().equals(previous.get()))) {
                return fromDom;
            }
            return Optional.empty();
        }
        Optional<String> fromNetwork = pickValidatedNetworkTask(networkTasks, 0);
        if (fromNetwork.isPresent()) {
            return fromNetwork;
        }
        return extractTaskJsonFromFrames(page, frame);
    }

    private Optional<String> extractTaskJsonFromFrames(Page page, Frame frame) {
        for (Frame f : page.frames()) {
            String url = safeFrameUrl(f);
            if (url.contains("smartcaptcha") || url.contains("captcha.yandex")) {
                Optional<String> fromFrame = extractTaskJsonFromFrameDom(f);
                if (fromFrame.isPresent()) {
                    return fromFrame;
                }
            }
        }
        return extractTaskJsonFromFrameDom(frame);
    }

    private Optional<String> extractTaskJsonFromFrameDom(Frame frame) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => {
                              const isPuzzleArray = (arr) => {
                                if (!Array.isArray(arr) || arr.length < 15 || arr.length > 100) return false;
                                return arr.every((n) => Number.isInteger(n) && n >= 0 && n <= 20);
                              };
                              const pickLongestArray = (text) => {
                                if (!text) return null;
                                const re = /\\[(?:\\s*\\d+\\s*,\\s*){14,}\\s*\\d+\\s*\\]/g;
                                let best = null;
                                let m;
                                while ((m = re.exec(text)) !== null) {
                                  if (!best || m[0].length > best.length) best = m[0];
                                }
                                return best;
                              };
                              const canvas = document.querySelector('.AdvancedCaptcha-KaleidoscopeCanvas, canvas');
                              if (canvas) {
                                for (const attr of ['data-task', 'data-puzzle', 'data-state', 'data-steps']) {
                                  const v = canvas.getAttribute(attr);
                                  if (v && v.includes('[')) return v;
                                }
                                const parent = canvas.closest('.AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha');
                                if (parent) {
                                  for (const attr of ['data-task', 'data-puzzle', 'data-state']) {
                                    const v = parent.getAttribute(attr);
                                    if (v && v.includes('[')) return v;
                                  }
                                }
                              }
                              const htmlArr = pickLongestArray(document.documentElement.innerHTML);
                              if (htmlArr) return htmlArr;
                              const scripts = document.querySelectorAll('script');
                              for (const s of scripts) {
                                const t = s.textContent || '';
                                const m = pickLongestArray(t);
                                if (m) return m;
                              }
                              const keys = Object.keys(window);
                              for (const k of keys) {
                                try {
                                  const v = window[k];
                                  if (isPuzzleArray(v)) return JSON.stringify(v);
                                  if (v && typeof v === 'object') {
                                    for (const sub of ['task', 'puzzle', 'steps', 'permutation', 'order', 'cells']) {
                                      if (isPuzzleArray(v[sub])) return JSON.stringify(v[sub]);
                                    }
                                  }
                                } catch (e) { /* ignore */ }
                              }
                              return null;
                            }
                            """);
            if (raw instanceof String s && !s.isBlank()) {
                return normalizeTaskJson(s);
            }
        } catch (Exception e) {
            log.warn("Kaleidoscope: DOM extract task: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> extractVoiceUrlFromCheckBody(String body) {
        if (body == null || body.isBlank() || !body.contains("voice")) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode voice = root.path("captcha").path("voice");
            if (voice.isTextual()) {
                String v = voice.asText().trim();
                if (!v.isEmpty()) {
                    return Optional.of(v);
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private Optional<String> findTaskArrayInJson(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            Optional<JsonNode> arr = findPuzzleArrayNode(root);
            if (arr.isPresent()) {
                return Optional.of(arr.get().toString());
            }
        } catch (Exception ignored) {
        }
        Matcher m = ARRAY_IN_TEXT.matcher(body);
        while (m.find()) {
            Optional<String> candidate = normalizeTaskJson(m.group());
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private Optional<String> pickValidatedNetworkTask(List<String> networkTasks, int fromIndex) {
        if (networkTasks == null || networkTasks.isEmpty()) {
            return Optional.empty();
        }
        int start = Math.max(0, fromIndex);
        if (start >= networkTasks.size()) {
            return Optional.empty();
        }
        for (int i = networkTasks.size() - 1; i >= start; i--) {
            Optional<String> task = normalizeTaskJson(networkTasks.get(i));
            if (task.isPresent()) {
                return task;
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findPuzzleArrayNode(JsonNode node) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isArray() && isPuzzleArray(node)) {
            return Optional.of(node);
        }
        if (node.isObject()) {
            for (String field : List.of("task", "puzzle", "steps", "permutation", "order", "cells", "data")) {
                JsonNode child = node.get(field);
                if (child != null && child.isArray() && isPuzzleArray(child)) {
                    return Optional.of(child);
                }
            }
            for (JsonNode child : node) {
                Optional<JsonNode> nested = findPuzzleArrayNode(child);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPuzzleArray(JsonNode arr) {
        if (!arr.isArray() || arr.size() < 15 || arr.size() > 100) {
            return false;
        }
        for (JsonNode n : arr) {
            if (!n.isInt() && !n.isIntegralNumber()) {
                return false;
            }
            int v = n.asInt();
            if (v < 0 || v > 20) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> normalizeTaskJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        try {
            JsonNode n = new ObjectMapper().readTree(s);
            if (n.isArray() && isPuzzleArray(n)) {
                return Optional.of(n.toString());
            }
            if (n.isTextual()) {
                return normalizeTaskJson(n.asText());
            }
        } catch (Exception e) {
            Matcher m = ARRAY_IN_TEXT.matcher(s);
            while (m.find()) {
                Optional<String> inner = normalizeTaskJson(m.group());
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> captureOriginalPuzzleImageBase64(Page page, Frame frame,
                                                              NetworkCaptureSession session,
                                                              String taskJson) {
        if (session == null) {
            return Optional.empty();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (session.payloads != null) {
            for (int i = session.payloads.size() - 1; i >= payloadUseFrom; i--) {
                CapturedPuzzle p = session.payloads.get(i);
                if (p.taskJson != null && p.taskJson.equals(taskJson) && p.imageRef != null && !p.imageRef.isBlank()) {
                    candidates.add(p.imageRef);
                    break;
                }
            }
            for (int i = session.payloads.size() - 1; i >= payloadUseFrom; i--) {
                String ref = session.payloads.get(i).imageRef;
                if (ref != null && !ref.isBlank()) {
                    candidates.add(ref);
                }
            }
        }
        if (session.puzzleImageUrls != null) {
            for (int i = session.puzzleImageUrls.size() - 1; i >= imageUseFrom; i--) {
                String u = session.puzzleImageUrls.get(i);
                if (u != null && !u.isBlank()) {
                    candidates.add(u);
                }
            }
        }
        for (String ref : candidates) {
            Optional<byte[]> bytes = resolvePuzzleImageBytes(page, frame, ref);
            if (bytes.isEmpty()) {
                continue;
            }
            try {
                byte[] normalized = normalizePuzzleImageBytes(bytes.get());
                if (normalized.length < MIN_PAZL_IMAGE_BYTES) {
                    log.warn("Kaleidoscope: оригинал image слишком маленький ({} bytes), ref={}",
                            normalized.length, truncate(ref, 80));
                    continue;
                }
                log.info("Kaleidoscope: оригинал image {} bytes, ref={}",
                        normalized.length, truncate(ref, 90));
                return Optional.of(Base64.getEncoder().encodeToString(normalized));
            } catch (Exception e) {
                log.warn("Kaleidoscope: нормализация оригинала image: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<byte[]> resolvePuzzleImageBytes(Page page, Frame frame, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("data:image")) {
            return decodeDataUrlImage(trimmed);
        }
        if (looksLikeRawBase64Image(trimmed)) {
            try {
                return Optional.of(Base64.getDecoder().decode(trimmed));
            } catch (Exception e) {
                log.debug("Kaleidoscope: base64 image decode: {}", e.getMessage());
            }
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return downloadPuzzleImageBytes(page, frame, trimmed);
        }
        return Optional.empty();
    }

    private static boolean looksLikeRawBase64Image(String s) {
        if (s.length() < 500 || s.length() > 2_000_000) {
            return false;
        }
        for (int i = 0; i < Math.min(s.length(), 200); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r')) {
                return false;
            }
        }
        return true;
    }

    private static Optional<byte[]> decodeDataUrlImage(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<byte[]> downloadPuzzleImageBytes(Page page, Frame frame, String url) {
        LinkedHashSet<String> tryUrls = new LinkedHashSet<>();
        String unwrapped = unwrapLoadCaptchaImageUrl(url);
        if (unwrapped != null && !unwrapped.isBlank()) {
            tryUrls.add(unwrapped);
        }
        tryUrls.add(url);
        for (String candidate : tryUrls) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Optional<byte[]> viaApi = fetchPuzzleImageUrl(page, frame, candidate);
            if (viaApi.isPresent()) {
                return viaApi;
            }
            if (candidate.contains("image?key=") || candidate.toLowerCase().contains("load-captcha-image")) {
                Optional<byte[]> viaBrowser = fetchPuzzleImageInBrowser(frame, candidate);
                if (viaBrowser.isPresent()) {
                    return viaBrowser;
                }
            }
        }
        return Optional.empty();
    }

    /** {@code load-captcha-image?&lt;base64(real image url)&gt;} из ответа /check (аналог voice). */
    private static String unwrapLoadCaptchaImageUrl(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase();
        if (!lower.contains("load-captcha-image") && !lower.contains("load-captcha-kaleidoscope")) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return url;
        }
        String payload = url.substring(q + 1);
        for (java.util.Base64.Decoder decoder : List.of(Base64.getUrlDecoder(), Base64.getDecoder())) {
            try {
                String inner = new String(decoder.decode(payload), StandardCharsets.UTF_8).trim();
                if (inner.startsWith("http") && !inner.toLowerCase().contains("voice")) {
                    return inner;
                }
            } catch (Exception ignored) {
                // try next decoder
            }
        }
        return url;
    }

    private Optional<byte[]> fetchPuzzleImageUrl(Page page, Frame frame, String url) {
        try {
            String referer = frame != null ? safeFrameUrl(frame) : null;
            if (referer == null || referer.isBlank()) {
                referer = page.url();
            }
            RequestOptions opts = RequestOptions.create().setTimeout(12_000);
            if (referer != null && !referer.isBlank()) {
                opts.setHeader("Referer", referer);
            }
            String cookieHeader = buildCookieHeader(page, url);
            if (!cookieHeader.isBlank()) {
                opts.setHeader("Cookie", cookieHeader);
            }
            var response = page.context().request().get(url, opts);
            int status = response.status();
            byte[] body = response.body();
            int len = body == null ? 0 : body.length;
            if (status >= 200 && status < 300 && body != null && len >= MIN_PAZL_IMAGE_BYTES && len <= 2_000_000) {
                log.info("Kaleidoscope: puzzle image APIRequest ok {} bytes status={} url={}",
                        len, status, truncate(url, 90));
                return Optional.of(body);
            }
            log.debug("Kaleidoscope: puzzle image APIRequest fail status={} len={} url={}",
                    status, len, truncate(url, 80));
        } catch (Exception e) {
            log.debug("Kaleidoscope: puzzle image APIRequest {}: {}", truncate(url, 80), e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<byte[]> fetchPuzzleImageInBrowser(Frame frame, String url) {
        try {
            Object raw = frame.evaluate(
                    """
                            async (imageUrl) => {
                              const ctrl = new AbortController();
                              const t = setTimeout(() => ctrl.abort(), 12000);
                              let r;
                              try {
                                r = await fetch(imageUrl, {
                                  credentials: 'include', cache: 'no-store', signal: ctrl.signal
                                });
                              } finally {
                                clearTimeout(t);
                              }
                              if (!r.ok) return { ok: false, status: r.status, len: 0 };
                              const buf = await r.arrayBuffer();
                              const u8 = new Uint8Array(buf);
                              let binary = '';
                              const chunk = 0x8000;
                              for (let i = 0; i < u8.length; i += chunk) {
                                binary += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
                              }
                              return { ok: true, status: r.status, len: u8.length, b64: btoa(binary) };
                            }
                            """,
                    url);
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                return Optional.empty();
            }
            if (!Boolean.TRUE.equals(map.get("ok"))) {
                return Optional.empty();
            }
            Object b64Obj = map.get("b64");
            if (!(b64Obj instanceof String b64) || b64.isEmpty()) {
                return Optional.empty();
            }
            byte[] body = Base64.getDecoder().decode(b64);
            if (body.length < MIN_PAZL_IMAGE_BYTES) {
                return Optional.empty();
            }
            log.info("Kaleidoscope: puzzle image fetch в iframe ok {} bytes", body.length);
            return Optional.of(body);
        } catch (Exception e) {
            log.debug("Kaleidoscope: puzzle image fetch в iframe: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String buildCookieHeader(Page page, String url) {
        try {
            var cookies = page.context().cookies(url);
            if (cookies == null || cookies.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (var c : cookies) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c.name).append('=').append(c.value);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** PNG для 2Captcha: оригинальные байты, без upscale; resize только если размер не 320×180. */
    private byte[] normalizePuzzleImageBytes(byte[] raw) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) {
            return raw;
        }
        if (src.getWidth() == PAZL_WIDTH && src.getHeight() == PAZL_HEIGHT) {
            if (raw.length > 8 && raw[0] == (byte) 0x89 && raw[1] == 0x50) {
                return raw;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(src, "png", png);
            return png.toByteArray();
        }
        log.info("Kaleidoscope: оригинал {}x{} → PNG {}x{}", src.getWidth(), src.getHeight(), PAZL_WIDTH, PAZL_HEIGHT);
        return resizePngTo320x180(raw);
    }

    private Optional<String> extractPuzzleImageRefFromJson(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            Optional<String> direct = extractPuzzleImageRefFromNode(root.path("captcha"));
            if (direct.isPresent()) {
                return direct;
            }
            return findPuzzleImageRefInTree(root);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> extractPuzzleImageRefFromNode(JsonNode captcha) {
        if (captcha == null || captcha.isMissingNode()) {
            return Optional.empty();
        }
        for (String field : List.of("image", "img", "picture", "art", "kaleidoscope", "puzzle")) {
            JsonNode n = captcha.path(field);
            if (n.isTextual()) {
                String v = n.asText().trim();
                if (isPuzzleImageRefValue(v)) {
                    return Optional.of(v);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> findPuzzleImageRefInTree(JsonNode node) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isTextual()) {
            String v = node.asText().trim();
            return isPuzzleImageRefValue(v) ? Optional.of(v) : Optional.empty();
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                Optional<String> found = findPuzzleImageRefInTree(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> found = findPuzzleImageRefInTree(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPuzzleImageRefValue(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String lower = v.toLowerCase();
        if (lower.contains("voice") || lower.contains(".mp3")) {
            return false;
        }
        if (v.startsWith("data:image/")) {
            return true;
        }
        if (v.startsWith("http://") || v.startsWith("https://")) {
            return lower.contains("load-captcha-image")
                    || lower.contains("load-captcha-kaleidoscope")
                    || lower.contains("image?key=")
                    || lower.contains("kaleidoscope")
                    || (lower.contains("smartcaptcha") && (lower.contains(".png") || lower.contains(".jpg")
                    || lower.contains(".jpeg") || lower.contains("/image")));
        }
        return looksLikeRawBase64Image(v);
    }

    private static boolean isPuzzleImageResponseUrl(String url, String contentType) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.contains("voice") || lower.contains("load-captcha-voice") || lower.contains(".mp3")) {
            return false;
        }
        if (lower.contains("load-captcha-image")
                || lower.contains("load-captcha-kaleidoscope")
                || lower.contains("image?key=")) {
            return true;
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("image/png") || ct.contains("image/jpeg") || ct.contains("image/webp")) {
                return lower.contains("smartcaptcha") || lower.contains("captcha.yandex")
                        || lower.contains("yandexcloud");
            }
        }
        return false;
    }

    private Optional<String> captureCanvasPngBase64(Frame frame, Page page) {
        String[] canvasSelectors = {
                ".AdvancedCaptcha-KaleidoscopeCanvas",
                "canvas.AdvancedCaptcha-Canvas",
                "canvas"
        };
        for (String sel : canvasSelectors) {
            try {
                Locator canvas = frame.locator(sel).first();
                if (canvas.count() == 0) {
                    continue;
                }
                canvas.waitFor(new Locator.WaitForOptions().setTimeout(8_000));
                byte[] png = canvas.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.PNG));
                byte[] resized = resizePngTo320x180(png);
                log.info("Kaleidoscope: canvas screenshot selector={}", sel);
                return Optional.of(Base64.getEncoder().encodeToString(resized));
            } catch (Exception e) {
                log.debug("Kaleidoscope: canvas {}: {}", sel, e.getMessage());
            }
        }
        log.warn("Kaleidoscope: canvas не найден во frame {}", truncate(safeFrameUrl(frame), 80));
        return Optional.empty();
    }

    static byte[] resizePngTo320x180(byte[] png) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) {
            throw new IllegalStateException("PNG decode failed");
        }
        if (src.getWidth() == PAZL_WIDTH && src.getHeight() == PAZL_HEIGHT) {
            return png;
        }
        BufferedImage dst = new BufferedImage(PAZL_WIDTH, PAZL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src.getScaledInstance(PAZL_WIDTH, PAZL_HEIGHT, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }

    private void waitForPuzzleCanvasReady(Frame frame, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Object ready = frame.evaluate(
                        """
                                () => {
                                  const c = document.querySelector('.AdvancedCaptcha-KaleidoscopeCanvas, canvas');
                                  if (!c) return false;
                                  const r = c.getBoundingClientRect();
                                  return c.width >= 200 && c.height >= 80 && r.width >= 120 && r.height >= 60;
                                }
                                """);
                if (Boolean.TRUE.equals(ready)) {
                    return;
                }
            } catch (Exception e) {
                log.debug("Kaleidoscope: ожидание canvas: {}", e.getMessage());
                return;
            }
            sleepQuiet(300);
        }
        log.info("Kaleidoscope: canvas за {} мс не дорисовался — всё равно снимаем кадр", timeoutMs);
    }

    private int readSliderMax(Frame frame) {
        try {
            Locator slider = frame.locator("#captcha-slider, [data-testid='slider']").first();
            if (slider.count() == 0) {
                return 28;
            }
            String max = slider.getAttribute("aria-valuemax");
            if (max != null && !max.isBlank()) {
                int v = Integer.parseInt(max.trim());
                if (v > 0 && v <= 64) {
                    return v;
                }
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: aria-valuemax: {}", e.getMessage());
        }
        return 28;
    }

    private boolean applySliderClicks(Frame frame, Page page, int clicks) {
        if (clicks <= 0 || page == null) {
            return false;
        }
        if (!prepareRightArrow(frame)) {
            log.warn("Kaleidoscope: стрелка вправо не найдена — ArrowRight на слайдере");
            return applySliderViaKeyboard(frame, clicks);
        }
        Locator btn = frame.locator("[data-gdev-slider-right='1']").first();
        int applied = clickArrowSeries(btn, clicks, "вправо");
        if (applied < clicks && prepareRightArrow(frame)) {
            int rest = clicks - applied;
            applied += clickArrowSeries(frame.locator("[data-gdev-slider-right='1']").first(), rest, "вправо");
        }
        log.info("Kaleidoscope: клики слайдера сделаны {}/{}", Math.min(applied, clicks), clicks);
        return applied >= clicks;
    }

    /**
     * Ровный ритм ~520 мс: Яндекс отбрасывает клики во время анимации шага,
     * а проверка canvas/aria между кликами давала ложные «не сдвинулся» и повтор одного и того же нажатия.
     */
    private int clickArrowSeries(Locator btn, int clicks, String side) {
        int applied = 0;
        for (int i = 0; i < clicks; i++) {
            if (!clickArrowOnce(btn)) {
                log.warn("Kaleidoscope: стрелка {} не нажалась на {}/{}", side, applied, clicks);
                break;
            }
            applied++;
            if (applied == 1 || applied == clicks || applied % 5 == 0) {
                log.info("Kaleidoscope: слайдер {} {}/{}", side, applied, clicks);
            }
            if (applied < clicks) {
                sleepQuiet(520);
            }
        }
        return applied;
    }

    private boolean clickArrowOnce(Locator btn) {
        try {
            if (btn.count() == 0) {
                return false;
            }
            btn.click(new Locator.ClickOptions()
                    .setTimeout(1500)
                    .setDelay(40)
                    .setNoWaitAfter(true)
                    .setForce(true));
            return true;
        } catch (Exception e) {
            log.debug("Kaleidoscope: click стрелки: {}", e.getMessage());
            return false;
        }
    }

    private static final String[] KALEIDOSCOPE_SUBMIT_SELECTORS = {
            "button[data-testid='submit']",
            "button[data-testid='submit-button']",
            ".AdvancedCaptcha-SubmitButton",
            "button:has-text('Отправить')",
            "button:has-text('Готово')"
    };

    /** Пазл остаётся на экране, пока не нажата «Отправить». Без этого клика серия выглядит как провал. */
    private boolean clickKaleidoscopeSubmit(Frame frame) {
        long deadline = System.currentTimeMillis() + 3_500;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : KALEIDOSCOPE_SUBMIT_SELECTORS) {
                try {
                    Locator btn = frame.locator(selector).first();
                    if (btn.count() == 0 || !btn.isVisible()) {
                        continue;
                    }
                    if (btn.isDisabled()) {
                        continue;
                    }
                    String ariaDisabled = btn.getAttribute("aria-disabled");
                    if ("true".equalsIgnoreCase(ariaDisabled)) {
                        continue;
                    }
                    String text = "";
                    try {
                        text = btn.innerText();
                    } catch (Exception ignored) {
                    }
                    log.info("Kaleidoscope: жмём «{}» selector={}", text == null ? "" : text.trim(), selector);
                    btn.click(new Locator.ClickOptions()
                            .setTimeout(2_000)
                            .setNoWaitAfter(true)
                            .setForce(true));
                    return true;
                } catch (Exception e) {
                    log.debug("Kaleidoscope: submit {}: {}", selector, e.getMessage());
                }
            }
            sleepQuiet(250);
        }
        logCaptchaButtons(frame);
        log.warn("Kaleidoscope: кнопка «Отправить» не найдена или disabled");
        return false;
    }

    private void logCaptchaButtons(Frame frame) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => [...document.querySelectorAll('button')].slice(0, 12).map((b) => ({
                              text: (b.innerText || '').trim().slice(0, 40),
                              test: b.getAttribute('data-testid'),
                              disabled: !!b.disabled,
                              aria: b.getAttribute('aria-label')
                            }))
                            """);
            log.info("Kaleidoscope: кнопки в iframe {}", raw);
        } catch (Exception e) {
            log.debug("Kaleidoscope: список кнопок: {}", e.getMessage());
        }
    }

    private boolean waitUntilPuzzleGone(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isVisible(page)) {
                return true;
            }
            sleepQuiet(300);
        }
        return !isVisible(page);
    }

    /** После неудачной серии вернуть ползунок в начало, иначе следующий ответ 2Captcha сложится с уже сделанными шагами. */
    private void rewindSliderToStart(Frame frame, int clicks) {
        if (clicks <= 0 || sliderAtStart(frame)) {
            log.info("Kaleidoscope: ползунок уже в начале, откат не нужен");
            return;
        }
        try {
            Object raw = frame.evaluate(MARK_LEFT_ARROW_JS);
            if (!(raw instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("ok"))) {
                log.warn("Kaleidoscope: стрелка влево не найдена, ползунок не откатываем");
                return;
            }
            log.info("Kaleidoscope: откат ползунка на {} кликов влево", clicks);
            clickArrowSeries(frame.locator("[data-gdev-slider-left='1']").first(), clicks, "влево");
        } catch (Exception e) {
            log.warn("Kaleidoscope: откат ползунка: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean prepareRightArrow(Frame frame) {
        try {
            Object raw = frame.evaluate(MARK_RIGHT_ARROW_JS);
            if (raw instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"))) {
                log.debug("Kaleidoscope: стрелка вправо aria='{}' class={}",
                        map.get("aria"), map.get("className"));
                return true;
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: mark arrow: {}", e.getMessage());
        }
        for (String sel : SLIDER_ARROW_SELECTORS) {
            try {
                Locator loc = frame.locator(sel).first();
                if (loc.count() == 0 || !loc.isVisible()) {
                    continue;
                }
                loc.evaluate("el => el.setAttribute('data-gdev-slider-right', '1')");
                log.debug("Kaleidoscope: стрелка слайдера selector={}", sel);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean clickMarkedArrow(Frame frame, boolean force) {
        try {
            Locator btn = frame.locator("[data-gdev-slider-right='1']").first();
            if (btn.count() == 0) {
                return false;
            }
            btn.click(new Locator.ClickOptions()
                    .setTimeout(4000)
                    .setDelay(60)
                    .setNoWaitAfter(true)
                    .setForce(force));
            return true;
        } catch (Exception e) {
            if (!force) {
                return clickMarkedArrow(frame, true);
            }
            log.warn("Kaleidoscope: click стрелки: {}", e.getMessage());
            return false;
        }
    }

    private SliderSnap waitForSliderMove(Frame frame, SliderSnap before, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SliderSnap last = before;
        while (System.currentTimeMillis() < deadline) {
            sleepQuiet(90);
            last = readSliderSnap(frame);
            if (last.movedFrom(before)) {
                return last;
            }
        }
        return last;
    }

    @SuppressWarnings("unchecked")
    private SliderSnap readSliderSnap(Frame frame) {
        try {
            Object raw = frame.evaluate(SLIDER_SNAPSHOT_JS);
            if (raw instanceof Map<?, ?> map) {
                return new SliderSnap(intOrNull(map.get("now")), strOrEmpty(map.get("thumb")), strOrNull(map.get("canvas")));
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: snapshot слайдера: {}", e.getMessage());
        }
        return SliderSnap.EMPTY;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private record SliderSnap(Integer now, String thumb, String canvas) {
        private static final SliderSnap EMPTY = new SliderSnap(null, "", null);

        boolean hasSignal() {
            return now != null || canvas != null || (thumb != null && thumb.length() > 4);
        }

        boolean movedFrom(SliderSnap prev) {
            if (prev == null) {
                return false;
            }
            if (now != null && prev.now != null && !now.equals(prev.now)) {
                return true;
            }
            if (canvas != null && prev.canvas != null && !canvas.equals(prev.canvas)) {
                return true;
            }
            return thumb != null && prev.thumb != null && thumb.length() > 4 && !thumb.equals(prev.thumb);
        }
    }

    private boolean applySliderViaKeyboard(Frame frame, int clicks) {
        try {
            Locator slider = frame.locator("#captcha-slider, [role='slider']").first();
            if (slider.count() == 0) {
                return false;
            }
            slider.scrollIntoViewIfNeeded();
            slider.focus();
            for (int i = 0; i < clicks; i++) {
                slider.press("ArrowRight");
                sleepQuiet(420);
            }
            return true;
        } catch (Exception e) {
            log.warn("Kaleidoscope: ArrowRight на слайдере: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Что произошло после «Отправить».
     * {@code GONE} — виджет снят. {@code ADDITIONAL} — Яндекс засчитал кадр и открыл следующий.
     * {@code RESET} — тот же пазл сброшен как неверный. {@code STUCK} — кадр не сменился и не исчез.
     */
    private enum AfterSubmit {
        GONE, ADDITIONAL, RESET, STUCK
    }

    private enum CaptureSlot {
        TASKS, PAYLOADS, IMAGES
    }

    private int captureSize(Page page, CaptureSlot slot) {
        NetworkCaptureSession session = page == null ? null : networkCaptures.get(page.context());
        if (session == null) {
            return 0;
        }
        return switch (slot) {
            case TASKS -> session.tasks == null ? 0 : session.tasks.size();
            case PAYLOADS -> session.payloads == null ? 0 : session.payloads.size();
            case IMAGES -> session.puzzleImageUrls == null ? 0 : session.puzzleImageUrls.size();
        };
    }

    private boolean captureGrew(Page page, int tasksBefore, int imagesBefore) {
        return captureSize(page, CaptureSlot.TASKS) > tasksBefore
                || captureSize(page, CaptureSlot.IMAGES) > imagesBefore;
    }

    /**
     * Ждёт исход уже отправленного кадра, не дольше 5 с.
     * «Дополнительная проверка» или рост захвата (новый task либо новая картинка) — сразу
     * {@code ADDITIONAL}, даже если ползунок ещё не в нуле: Яндекс уже выдал следующий пазл.
     * Исчезновение canvas короче 1.2 с успехом не считается: кадр мигает при смене пазла.
     * Новый захват важнее отката слайдера.
     */
    private AfterSubmit waitAfterSubmit(Page page, Frame frame, int tasksBefore, int imagesBefore,
                                       NetworkCaptureSession session) {
        log.info("Kaleidoscope: ждём исход после «Отправить» (tasks={} images={})", tasksBefore, imagesBefore);
        long deadline = System.currentTimeMillis() + 5_000;
        long goneSince = -1;
        while (System.currentTimeMillis() < deadline) {
            drainPendingChecks(session);
            if (isAdditionalVerification(frame) || captureGrew(page, tasksBefore, imagesBefore)) {
                log.info("Kaleidoscope: исход после «Отправить» = дополнительная проверка");
                return AfterSubmit.ADDITIONAL;
            }
            if (!isVisible(page)) {
                if (goneSince < 0) {
                    goneSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - goneSince >= 1_200) {
                    log.info("Kaleidoscope: исход после «Отправить» = капча снята");
                    return AfterSubmit.GONE;
                }
            } else {
                goneSince = -1;
                Frame current = findKaleidoscopeFrame(page).orElse(frame);
                if (isAdditionalVerification(current)) {
                    log.info("Kaleidoscope: исход после «Отправить» = дополнительная проверка");
                    return AfterSubmit.ADDITIONAL;
                }
                if (isRetryLabelVisible(current) && sliderAtStart(current)) {
                    log.info("Kaleidoscope: исход после «Отправить» = пазл сброшен");
                    return AfterSubmit.RESET;
                }
            }
            sleepQuiet(300);
        }
        drainPendingChecks(session);
        if (isAdditionalVerification(frame) || captureGrew(page, tasksBefore, imagesBefore)) {
            log.info("Kaleidoscope: исход после «Отправить» = дополнительная проверка");
            return AfterSubmit.ADDITIONAL;
        }
        if (!isVisible(page)) {
            log.info("Kaleidoscope: исход после «Отправить» = капча снята");
            return AfterSubmit.GONE;
        }
        Frame current = findKaleidoscopeFrame(page).orElse(frame);
        if (isAdditionalVerification(current)) {
            log.info("Kaleidoscope: исход после «Отправить» = дополнительная проверка");
            return AfterSubmit.ADDITIONAL;
        }
        if (isRetryLabelVisible(current) && sliderAtStart(current)) {
            log.info("Kaleidoscope: исход после «Отправить» = пазл сброшен");
            return AfterSubmit.RESET;
        }
        log.info("Kaleidoscope: исход после «Отправить» = пазл остался");
        return AfterSubmit.STUCK;
    }

    /**
     * Следующий раунд не должен взять task и картинку уже отправленного кадра.
     * Индексы ставятся на размер захвата до «Отправить». Если после отправки пришла только
     * картинка, {@code taskUseFrom} не сбрасывается: пустой task не подменяется первым пазлом.
     * Если не пришло ничего, task снова можно взять с начала, а старую картинку — нет.
     */
    private void armFreshPuzzle(Page page, int tasksBefore, int payloadsBefore, int imagesBefore,
                                NetworkCaptureSession session) {
        drainPendingChecks(session);
        taskUseFrom = tasksBefore;
        payloadUseFrom = payloadsBefore;
        imageUseFrom = imagesBefore;
        long deadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadline && !captureGrew(page, tasksBefore, imagesBefore)) {
            drainPendingChecks(session);
            if (captureGrew(page, tasksBefore, imagesBefore)) {
                break;
            }
            sleepQuiet(300);
        }
        boolean newTask = captureSize(page, CaptureSlot.TASKS) > tasksBefore;
        boolean newImage = captureSize(page, CaptureSlot.IMAGES) > imagesBefore;
        if (!newTask && newImage) {
            log.info("Kaleidoscope: для доп. проверки картинка новая, task ещё ждём (не берём первый пазл)");
            return;
        }
        if (!newTask) {
            log.info("Kaleidoscope: новый task после «Отправить» не пришёл — снимем canvas, старую картинку не шлём");
            taskUseFrom = 0;
            payloadUseFrom = Math.max(payloadsBefore, captureSize(page, CaptureSlot.PAYLOADS));
        } else {
            log.info("Kaleidoscope: для доп. проверки берём task/image после индекса tasks={} images={}",
                    tasksBefore, imagesBefore);
        }
    }

    private void waitUntilAdditionalPuzzleReady(Frame frame, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (sliderAtStart(frame)) {
                log.info("Kaleidoscope: доп. проверка, ползунок в начале");
                return;
            }
            sleepQuiet(200);
        }
        log.info("Kaleidoscope: доп. проверка — ползунок не в нуле, решаем кадр как есть");
    }

    private boolean isAdditionalVerification(Frame frame) {
        try {
            Locator retry = frame.locator("[data-testid='retry-label']");
            if (retry.count() == 0 || !retry.first().isVisible()) {
                return false;
            }
            String text = retry.first().innerText(new Locator.InnerTextOptions().setTimeout(800));
            return text != null && text.contains("Дополнительная проверка");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sliderAtStart(Frame frame) {
        try {
            Locator slider = frame.locator("#captcha-slider").first();
            if (slider.count() == 0) {
                return false;
            }
            String now = slider.getAttribute("aria-valuenow",
                    new Locator.GetAttributeOptions().setTimeout(800));
            if (now == null || now.isBlank()) {
                return false;
            }
            return Integer.parseInt(now.trim()) <= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRetryLabelVisible(Frame frame) {
        try {
            Locator retry = frame.locator("[data-testid='retry-label']");
            return retry.count() > 0 && retry.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickRetryIfPresent(Frame frame) {
        try {
            Locator retry = frame.locator(
                    "button[data-testid='refresh'], button:has-text('Попробуйте'), .AdvancedCaptcha-RetryButton");
            if (retry.count() > 0) {
                retry.first().click(new Locator.ClickOptions().setTimeout(5000));
                return true;
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: retry click: {}", e.getMessage());
        }
        return false;
    }

    /** Не читать body: бинарный voice/mp3 в onResponse ломает поток solve (re-entrant deadlock). */
    private static boolean isSmartCaptchaBinaryVoiceResponse(String url, String contentType) {
        if (url != null) {
            String lower = url.toLowerCase();
            if (lower.contains("load-captcha-voice")
                    || lower.contains("voice?key=")
                    || lower.contains("/voice?")) {
                return true;
            }
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("audio/")
                    || ct.contains("mpeg")
                    || ct.contains("octet-stream")) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record BoundingBoxSafe(double x, double y, double width, double height) {
        boolean onScreen() {
            return width > 2 && height > 2 && x > -500 && y > -500;
        }

        boolean isKaleidoscopeSize() {
            return width >= MIN_KALEIDOSCOPE_CANVAS_W && height >= MIN_KALEIDOSCOPE_CANVAS_H;
        }
    }

    private static BoundingBoxSafe boundingBox(Locator locator) {
        try {
            var box = locator.boundingBox();
            if (box == null) {
                return null;
            }
            return new BoundingBoxSafe(box.x, box.y, box.width, box.height);
        } catch (Exception e) {
            return null;
        }
    }

    /** Пауза без page.waitForTimeout — иначе deadlock с onResponse при активном capture. */
    private static void sleepQuiet(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
