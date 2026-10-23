package by.gdev.common.service.playwright.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Пишет разметку SmartCaptcha в {@code PLAYWRIGHT_DEBUG_DIR} асинхронно — не блокирует solve капчи.
 */
@Slf4j
public final class PlaywrightCdpDebugSink {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path DEBUG_DIR = Path.of(
            System.getenv().getOrDefault("PLAYWRIGHT_DEBUG_DIR", "D:\\gdev-playwright-debug"));

    private static final Path MARKUP_FILE = DEBUG_DIR.resolve("smartcaptcha-markup-latest.json");

    private static final Path PAZL_DIR = DEBUG_DIR.resolve("pazl");

    private static final ExecutorService ASYNC = Executors.newSingleThreadExecutor((ThreadFactory) r -> {
        Thread t = new Thread(r, "playwright-cdp-markup");
        t.setDaemon(true);
        return t;
    });

    /** Без rootHtmlSnippet — evaluate не должен висеть на минуты. */
    private static final String ADVANCED_DUMP_JS = """
            () => {
              const vis = (el) => {
                if (!el) return false;
                const r = el.getBoundingClientRect();
                const st = getComputedStyle(el);
                return r.width > 2 && r.height > 2 && st.display !== 'none' && st.visibility !== 'hidden';
              };
              const describe = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {
                  visible: vis(el),
                  tag: el.tagName,
                  testId: el.getAttribute('data-testid'),
                  ariaLabel: (el.getAttribute('aria-label') || '').slice(0, 120),
                  name: el.getAttribute('name'),
                  disabled: !!el.disabled,
                  className: String(el.className || '').slice(0, 160),
                  bbox: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
                };
              };
              const q = (s) => document.querySelector(s);
              return {
                frameUrl: location.href,
                mode: {
                  kaleidoscopeCanvas: !!q('.AdvancedCaptcha-KaleidoscopeCanvas'),
                  audioRep: !!q("input[name='rep']"),
                  audioPlay: !!q("button[data-testid='play']"),
                },
                challengeType: describe(q('button[data-testid="challenge-type"]')),
                play: describe(q('button[data-testid="play"]')),
                repInput: describe(q("input[name='rep']")),
              };
            }
            """;

    private PlaywrightCdpDebugSink() {
    }

