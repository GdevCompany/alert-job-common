package by.gdev.common.service.playwright.captcha;

import com.microsoft.playwright.Page;

/**
 * Снимает посторонние {@code onResponse} (логин YouDo и т.п.) на время audio solve,
 * чтобы {@code response.text()} / locator не дедлокили Playwright с перехватом voice.
 */
public interface SmartCaptchaAudioSolveGuard {

    void beforeAudioSolve(Page page);

    void afterAudioSolve(Page page);
}
