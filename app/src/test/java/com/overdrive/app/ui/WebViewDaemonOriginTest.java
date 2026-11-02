package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.ui.fragment.WebViewFragment;
import java.net.MalformedURLException;
import java.net.URL;
import org.junit.Test;

/** Only the daemon origin may receive a session or remain inside the bridged WebView. */
public class WebViewDaemonOriginTest {
    private static void allowed(String value) throws MalformedURLException {
        assertTrue(value, WebViewFragment.isDaemonUrl(new URL(value)));
    }

    private static void denied(String value) throws MalformedURLException {
        assertFalse(value, WebViewFragment.isDaemonUrl(new URL(value)));
    }

    @Test
    public void acceptsDaemonOriginsWithAnEmptyRootPath() throws Exception {
        allowed("http://127.0.0.1:8080");
        allowed("http://localhost:8080");
        allowed("http://localhost:8080?view=home");
    }

    @Test
    public void acceptsDaemonPagesAssetsAndMediaPaths() throws Exception {
        allowed("http://127.0.0.1:8080/");
        allowed("http://localhost:8080/events.html?file=fixture.mp4");
        allowed("http://127.0.0.1:8080/shared/fixture.js?v=58");
        allowed("http://127.0.0.1:8080/video.mp4?start=0");
    }

    @Test
    public void rejectsUserInformationOnTheDaemonHost() throws Exception {
        denied("http://user@127.0.0.1:8080/");
        denied("http://user:password@localhost:8080/events.html");
        denied("http://@localhost:8080/");
    }

    @Test
    public void rejectsDaemonTextInForeignAuthorities() throws Exception {
        denied("http://127.0.0.1:8080@127.0.0.2:18080/citation");
        denied("http://localhost:8080@example.invalid:8080/events.html");
        denied("http://127.0.0.1.example.invalid:8080/");
        denied("http://localhost.example.invalid:8080/");
    }

    @Test
    public void rejectsDaemonTextInForeignPathsAndQueries() throws Exception {
        denied("http://example.invalid:8080/127.0.0.1:8080/events.html");
        denied("http://127.0.0.2:18080/citation?next=http://localhost:8080/");
        denied("https://example.invalid/?origin=http://127.0.0.1:8080/");
    }

    @Test
    public void requiresTheExactHttpOrigin() throws Exception {
        denied("https://127.0.0.1:8080/");
        denied("http://127.0.0.1/");
        denied("http://127.0.0.1:80/");
        denied("http://127.0.0.1:8081/");
        denied("http://127.0.0.2:8080/");
        denied("http://[::1]:8080/");
        denied("ftp://localhost:8080/");
    }
}
