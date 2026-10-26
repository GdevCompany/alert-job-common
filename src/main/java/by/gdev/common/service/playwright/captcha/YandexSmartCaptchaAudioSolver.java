package by.gdev.common.service.playwright.captcha;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Yandex SmartCaptcha audio challenge → 2Captcha {@link TwoCaptchaClient#solveAudioCaptcha}.
 * <p>
 * На калейдоскопе это запасной путь: Pazl вернул {@code solution=0}, либо пазла на экране уже нет
 * и основной solve не закрыл виджет. Пока слайдер виден и ответ Pazl просто не получен,
 * тип задания на аудио не переключается. Voice URL запоминается в {@code onResponse};
 * mp3 скачивается на потоке solve. {@code request} внутри слушателя ответов дедлочит страницу так же,
 * как чтение тела {@code /check}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexSmartCaptchaAudioSolver {

    private static final Pattern MP3_URL = Pattern.compile("\\.mp3([?#]|$)", Pattern.CASE_INSENSITIVE);

    private static final String[] ADVANCED_IFRAME_SELECTORS = {
            "iframe[data-testid='advanced-iframe']",
            "iframe[src*='smartcaptcha'][src*='advanced']",
    };

    /** Кнопка «Изменить тип задания» (DOM YouDo advanced iframe). */
    private static final String[] CHALLENGE_TYPE_SELECTORS = {
            "button[aria-describedby='change-challenge-tooltip'][data-testid='challenge-type'][aria-label='Изменить тип задания']",
            "button[data-testid='challenge-type'][aria-label='Изменить тип задания']",
            "button[aria-describedby='change-challenge-tooltip'][data-testid='challenge-type']",
            "button.CaptchaButton.CaptchaButton_view_clear.CaptchaButton_size_l.CaptchaButton_centered.ActionButtons-ActionButton[data-testid='challenge-type']",
            "button.CaptchaButton.CaptchaButton_view_clear.CaptchaButton_size_l.ActionButtons-ActionButton[data-testid='challenge-type']",
            "button.CaptchaButton.ActionButtons-ActionButton[data-testid='challenge-type']",
            "button.ActionButtons-ActionButton[data-testid='challenge-type']",
            "button[data-testid='challenge-type']",
    };

    private static final String[] PLAY_SELECTORS = {
            "button[data-testid='play'].AdvancedCaptcha_audio-button",
            "button[data-testid='play'][aria-label*='Прослушать']",
            "[data-testid='play']",
            "button[aria-label*='Прослушать']",
            "button[aria-label*='Воспроизвести']",
            "button[aria-label*='Play']",
            ".AdvancedCaptcha_audio-button",
            ".AdvancedCaptcha-Button_type_play",
            ".AdvancedCaptcha_audio button"
    };

    /** Поле «Например, 1234» в advanced iframe (DOM YouDo). */
    private static final String REP_INPUT_SELECTOR =
            "input.Textinput-Control[name='rep'][inputmode='numeric'][placeholder*='1234']";

    private static final String REP_INPUT_FALLBACK =
            "input.Textinput-Control[name='rep'][inputmode='numeric'], input[name='rep'][inputmode='numeric']";

    private static final String[] ANSWER_INPUT_SELECTORS = {
            REP_INPUT_SELECTOR,
            REP_INPUT_FALLBACK,
            "input.Textinput-Control[name='rep']",
            "input[name='rep'][inputmode='numeric']",
            "input[name='rep']",
    };

    private static final String SUBMIT_SELECTOR = "button[data-testid='submit']";

    private static final String[] SUBMIT_SELECTORS = {
            SUBMIT_SELECTOR,
    };

    /** Видимый challenge (калейдоскоп canvas / слайдер / аудио-форма). */
    private static final String ACTIVE_CHALLENGE_LOCATOR =
            ".AdvancedCaptcha-KaleidoscopeCanvas:visible, #captcha-slider:visible, "
                    + "input[name='rep']:visible, button[data-testid='play']:visible";

    private final TwoCaptchaClient twoCaptchaClient;

    public boolean isAudioChallengeVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (Frame f : page.frames()) {
                if (!isSmartCaptchaFrame(f)) {
                    continue;
                }
                if (isAudioFormVisibleInFrame(f)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: visibility check: {}", e.getMessage());
        }
        return false;
    }

    /** Аудио-режим: «Прослушайте и введите четыре цифры» + play (класс kaleidoscope на корне может остаться). */
    private static boolean isAudioFormVisibleInFrame(Frame frame) {
        try {
            Locator rep = frame.locator(
                    "input.Textinput-Control[name='rep'], input[name='rep'][inputmode='numeric'], input[name='rep']");
            if (rep.count() > 0 && isVisibleQuick(rep.first())) {
                return true;
            }
            Locator play = frame.locator("button[data-testid='play']");
            return play.count() > 0 && isVisibleQuick(play.first());
        } catch (Exception e) {
            return false;
        }
    }

    /** Калейдоскоп/слайдер. Если видна audio-форма (rep/play) — это уже не калейдоскоп. */
    private static boolean isKaleidoscopeChallengeInFrame(Frame frame) {
        if (isDedicatedAudioUiVisibleInFrame(frame)) {
            return false;
        }
        try {
            Locator canvas = frame.locator(
                    ".AdvancedCaptcha-KaleidoscopeCanvas, canvas.AdvancedCaptcha-KaleidoscopeCanvas");
            if (canvas.count() > 0 && isVisibleQuick(canvas.first())) {
                return true;
            }
            Locator slider = frame.locator("#captcha-slider");
            return slider.count() > 0 && isVisibleQuick(slider.first());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDedicatedAudioChallenge(Frame frame) {
        return isDedicatedAudioUiVisibleInFrame(frame);
    }

    private static boolean isVisibleQuick(Locator locator) {
        try {
            return locator.isVisible(new Locator.IsVisibleOptions().setTimeout(UI_PROBE_TIMEOUT_MS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Переключиться на аудио (если нужно), скачать mp3, распознать через 2Captcha, ввести ответ.
     */
    public boolean solve(Page page) {
        return solve(page, List.of());
    }

    /** Короткий таймаут для проверок UI в iframe (дефолт Playwright ~30s — зависает на калейдоскопе). */
    private static final int UI_PROBE_TIMEOUT_MS = 500;

    /**
     * @param seedVoiceUrls например {@code voice} из ответа {@code /check} до перехвата сети
     */
    public boolean solve(Page page, List<String> seedVoiceUrls) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("SmartCaptcha audio: 2Captcha не настроен");
            return false;
        }

        List<String> capturedUrls = new CopyOnWriteArrayList<>();
        if (seedVoiceUrls != null) {
            for (String u : seedVoiceUrls) {
                if (u != null && !u.isBlank()) {
                    capturedUrls.add(u);
                }
            }
        }
        AtomicReference<byte[]> mp3FromIntercept = new AtomicReference<>();
        Consumer<Response> listener = captureAudioUrls(page, capturedUrls, mp3FromIntercept);
        boolean listenerAttached = false;
        try {
            for (int round = 1; round <= 2; round++) {
                log.info("SmartCaptcha audio: раунд {}/2 (шаги 5–12)", round);
                if (round > 1) {
                    capturedUrls.clear();
                    mp3FromIntercept.set(null);
                }
                if (!listenerAttached) {
                    page.context().onResponse(listener);
                    listenerAttached = true;
                }
                Optional<Frame> frameOpt = findAdvancedFrame(page);
                if (frameOpt.isEmpty()) {
                    log.warn("SmartCaptcha audio: advanced frame не найден (round={})", round);
                    sleepQuiet(800);
                    continue;
                }
                Frame frame = frameOpt.get();
                log.info("SmartCaptcha audio: frame url={}", truncate(safeUrl(frame), 100));
                expandVoiceUrlsInSink(capturedUrls);
                boolean kaleidoscope = isKaleidoscopeChallengeInFrame(frame);
                if (kaleidoscope && !capturedUrls.isEmpty()) {
                    log.info(
                            "SmartCaptcha audio: калейдоскоп — очищаем {} seed URL (нужен challenge-type)",
                            capturedUrls.size());
                    capturedUrls.clear();
                    mp3FromIntercept.set(null);
                }
                log.info("SmartCaptcha audio: voice urls (seed): {}", voiceUrlsPreview(capturedUrls));

                boolean dedicatedAudio = isDedicatedAudioChallenge(frame);
                // [5/12]–[7/12]: challenge-type → voice URL → download mp3 (listener ON)
                byte[] mp3 = runStrictAudioChain(page, frame, capturedUrls, mp3FromIntercept,
                        kaleidoscope, dedicatedAudio, round);
                if (mp3 == null) {
                    continue;
                }

                // Снять onResponse ДО 2Captcha и ввода в rep — иначе submit/locator = deadlock
                if (listenerAttached) {
                    try {
                        page.context().offResponse(listener);
                    } catch (Exception e) {
                        log.debug("SmartCaptcha audio: offResponse before 2captcha: {}", e.getMessage());
                    }
                    listenerAttached = false;
                    log.info("SmartCaptcha audio: onResponse снят перед 2Captcha/submit");
                }

                log.info("SmartCaptcha audio: [8/12] Create AudioTask, mp3={} bytes", mp3.length);
                log.info("SmartCaptcha audio: [9/12] Send AudioTask → api.2captcha.com");
                Optional<TwoCaptchaClient.AudioResult> audioResult =
                        twoCaptchaClient.solveAudioCaptcha(mp3, "ru");
                if (audioResult.isEmpty()) {
                    log.error("SmartCaptcha audio: [10/12] ПРОВАЛ — нет ответа 2Captcha (round={})",
                            round);
                    continue;
                }
                String rawCaptcha = audioResult.get().text();
                log.info("SmartCaptcha audio: [10/12] Wait answer OK (raw): «{}»", rawCaptcha);
                String answer = digitsFromApiResponse(rawCaptcha);
                if (!isFourDigitCode(answer)) {
                    String digitsInRaw = rawCaptcha == null ? "" : rawCaptcha.replaceAll("\\D", "");
                    log.warn(
                            "SmartCaptcha audio: [11/12] нужно ровно 4 цифры, API digits='{}' "
                                    + "(raw digits len={}) — в rep НЕ вводим, reportIncorrect",
                            answer.isEmpty() ? "—" : answer,
                            digitsInRaw.length());
                    twoCaptchaClient.reportIncorrect(audioResult.get().taskId());
                    continue;
                }
                log.info("SmartCaptcha audio: [11/12] Validate 4 digits «{}» → input[name=rep]",
                        answer);

                if (!isAudioFormVisibleInFrameEvaluate(frame)) {
                    log.warn("SmartCaptcha audio: после 2Captcha нет поля rep — ждём UI");
                    sleepQuiet(1_500);
                }
                log.info("SmartCaptcha audio: [12/12] Click submit (button[data-testid=submit])");
                if (!submitAnswer(frame, page, answer)) {
                    log.warn("SmartCaptcha audio: [12/12] ПРОВАЛ — ввод/submit (rep visible={})",
                            isAudioFormVisibleInFrame(frame));
                    continue;
                }
                sleepQuiet(2500);
                if (isChallengeGone(page)) {
                    log.info("SmartCaptcha audio: challenge снят после ответа «{}»", maskAnswer(answer));
                    return true;
                }
                log.warn("SmartCaptcha audio: ответ отправлен, challenge остался (round={})", round);
            }
            // Не использовать isChallengeGone как «успех» после провала раундов:
            // в DOM может лежать чужой smart-token от YandexSmartCaptchaTask при живом challenge.
            log.warn("SmartCaptcha audio: оба раунда без успеха");
            return false;
        } finally {
            if (listenerAttached) {
                try {
                    page.context().offResponse(listener);
                } catch (Exception e) {
                    log.debug("SmartCaptcha audio: offResponse: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Строго по шагам: 1) аудио + voice URL → 2) mp3 на диске в памяти → только потом 3/4/5 в solve().
     * Следующий шаг не начинается, пока предыдущий не OK.
     */
    private byte[] runStrictAudioChain(Page page, Frame frame, List<String> capturedUrls,
            AtomicReference<byte[]> mp3FromIntercept, boolean kaleidoscope, boolean dedicatedAudio,
            int round) {
        if (!step1SwitchToAudio(page, frame, capturedUrls, kaleidoscope, dedicatedAudio, round)) {
            return null;
        }
        return step2DownloadMp3(page, frame, capturedUrls, mp3FromIntercept, round);
    }

    private boolean step1SwitchToAudio(Page page, Frame frame, List<String> capturedUrls,
            boolean kaleidoscope, boolean dedicatedAudio, int round) {
        expandVoiceUrlsInSink(capturedUrls);
        // Пересчитываем UI: после прошлого раунда canvas может остаться в DOM, а форма уже audio
        boolean audioUi = isDedicatedAudioUiVisibleInFrame(frame);
        boolean needSwitch = kaleidoscope && !audioUi;
        log.info("SmartCaptcha audio: [5/12] audio UI (needSwitch={}, kaleidoscope={}, dedicated={})",
                needSwitch, kaleidoscope, dedicatedAudio || audioUi);
        if (hasUsableVoiceUrl(capturedUrls)) {
            log.info("SmartCaptcha audio: [6/12] Detect voice URL уже есть: {}",
                    voiceUrlsPreview(capturedUrls));
            return true;
        }
        if (!ensureVoiceUrlForAudio(page, frame, capturedUrls, needSwitch)) {
            log.error("SmartCaptcha audio: [5–6/12] ПРОВАЛ — нет voice URL (round={})", round);
            return false;
        }
        log.info("SmartCaptcha audio: [6/12] Detect voice OK: {}", voiceUrlsPreview(capturedUrls));
        return true;
    }

    private byte[] step2DownloadMp3(Page page, Frame frame, List<String> capturedUrls,
            AtomicReference<byte[]> mp3FromIntercept, int round) {
        log.info("SmartCaptcha audio: [7/12] Download mp3 по voice URL");
        if (!hasUsableVoiceUrl(capturedUrls)) {
            log.error("SmartCaptcha audio: [7/12] ПРОВАЛ — нет URL (round={})", round);
            return null;
        }
        byte[] mp3 = resolveMp3WithRetries(page, frame, capturedUrls, mp3FromIntercept, 3);
        if (mp3 == null || mp3.length < 200) {
            log.error("SmartCaptcha audio: [7/12] ПРОВАЛ — mp3 не скачан (round={})", round);
            return null;
        }
        if (mp3.length > 1_000_000) {
            log.error("SmartCaptcha audio: [7/12] ПРОВАЛ — mp3 слишком большой {} bytes", mp3.length);
            return null;
        }
        log.info("SmartCaptcha audio: [7/12] OK — mp3 {} bytes", mp3.length);
        return mp3;
    }

    private byte[] tryDownloadMp3(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept) {
        return takeInterceptedOrDownload(page, frame, urlSink, mp3FromIntercept);
    }

    private byte[] resolveMp3WithRetries(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            byte[] mp3 = takeInterceptedOrDownload(page, frame, urlSink, mp3FromIntercept);
            if (mp3 != null && mp3.length >= 200) {
                return mp3;
            }
            log.warn("SmartCaptcha audio: mp3 попытка {}/{} — нет файла, urls={}",
                    attempt, maxAttempts, voiceUrlsPreview(urlSink));
            if (attempt < maxAttempts) {
                tryClickPlayQuick(frame, page);
                briefPauseForVoiceUrl(urlSink, 1_200);
            }
        }
        return null;
    }

    private byte[] takeInterceptedOrDownload(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept) {
        expandVoiceUrlsInSink(urlSink);
        byte[] cached = mp3FromIntercept.get();
        if (cached != null && cached.length >= 200) {
            log.info("SmartCaptcha audio: mp3 уже в буфере, {} bytes", cached.length);
            return cached;
        }
        byte[] downloaded = downloadVoiceByUrl(page, frame, urlSink);
        if (downloaded != null && downloaded.length >= 200) {
            mp3FromIntercept.compareAndSet(null, downloaded);
        }
        return downloaded;
    }

    /** Скачать mp3 по ссылке voice из /check или перехвата (без циклов ожидания UI). */
    private byte[] downloadVoiceByUrl(Page page, Frame frame, List<String> urlSink) {
        expandVoiceUrlsInSink(urlSink);
        if (pickBestVoiceUrl(urlSink).isEmpty()) {
            log.warn("SmartCaptcha audio: нет voice URL для скачивания");
            return null;
        }
        for (String url : voiceUrlsOrdered(urlSink)) {
            log.info("SmartCaptcha audio: скачивание по ссылке {}", truncate(url, 120));
            Optional<byte[]> bytes = downloadVoiceBytes(page, frame, url);
            if (bytes.isPresent() && bytes.get().length >= 200) {
                log.info("SmartCaptcha audio: mp3 скачан, {} bytes", bytes.get().length);
                return bytes.get();
            }
        }
        log.warn("SmartCaptcha audio: ни одна ссылка не дала mp3, urls={}", voiceUrlsPreview(urlSink));
        return null;
    }

    /** Добавить распакованный voice?key= из load-captcha-voice?base64. */
    private static void expandVoiceUrlsInSink(List<String> urlSink) {
        if (urlSink == null || urlSink.isEmpty()) {
            return;
        }
        for (String url : new ArrayList<>(urlSink)) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String inner = unwrapLoadCaptchaVoiceUrl(url);
            if (inner != null && !inner.equals(url) && !urlSink.contains(inner)) {
                urlSink.add(inner);
                log.info("SmartCaptcha audio: voice URL распакован → {}", truncate(inner, 100));
            }
        }
    }

    private static String voiceUrlsPreview(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            if (n > 0) {
                sb.append(", ");
            }
            sb.append(truncate(u, 70));
            n++;
            if (n >= 4) {
                sb.append(", …");
                break;
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** Предпочитаем прямой voice?key=, не load-captcha-voice-обёртку. */
    private static java.util.List<String> voiceUrlsOrdered(List<String> urlSink) {
        java.util.List<String> direct = new ArrayList<>();
        java.util.List<String> other = new ArrayList<>();
        if (urlSink != null) {
            for (String url : urlSink) {
                if (url == null || url.isBlank() || isIntroOrStaticNoise(url.toLowerCase())) {
                    continue;
                }
                String resolved = unwrapLoadCaptchaVoiceUrl(url);
                if (resolved != null && !resolved.equals(url) && !urlSink.contains(resolved)) {
                    urlSink.add(resolved);
                }
                String use = resolved != null ? resolved : url;
                String lower = use.toLowerCase();
                if (lower.contains("voice?") || lower.contains("/voice?")) {
                    direct.add(use);
                } else if (lower.contains("/voice") || lower.contains("load-captcha-voice")) {
                    other.add(use);
                }
            }
        }
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.addAll(direct);
        ordered.addAll(other);
        return new ArrayList<>(ordered);
    }

    /** {@code load-captcha-voice?&lt;base64(real voice url)&gt;} из ответа /check. */
    private static String unwrapLoadCaptchaVoiceUrl(String url) {
        if (url == null || !url.toLowerCase().contains("load-captcha-voice")) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return url;
        }
        String payload = url.substring(q + 1);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            String inner = new String(decoded, StandardCharsets.UTF_8).trim();
            if (inner.startsWith("http") && inner.toLowerCase().contains("voice")) {
                return inner;
            }
        } catch (Exception ignored) {
            try {
                byte[] decoded = Base64.getDecoder().decode(payload);
                String inner = new String(decoded, StandardCharsets.UTF_8).trim();
                if (inner.startsWith("http") && inner.toLowerCase().contains("voice")) {
                    return inner;
                }
            } catch (Exception ignored2) {
                // keep original url
            }
        }
        return url;
    }

    private static Optional<String> pickBestVoiceUrl(List<String> urls) {
        java.util.List<String> ordered = voiceUrlsOrdered(urls);
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
    }

    private Optional<byte[]> downloadVoiceBytes(Page page, Frame frame, String url) {
        java.util.LinkedHashSet<String> tryUrls = new java.util.LinkedHashSet<>();
        String unwrapped = unwrapLoadCaptchaVoiceUrl(url);
        if (unwrapped != null && unwrapped.contains("voice")) {
            tryUrls.add(unwrapped);
        }
        tryUrls.add(url);
        for (String candidate : tryUrls) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Optional<byte[]> viaApi = fetchUrl(page, frame, candidate);
            if (viaApi.isPresent()) {
                return viaApi;
            }
            if (candidate.contains("voice?key=")) {
                Optional<byte[]> viaBrowser = fetchVoiceInBrowser(frame, candidate);
                if (viaBrowser.isPresent()) {
                    return viaBrowser;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * fetch() внутри iframe advanced — cookies сессии SmartCaptcha (иначе voice 403/пусто).
     */
    private Optional<byte[]> fetchVoiceInBrowser(Frame frame, String url) {
        try {
            Object raw = frame.evaluate(
                    """
                            async (voiceUrl) => {
                              const ctrl = new AbortController();
                              const t = setTimeout(() => ctrl.abort(), 12000);
                              let r;
                              try {
                                r = await fetch(voiceUrl, {
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
            Object ok = map.get("ok");
            Object status = map.get("status");
            Object len = map.get("len");
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("SmartCaptcha audio: fetch в iframe status={} url={}",
                        status, truncate(url, 90));
                return Optional.empty();
            }
            Object b64Obj = map.get("b64");
            if (!(b64Obj instanceof String b64) || b64.isEmpty()) {
                log.warn("SmartCaptcha audio: fetch в iframe пустой body len={} url={}",
                        len, truncate(url, 90));
                return Optional.empty();
            }
            byte[] body = Base64.getDecoder().decode(b64);
            if (body.length < 200) {
                log.warn("SmartCaptcha audio: fetch в iframe слишком маленький {} bytes", body.length);
                return Optional.empty();
            }
            log.info("SmartCaptcha audio: fetch в iframe ok, {} bytes, status={}", body.length, status);
            return Optional.of(body);
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: fetch в iframe {}: {}", truncate(url, 80), e.getMessage());
            return Optional.empty();
        }
    }

    private static String maskAnswer(String answer) {
        if (answer == null || answer.length() <= 2) {
            return "***";
        }
        return answer.charAt(0) + "***" + answer.charAt(answer.length() - 1);
    }

    /** Ответ AudioTask: только цифры из текста API (слова/фразы не разбираем). */
    private static String digitsFromApiResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.matches("\\d{4}")) {
            return trimmed;
        }
        String digitsOnly = trimmed.replaceAll("\\D", "");
        if (digitsOnly.length() >= 4) {
            return digitsOnly.substring(0, 4);
        }
        return "";
    }

    private static boolean isFourDigitCode(String answer) {
        return answer != null && answer.matches("\\d{4}");
    }

    private static boolean hasDirectVoiceKeyUrl(List<String> urlSink) {
        if (urlSink == null) {
            return false;
        }
        for (String url : urlSink) {
            if (url != null && url.contains("voice?key=")) {
                return true;
            }
        }
        return false;
    }

    private boolean isChallengeGone(Page page) {
        try {
            // Сначала UI: чужой smart-token в input НЕ значит, что challenge снят
            for (Frame f : page.frames()) {
                if (!isSmartCaptchaFrame(f)) {
                    continue;
                }
                if (isDedicatedAudioUiVisibleInFrame(f) || isKaleidoscopeChallengeInFrame(f)) {
                    return false;
                }
                try {
                    Locator challenge = f.locator(ACTIVE_CHALLENGE_LOCATOR);
                    if (challenge.count() > 0 && isVisibleQuick(challenge.first())) {
                        return false;
                    }
                } catch (Exception ignored) {
                    // next frame
                }
            }
            if (isAudioChallengeVisible(page)) {
                return false;
            }
            Object len = page.evaluate(
                    """
                            () => {
                              const t = document.querySelector(
                                'input[name="smart-token"], input[data-testid="smart-token"], input[name="smart_token"]');
                              return t && t.value ? t.value.length : 0;
                            }
                            """);
            return len instanceof Number n && n.intValue() > 20;
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Только URL в sink. Скачивание — на потоке solve ({@link #takeInterceptedOrDownload}):
     * {@code fetchUrl}/{@code request().get()} внутри onResponse даёт deadlock с {@code waitForTimeout}.
     */
    private Consumer<Response> captureAudioUrls(Page page, List<String> sink,
            AtomicReference<byte[]> mp3FromIntercept) {
        return response -> {
            try {
                String url = response.url();
                if (!isCapturableAudioUrl(url, response.headers().get("content-type"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 400) {
                    return;
                }
                String direct = unwrapLoadCaptchaVoiceUrl(url);
                String downloadUrl = direct != null && direct.toLowerCase().contains("voice") ? direct : url;
                if (!isChallengeVoiceUrl(downloadUrl) && !downloadUrl.toLowerCase().contains("load-captcha-voice")) {
                    return;
                }
                if (!sink.contains(url)) {
                    sink.add(url);
                    log.info("SmartCaptcha audio: voice URL {}", truncate(url, 120));
                }
                if (direct != null && !direct.equals(url) && !sink.contains(direct)) {
                    sink.add(direct);
                    log.info("SmartCaptcha audio: voice URL (direct) {}", truncate(direct, 120));
                }
            } catch (Exception e) {
                log.warn("SmartCaptcha audio: перехват URL: {}", e.getMessage());
            }
        };
    }

    private static boolean isCapturableAudioUrl(String url, String contentType) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        if (!lower.contains("smartcaptcha") && !lower.contains("yandexcloud.net")
                && !lower.contains("captcha.yandex")) {
            return false;
        }
        if (isIntroOrStaticNoise(lower)) {
            return false;
        }
        if (lower.contains("/voice") || lower.contains("voice?")) {
            return true;
        }
        boolean audioCt = contentType != null && contentType.toLowerCase().contains("audio");
        return audioCt || MP3_URL.matcher(url).find() || lower.contains("/audio");
    }

    private static boolean isChallengeVoiceUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        if (isIntroOrStaticNoise(lower)) {
            return false;
        }
        return lower.contains("/voice") || lower.contains("voice?");
    }

    private static boolean isIntroOrStaticNoise(String urlLower) {
        return urlLower.contains("intro-ru")
                || urlLower.contains("intro-en")
                || urlLower.contains("voiceintro")
                || urlLower.contains("load-captcha-voiceintro")
                || urlLower.contains("/static/intro")
                || urlLower.contains("/static/");
    }

    private Optional<byte[]> resolveAudioBytes(Page page, Frame frame, List<String> urls) {
        for (String url : voiceUrlsOrdered(urls)) {
            Optional<byte[]> bytes = downloadVoiceBytes(page, frame, url);
            if (bytes.isPresent()) {
                return bytes;
            }
        }
        try {
            Object src = frame.evaluate(
                    """
                            () => {
                              const a = document.querySelector('audio');
                              if (a && a.src) return a.src;
                              const s = document.querySelector('source[type="audio/mpeg"], source[src*=".mp3"]');
                              if (s && s.src) return s.src;
                              return null;
                            }
                            """);
            if (src instanceof String url && !url.isBlank() && !isIntroOrStaticNoise(url.toLowerCase())) {
                return downloadVoiceBytes(page, frame, url);
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: audio[src]: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> pickBestAudioUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = new ArrayList<>();
        for (String url : urls) {
            if (url == null || isIntroOrStaticNoise(url.toLowerCase())) {
                continue;
            }
            candidates.add(url);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingInt((String u) -> scoreAudioUrl(u)).reversed());
        return Optional.of(candidates.get(0));
    }

    private static int scoreAudioUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("/voice") || lower.contains("voice?")) {
            return 100;
        }
        if (MP3_URL.matcher(url).find()) {
            return 50;
        }
        return 10;
    }

    private Optional<byte[]> fetchUrl(Page page, Frame frame, String url) {
        try {
            String referer = frame != null ? safeUrl(frame) : null;
            if (referer == null || referer.isBlank()) {
                referer = page.url();
            }
            var opts = RequestOptions.create().setTimeout(12_000);
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
            if (status >= 200 && status < 300 && body != null && len >= 200 && len <= 1_000_000) {
                log.info("SmartCaptcha audio: APIRequest скачано {} bytes (status={}) {}", len, status,
                        truncate(url, 90));
                return Optional.of(body);
            }
            log.warn("SmartCaptcha audio: APIRequest неудача status={} len={} url={}",
                    status, len, truncate(url, 90));
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: APIRequest {}: {}", truncate(url, 80), e.getMessage());
        }
        return Optional.empty();
    }

    /** Калейдоскоп → форма rep/play (для ввода ответа). Без Playwright click — он зависает на navigation. */
    private void ensureAudioChallengeUi(Frame frame, Page page, List<String> capturedUrls) {
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-форма уже на экране (rep/play)");
            return;
        }
        switchToAudioDomOnly(frame, page, List.of());
        sleepQuiet(500);
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-режим включён (rep/play видны)");
        } else {
            log.warn("SmartCaptcha audio: rep/play ещё нет — повтор DOM-клик «Аудио»");
            switchToAudioDomOnly(frame, page, List.of());
            sleepQuiet(600);
        }
    }

    private void switchToAudioDomOnly(Frame frame, Page page, List<String> capturedUrls) {
        if (isAudioFormVisibleInFrame(frame)) {
            return;
        }
        ensureChallengeTypeVoiceUrl(page, frame, capturedUrls, isKaleidoscopeChallengeInFrame(frame));
        sleepQuiet(350);
    }

    /**
     * Получить voice URL: при калейдоскопе — один клик challenge-type; при audio UI — play + DOM src.
     * Не кликать challenge-type повторно, если audio-форма уже видна (иначе переключает тип обратно).
     */
    private boolean ensureVoiceUrlForAudio(Page page, Frame frame, List<String> capturedUrls,
            boolean needSwitchFromKaleidoscope) {
        expandVoiceUrlsInSink(capturedUrls);
        if (hasUsableVoiceUrl(capturedUrls)) {
            return true;
        }
        harvestVoiceUrlFromDom(frame, capturedUrls);
        if (hasUsableVoiceUrl(capturedUrls)) {
            return true;
        }

        if (needSwitchFromKaleidoscope || !isDedicatedAudioUiVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: калейдоскоп — один клик challenge-type");
            boolean clicked = clickChallengeTypeOnPage(page);
            if (!clicked) {
                logChallengeTypeProbe(page);
            }
            // Ждём либо voice в сети, либо появление audio UI (без повторных кликов)
            if (waitForVoiceOrAudioUi(page, frame, capturedUrls, 8_000)) {
                if (hasUsableVoiceUrl(capturedUrls)) {
                    return true;
                }
            }
        }

        // Audio UI: refresh (новый трек) → play → DOM/Performance harvest
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (hasUsableVoiceUrl(capturedUrls)) {
                return true;
            }
            Frame live = findAdvancedFrame(page).orElse(frame);
            if (!isDedicatedAudioUiVisibleInFrame(live)) {
                log.info("SmartCaptcha audio: audio UI нет — challenge-type ещё раз (попытка {}/3)", attempt);
                clickChallengeTypeOnPage(page);
                waitForVoiceOrAudioUi(page, live, capturedUrls, 5_000);
                live = findAdvancedFrame(page).orElse(live);
            }
            if (isDedicatedAudioUiVisibleInFrame(live)) {
                // play без refresh часто не даёт новый network request (тот же mp3 в кэше)
                if (attempt > 1 || needSwitchFromKaleidoscope == false) {
                    log.info("SmartCaptcha audio: audio UI — refresh перед play (попытка {}/3)", attempt);
                    tryClickRefreshQuick(live);
                    sleepQuiet(400);
                }
                log.info("SmartCaptcha audio: audio UI — play + harvest (попытка {}/3)", attempt);
                tryClickPlayQuick(live, page);
                harvestVoiceUrlFromDom(live, capturedUrls);
                if (waitForVoiceInSink(capturedUrls, 4_000)) {
                    return true;
                }
            }
        }
        return hasUsableVoiceUrl(capturedUrls);
    }

    /** Voice URL из {@code <audio>} / Performance resource (voice?key=). */
    private static void harvestVoiceUrlFromDom(Frame frame, List<String> capturedUrls) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => {
                              const urls = [];
                              const push = (u) => {
                                if (u && typeof u === 'string' && u.length > 8 && !urls.includes(u)) urls.push(u);
                              };
                              const a = document.querySelector('audio');
                              if (a) {
                                push(a.currentSrc || a.src);
                                a.querySelectorAll('source').forEach(s => push(s.src));
                              }
                              document.querySelectorAll('source[src*="voice"], source[src*=".mp3"]').forEach(s => push(s.src));
                              try {
                                performance.getEntriesByType('resource').forEach((e) => {
                                  const n = e && e.name ? String(e.name) : '';
                                  if (/voice|mp3|load-captcha-voice/i.test(n)
                                      && !/voiceintro|intro-ru|intro-en|\\/static\\//i.test(n)) {
                                    push(n);
                                  }
                                });
                              } catch (err) {}
                              return urls;
                            }
                            """);
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) {
                        continue;
                    }
                    String u = String.valueOf(o);
                    if (u.isBlank() || capturedUrls.contains(u)) {
                        continue;
                    }
                    if (isIntroOrStaticNoise(u.toLowerCase())) {
                        continue;
                    }
                    capturedUrls.add(u);
                    log.info("SmartCaptcha audio: voice из DOM {}", truncate(u, 100));
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: harvest DOM: {}", e.getMessage());
        }
        expandVoiceUrlsInSink(capturedUrls);
    }

    private boolean waitForVoiceOrAudioUi(Page page, Frame frame, List<String> capturedUrls, int maxWaitMs) {
        int step = 100;
        for (int waited = 0; waited < maxWaitMs; waited += step) {
            expandVoiceUrlsInSink(capturedUrls);
            harvestVoiceUrlFromDom(findAdvancedFrame(page).orElse(frame), capturedUrls);
            if (hasUsableVoiceUrl(capturedUrls)) {
                log.info("SmartCaptcha audio: voice URL в sink за {} ms", waited);
                return true;
            }
            Frame live = findAdvancedFrame(page).orElse(frame);
            if (isDedicatedAudioUiVisibleInFrame(live)) {
                log.info("SmartCaptcha audio: audio UI появился за {} ms", waited);
                return true;
            }
            sleepQuiet(step);
        }
        return hasUsableVoiceUrl(capturedUrls) || isDedicatedAudioUiVisibleInFrame(
                findAdvancedFrame(page).orElse(frame));
    }

    /** @deprecated use {@link #ensureVoiceUrlForAudio} */
    private boolean ensureChallengeTypeVoiceUrl(Page page, Frame frame, List<String> capturedUrls,
            boolean kaleidoscope) {
        return ensureVoiceUrlForAudio(page, frame, capturedUrls, kaleidoscope);
    }

    /** Клик challenge-type только в advanced-iframe (кнопки там нет в backend.*). */
    private static boolean clickChallengeTypeOnPage(Page page) {
        Frame advanced = null;
        for (Frame f : page.frames()) {
            if (!isAdvancedCaptchaFrame(f)) {
                continue;
            }
            advanced = f;
            if (clickChallengeTypeInFrame(f)) {
                return true;
            }
        }
        if (advanced != null) {
            log.warn("SmartCaptcha audio: challenge-type не нажата в advanced iframe");
            return false;
        }
        for (Frame f : page.frames()) {
            if (!isSmartCaptchaFrame(f) || isBackendCaptchaFrame(f)) {
                continue;
            }
            if (clickChallengeTypeInFrame(f)) {
                return true;
            }
        }
        log.warn("SmartCaptcha audio: challenge-type — advanced iframe не найден на странице");
        return false;
    }

    private static boolean isAdvancedCaptchaFrame(Frame f) {
        String url = safeUrl(f);
        return url.contains("smartcaptcha") && url.contains("/advanced");
    }

    private static boolean isBackendCaptchaFrame(Frame f) {
        String url = safeUrl(f);
        return url.contains("smartcaptcha") && url.contains("/backend");
    }

    private static void logChallengeTypeProbe(Page page) {
        for (Frame f : page.frames()) {
            if (!isSmartCaptchaFrame(f)) {
                continue;
            }
            try {
                Object probe = f.evaluate(
                        """
                                () => {
                                  const btn = document.querySelector('button[data-testid="challenge-type"]');
                                  return {
                                    href: (location.href || '').slice(0, 100),
                                    hasChallengeType: !!btn,
                                    ariaLabel: btn ? (btn.getAttribute('aria-label') || '') : '',
                                    describedBy: btn ? (btn.getAttribute('aria-describedby') || '') : ''
                                  };
                                }
                                """);
                log.info("SmartCaptcha audio: probe frame challenge-type → {}", probe);
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: probe frame: {}", e.getMessage());
            }
        }
    }

    /** Аудио-режим: rep/play видны и калейдоскоп/слайдер не перекрывают challenge. */
    private static boolean isDedicatedAudioUiVisibleInFrame(Frame frame) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => {
                              const visible = (el) => {
                                if (!el) return false;
                                const r = el.getBoundingClientRect();
                                if (r.width < 2 || r.height < 2) return false;
                                const st = getComputedStyle(el);
                                return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
                              };
                              const kaleido = document.querySelector(
                                '.AdvancedCaptcha-KaleidoscopeCanvas, canvas.AdvancedCaptcha-KaleidoscopeCanvas, #captcha-slider');
                              if (visible(kaleido)) return false;
                              const rep = document.querySelector('input[name="rep"]');
                              const play = document.querySelector('button[data-testid="play"]');
                              return visible(rep) || visible(play);
                            }
                            """);
            return Boolean.TRUE.equals(raw);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAudioFormVisibleInFrameEvaluate(Frame frame) {
        return isDedicatedAudioUiVisibleInFrame(frame);
    }

    private static boolean hasUsableVoiceUrl(List<String> capturedUrls) {
        return hasDirectVoiceKeyUrl(capturedUrls) || pickBestVoiceUrl(capturedUrls).isPresent();
    }

    /** Пауза без page.waitForTimeout — иначе re-entrant onResponse во время клика вешает поток. */
    private boolean waitForVoiceInSink(List<String> capturedUrls, int maxWaitMs) {
        int step = 80;
        for (int waited = 0; waited < maxWaitMs; waited += step) {
            expandVoiceUrlsInSink(capturedUrls);
            if (hasUsableVoiceUrl(capturedUrls)) {
                log.info("SmartCaptcha audio: voice URL в sink за {} ms", waited);
                return true;
            }
            sleepQuiet(step);
        }
        return hasUsableVoiceUrl(capturedUrls);
    }

    private void briefPauseForVoiceUrl(List<String> capturedUrls, int maxWaitMs) {
        waitForVoiceInSink(capturedUrls, maxWaitMs);
    }

    /**
     * Полный клик внутри iframe (координаты + события + .click()) — без Playwright input API.
     */
    private static boolean clickChallengeTypeInFrame(Frame frame) {
        try {
            Object clicked = frame.evaluate(
                    """
                            () => {
                              const selectors = [
                                'button[aria-describedby="change-challenge-tooltip"][data-testid="challenge-type"][aria-label="Изменить тип задания"]',
                                'button[data-testid="challenge-type"][aria-label="Изменить тип задания"]',
                                'button.CaptchaButton.CaptchaButton_view_clear.ActionButtons-ActionButton[data-testid="challenge-type"]',
                                'button.ActionButtons-ActionButton[data-testid="challenge-type"]',
                                'button.CaptchaButton[data-testid="challenge-type"]',
                                'button[data-testid="challenge-type"]'
                              ];
                              let btn = null;
                              let matched = null;
                              for (const sel of selectors) {
                                btn = document.querySelector(sel);
                                if (btn) { matched = sel; break; }
                              }
                              if (!btn) return { ok: false, reason: 'no-button' };
                              const r = btn.getBoundingClientRect();
                              const cx = r.left + r.width / 2;
                              const cy = r.top + r.height / 2;
                              const opts = {
                                bubbles: true, cancelable: true, view: window,
                                clientX: cx, clientY: cy, buttons: 1, button: 0
                              };
                              btn.focus();
                              for (const type of [
                                'pointerover', 'mouseover', 'pointerenter', 'mouseenter',
                                'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'
                              ]) {
                                btn.dispatchEvent(new MouseEvent(type, opts));
                              }
                              if (typeof btn.click === 'function') {
                                btn.click();
                              }
                              return {
                                ok: true,
                                selector: matched,
                                label: (btn.getAttribute('aria-label') || '').trim().slice(0, 80)
                              };
                            }
                            """);
            if (clicked instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"))) {
                log.info(
                        "SmartCaptcha audio: challenge-type OK frame={} selector={} label={}",
                        truncate(safeUrl(frame), 72),
                        map.get("selector"),
                        map.get("label"));
                return true;
            }
            String reason = clicked instanceof java.util.Map<?, ?> m
                    ? String.valueOf(m.get("reason"))
                    : String.valueOf(clicked);
            log.debug(
                    "SmartCaptcha audio: challenge-type skip frame={} ({})",
                    truncate(safeUrl(frame), 72),
                    reason);
            return false;
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: challenge-type в iframe: {}", e.getMessage());
            return false;
        }
    }

    private static void sleepQuiet(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String buildCookieHeader(Page page, String targetUrl) {
        try {
            var cookies = page.context().cookies(targetUrl);
            if (cookies == null || cookies.isEmpty()) {
                cookies = page.context().cookies();
            }
            if (cookies == null || cookies.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (var c : cookies) {
                if (c.name == null || c.value == null) {
                    continue;
                }
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

    private void tryClickPlayQuick(Frame frame, Page page) {
        try {
            Object ok = frame.evaluate(
                    """
                            () => {
                              const btn = document.querySelector('button[data-testid="play"]')
                                || document.querySelector('button[aria-label*="Прослушать"]');
                              if (!btn) return false;
                              btn.click();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("SmartCaptcha audio: play (fallback) через evaluate");
                return;
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: play evaluate: {}", e.getMessage());
        }
        log.warn("SmartCaptcha audio: play (fallback) — кнопка не нажата");
    }

    /** Новый аудио-трек (после плохого ответа 2Captcha play alone не хватает). */
    private static void tryClickRefreshQuick(Frame frame) {
        try {
            Object ok = frame.evaluate(
                    """
                            () => {
                              const btn = document.querySelector('button[data-testid="refresh"]')
                                || document.querySelector('button[aria-label*="Обновить"]');
                              if (!btn) return false;
                              btn.click();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("SmartCaptcha audio: refresh через evaluate");
            } else {
                log.debug("SmartCaptcha audio: refresh — кнопки нет");
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: refresh evaluate: {}", e.getMessage());
        }
    }

    private boolean submitAnswer(Frame frame, Page page, String answer) {
        if (answer == null || answer.length() != 4) {
            log.warn("SmartCaptcha audio: submit пропущен — answer len={}", answer == null ? -1 : answer.length());
            return false;
        }
        Frame target = findAdvancedFrame(page).orElse(frame);
        log.info(
                "SmartCaptcha audio: [11–12/12] ввод «{}» в input[name=rep] + button[data-testid=submit] (frame={})",
                maskAnswer(answer),
                truncate(safeUrl(target), 72));
        // Prefer DOM evaluate first — no Playwright locator while page may still have other listeners
        if (trySubmitAnswerViaDom(target, page, answer)) {
            return true;
        }
        if (submitAnswerPlaywright(target, page, answer)) {
            return true;
        }
        if (submitAnswerViaPageIframe(page, answer)) {
            return true;
        }
        log.warn("SmartCaptcha audio: [12/12] ПРОВАЛ — rep/submit не найдены в advanced iframe");
        return false;
    }

    private boolean submitAnswerPlaywright(Frame frame, Page page, String answer) {
        for (String sel : ANSWER_INPUT_SELECTORS) {
            try {
                Locator input = frame.locator(sel).first();
                if (input.count() == 0 || !isVisibleQuick(input)) {
                    continue;
                }
                log.info("SmartCaptcha audio: ввод в видимое поле selector={}", sel);
                input.click(new Locator.ClickOptions().setTimeout(2_000).setNoWaitAfter(true));
                input.fill("");
                input.pressSequentially(answer, new Locator.PressSequentiallyOptions().setDelay(40));
                log.info("SmartCaptcha audio: введено 4 цифры в name=rep (Playwright)");
                return clickSubmitAfterAnswer(frame, page);
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: input {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private boolean submitAnswerViaPageIframe(Page page, String answer) {
        for (String iframeSel : ADVANCED_IFRAME_SELECTORS) {
            for (String sel : ANSWER_INPUT_SELECTORS) {
                try {
                    Locator input = page.frameLocator(iframeSel).locator(sel).first();
                    if (input.count() == 0 || !isVisibleQuick(input)) {
                        continue;
                    }
                    log.info("SmartCaptcha audio: ввод rep через frameLocator iframe={} sel={}", iframeSel, sel);
                    input.click(new Locator.ClickOptions().setTimeout(2_000).setNoWaitAfter(true));
                    input.fill("");
                    input.pressSequentially(answer, new Locator.PressSequentiallyOptions().setDelay(40));
                    sleepQuiet(200);
                    Locator submit = page.frameLocator(iframeSel).locator("button[data-testid='submit']").first();
                    if (submit.count() > 0 && isVisibleQuick(submit)) {
                        submit.click(new Locator.ClickOptions()
                                .setTimeout(2_000)
                                .setNoWaitAfter(true)
                                .setForce(true));
                        log.info("SmartCaptcha audio: submit через frameLocator");
                        return true;
                    }
                    input.press("Enter");
                    return true;
                } catch (Exception e) {
                    log.debug("SmartCaptcha audio: frameLocator rep {}: {}", sel, e.getMessage());
                }
            }
        }
        return false;
    }

    private boolean clickSubmitAfterAnswer(Frame frame, Page page) {
        for (String submitSel : SUBMIT_SELECTORS) {
            try {
                Locator submit = frame.locator(submitSel).first();
                if (submit.count() > 0 && isVisibleQuick(submit)) {
                    submit.click(new Locator.ClickOptions()
                            .setTimeout(2_000)
                            .setNoWaitAfter(true)
                            .setForce(true));
                    log.info("SmartCaptcha audio: submit {}", submitSel);
                    return true;
                }
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: submit {}: {}", submitSel, e.getMessage());
            }
        }
        try {
            Locator rep = frame.locator(
                    "input.Textinput-Control[name='rep']:visible, input[name='rep'][inputmode='numeric']:visible")
                    .first();
            if (rep.count() > 0 && isVisibleQuick(rep)) {
                rep.press("Enter");
                log.info("SmartCaptcha audio: submit через Enter на видимом rep");
                return true;
            }
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: submit не удался: {}", e.getMessage());
        }
        return false;
    }

    private boolean trySubmitAnswerViaDom(Frame frame, Page page, String answer) {
        if (answer == null || answer.length() != 4) {
            return false;
        }
        try {
            Object raw = frame.evaluate(
                    """
                            (digits) => {
                              const isVisible = (el) => {
                                if (!el) return false;
                                const st = window.getComputedStyle(el);
                                if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                                const r = el.getBoundingClientRect();
                                return r.width > 2 && r.height > 2;
                              };
                              const input = document.querySelector(
                                'input.Textinput-Control[name="rep"][inputmode="numeric"]'
                              ) || document.querySelector('input[name="rep"][inputmode="numeric"]')
                              || document.querySelector('input.Textinput-Control[name="rep"]')
                              || document.querySelector('input[name="rep"]');
                              if (!input) return { ok: false, reason: 'no-rep' };
                              if (!isVisible(input)) return { ok: false, reason: 'rep-hidden' };
                              input.focus();
                              input.value = '';
                              input.value = digits;
                              input.dispatchEvent(new Event('input', { bubbles: true }));
                              input.dispatchEvent(new Event('change', { bubbles: true }));
                              const submit = document.querySelector(
                                "button[data-testid='submit'], button[type='submit'], .AdvancedCaptcha_submit");
                              if (submit && !submit.disabled && isVisible(submit)) {
                                submit.click();
                                return { ok: true, via: 'submit-click', len: digits.length };
                              }
                              input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
                              input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
                              return { ok: true, via: 'enter', len: digits.length };
                            }
                            """,
                    answer);
            if (raw instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"))) {
                log.info("SmartCaptcha audio: ответ введён через DOM ({}), len={}", map.get("via"), map.get("len"));
                sleepQuiet(300);
                return true;
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: DOM ввод rep: {}", e.getMessage());
        }
        return false;
    }

    private Optional<Frame> findAdvancedFrame(Page page) {
        Frame advanced = null;
        Frame withChallengeType = null;
        Frame withRep = null;
        for (Frame f : page.frames()) {
            String url = safeUrl(f);
            if (!url.contains("smartcaptcha") && !url.contains("captcha.yandex")) {
                continue;
            }
            if (url.contains("/advanced")) {
                advanced = f;
            }
            try {
                Object flags = f.evaluate(
                        """
                                () => ({
                                  challenge: !!document.querySelector('button[data-testid="challenge-type"]'),
                                  rep: !!document.querySelector('input.Textinput-Control[name="rep"], input[name="rep"]')
                                })
                                """);
                if (flags instanceof java.util.Map<?, ?> map) {
                    if (Boolean.TRUE.equals(map.get("challenge"))) {
                        withChallengeType = f;
                    }
                    if (Boolean.TRUE.equals(map.get("rep"))) {
                        withRep = f;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (withChallengeType != null) {
            return Optional.of(withChallengeType);
        }
        if (withRep != null) {
            return Optional.of(withRep);
        }
        return Optional.ofNullable(advanced);
    }

    private static boolean isSmartCaptchaFrame(Frame f) {
        String url = safeUrl(f);
        return url.contains("smartcaptcha") || url.contains("captcha.yandex") || url.contains("yandexcloud.net");
    }

    private static String safeUrl(Frame frame) {
        try {
            return frame != null ? frame.url() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