    /**
     * Дамп Pazl для тикета 2Captcha: PNG 320×180 + полный task + meta.
     * Пишет всегда в {@code PLAYWRIGHT_DEBUG_DIR/pazl/} (не зависит от PLAYWRIGHT_DEBUG_MARKUP).
     * Отключить: {@code PLAYWRIGHT_DEBUG_PAZL=false}.
     */
    public static void dumpPazlCreate(long taskId, String imageBase64, String taskJson, String taskPreview) {
        if (!isPazlDumpEnabled() || taskId <= 0) {
            return;
        }
        String image = imageBase64;
        String task = taskJson;
        String preview = taskPreview;
        ASYNC.execute(() -> {
            try {
                Files.createDirectories(PAZL_DIR);
                Path png = PAZL_DIR.resolve(taskId + ".png");
                Path taskFile = PAZL_DIR.resolve(taskId + "-task.txt");
                Path metaFile = PAZL_DIR.resolve(taskId + "-meta.json");
                byte[] bytes = Base64.getDecoder().decode(stripDataUrl(image));
                Files.write(png, bytes);
                Files.writeString(taskFile, task == null ? "" : task, StandardCharsets.UTF_8);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("ts", Instant.now().toString());
                meta.put("taskId", taskId);
                meta.put("type", "PazlCaptchaTask");
                meta.put("imagePng", png.getFileName().toString());
                meta.put("imageBytes", bytes.length);
                meta.put("imageBase64Len", image == null ? 0 : image.length());
                meta.put("taskPreview", preview);
                meta.put("taskLen", task == null ? 0 : task.length());
                meta.put("createTask", Map.of(
                        "type", "PazlCaptchaTask",
                        "image", "<see " + png.getFileName() + ">",
                        "task", task == null ? "" : task,
                        "languagePool", "ru"
                ));
                JSON.writeValue(metaFile.toFile(), meta);
                log.info("Pazl dump → {} (+ {}-task.txt, meta)", png, taskId);
            } catch (Exception e) {
                log.warn("Pazl dump taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    /** Дописать solution в meta после getTaskResult (для тикета). */
    public static void dumpPazlResult(long taskId, String rawSolution, String action) {
        if (!isPazlDumpEnabled() || taskId <= 0) {
            return;
        }
        String sol = rawSolution;
        String act = action;
        ASYNC.execute(() -> {
            try {
                Path metaFile = PAZL_DIR.resolve(taskId + "-meta.json");
                Map<String, Object> meta;
                if (Files.isRegularFile(metaFile)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = JSON.readValue(metaFile.toFile(), Map.class);
                    meta = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
                } else {
                    meta = new LinkedHashMap<>();
                    meta.put("taskId", taskId);
                }
                meta.put("resultTs", Instant.now().toString());
                meta.put("solution", sol);
                meta.put("action", act);
                meta.put("getTaskResult", Map.of(
                        "errorId", 0,
                        "status", "ready",
                        "solution", sol == null ? "" : sol
                ));
                Files.createDirectories(PAZL_DIR);
                JSON.writeValue(metaFile.toFile(), meta);
                log.info("Pazl dump result taskId={} solution={} → {}", taskId,
                        sol == null ? "null" : (sol.length() > 80 ? sol.substring(0, 80) + "…" : sol),
                        metaFile);
            } catch (Exception e) {
                log.warn("Pazl dump result taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    /** Не блокирует поток Playwright job. */
    public static void publishSmartCaptcha(Page page, Frame advancedFrame, String phase) {
        if (!isEnabled() || page == null || page.isClosed()) {
            return;
        }
        ASYNC.execute(() -> publishSync(page, advancedFrame, phase));
    }

    private static void publishSync(Page page, Frame advancedFrame, String phase) {
        try {
            page.setDefaultTimeout(2_000);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("ts", Instant.now().toString());
            doc.put("source", "java-playwright");
            doc.put("phase", phase);
            doc.put("pageUrl", page.url());
            List<Map<String, Object>> frames = new ArrayList<>();
            if (advancedFrame != null) {
                frames.add(evalFrame(advancedFrame, "primary"));
            }
            doc.put("smartCaptchaFrames", frames);
            Files.createDirectories(DEBUG_DIR);
            JSON.writeValue(MARKUP_FILE.toFile(), doc);
            log.info("SmartCaptcha markup [{}] → {}", phase, MARKUP_FILE);
        } catch (Exception e) {
            log.debug("SmartCaptcha markup [{}]: {}", phase, e.getMessage());
        }
    }

    private static boolean isEnabled() {
        String v = System.getenv("PLAYWRIGHT_DEBUG_MARKUP");
        if (v == null || v.isBlank()) {
            return false;
        }
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    private static boolean isPazlDumpEnabled() {
        String v = System.getenv("PLAYWRIGHT_DEBUG_PAZL");
        if (v == null || v.isBlank()) {
            return true;
        }
        return !v.equalsIgnoreCase("false") && !v.equals("0");
    }

    private static String stripDataUrl(String imageBase64) {
        if (imageBase64 == null) {
            return "";
        }
        String s = imageBase64.trim();
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            return s.substring(idx + "base64,".length());
        }
        return s;
    }

    private static Map<String, Object> evalFrame(Frame frame, String role) {
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("role", role);
        wrap.put("frameUrl", safeUrl(frame));
        try {
            wrap.put("markup", frame.evaluate(ADVANCED_DUMP_JS));
        } catch (Exception e) {
            wrap.put("evaluateError", e.getMessage());
        }
        return wrap;
    }

    private static String safeUrl(Frame frame) {
        try {
            return frame != null ? frame.url() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static Path markupFilePath() {
        return MARKUP_FILE;
    }
}
