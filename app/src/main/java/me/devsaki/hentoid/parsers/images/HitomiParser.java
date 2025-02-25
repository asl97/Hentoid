package me.devsaki.hentoid.parsers.images;

import android.os.Handler;
import android.os.Looper;
import android.webkit.URLUtil;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.core.HentoidApp;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.json.sources.HitomiGalleryInfo;
import me.devsaki.hentoid.parsers.ParseHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.StringHelper;
import me.devsaki.hentoid.util.exception.EmptyResultException;
import me.devsaki.hentoid.util.file.FileHelper;
import me.devsaki.hentoid.util.network.HttpHelper;
import me.devsaki.hentoid.views.HitomiBackgroundWebView;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * Handles parsing of content from hitomi.la
 */
public class HitomiParser extends BaseImageListParser {

    @Override
    public List<ImageFile> parseImageListImpl(@NonNull Content onlineContent, @Nullable Content storedContent) throws Exception {
        String readerUrl = onlineContent.getReaderUrl();
        processedUrl = onlineContent.getGalleryUrl();

        if (!URLUtil.isValidUrl(readerUrl))
            throw new IllegalArgumentException("Invalid gallery URL : " + readerUrl);

        Timber.d("Gallery URL: %s", readerUrl);

        EventBus.getDefault().register(this);

        List<ImageFile> result;
        try {
            result = parseImageListWithWebview(onlineContent, null);
            ParseHelper.setDownloadParams(result, onlineContent.getSite().getUrl());
        } finally {
            EventBus.getDefault().unregister(this);
        }

        return result;
    }

    public List<ImageFile> parseImageListWithWebview(@NonNull Content onlineContent, WebView webview) throws Exception {
        String pageUrl = onlineContent.getReaderUrl();

        // Add referer information to downloadParams for future image download
        Map<String, String> downloadParams = new HashMap<>();
        downloadParams.put(HttpHelper.HEADER_REFERER_KEY, pageUrl);
        String downloadParamsStr = JsonHelper.serializeToJson(downloadParams, JsonHelper.MAP_STRINGS);

        String galleryJsonUrl = "https://ltn.hitomi.la/galleries/" + onlineContent.getUniqueSiteId() + ".js";

        // Get the gallery JSON
        List<Pair<String, String>> headers = new ArrayList<>();
        headers.add(new Pair<>(HttpHelper.HEADER_REFERER_KEY, pageUrl));
        Response response = HttpHelper.getOnlineResourceFast(galleryJsonUrl, headers, Site.HITOMI.useMobileAgent(), Site.HITOMI.useHentoidAgent(), Site.HITOMI.useWebviewAgent());

        ResponseBody body = response.body();
        if (null == body) throw new IOException("Empty body");
        String galleryInfo = body.string();

        updateContentInfo(onlineContent, galleryInfo);
        onlineContent.setUpdatedProperties(true);

        // Get pages URL
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicReference<String> imagesStr = new AtomicReference<>();
        Handler handler = new Handler(Looper.getMainLooper());
        if (null == webview) {
            handler.post(() -> {
                if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
                HitomiBackgroundWebView hitomiWv = new HitomiBackgroundWebView(HentoidApp.Companion.getInstance(), Site.HITOMI);
                Timber.d(">> loading url %s", pageUrl);
                hitomiWv.loadUrl(pageUrl, () -> evaluateJs(hitomiWv, galleryInfo, imagesStr, done));
                Timber.i(">> loading wv");
            });
        } else { // We suppose the caller is the main thread if the webview is provided
            handler.post(() -> evaluateJs(webview, galleryInfo, imagesStr, done));
        }

        int remainingIterations = 15; // Timeout
        do {
            Helper.pause(1000);
        } while (!done.get() && !processHalted.get() && remainingIterations-- > 0);
        if (processHalted.get())
            throw new EmptyResultException("Unable to detect pages (empty result)");

        String jsResult = imagesStr.get();
        if (null == jsResult || jsResult.isEmpty())
            throw new EmptyResultException("Unable to detect pages (empty result)");

        List<ImageFile> result = new ArrayList<>();
        jsResult = jsResult.replace("\"[", "[").replace("]\"", "]").replace("\\\"", "\"");
        List<String> imageUrls = JsonHelper.jsonToObject(jsResult, JsonHelper.LIST_STRINGS);
        if (imageUrls != null && !imageUrls.isEmpty()) {
            onlineContent.setCoverImageUrl(imageUrls.get(0));
            result.add(ImageFile.newCover(imageUrls.get(0), StatusContent.SAVED));
            int order = 1;
            for (String s : imageUrls) {
                ImageFile img = ParseHelper.urlToImageFile(s, order++, imageUrls.size(), StatusContent.SAVED);
                img.setDownloadParams(downloadParamsStr);
                result.add(img);
            }
        }

        return result;
    }

    // TODO doc
    private void evaluateJs(@NonNull WebView webview, @NonNull String galleryInfo, @NonNull AtomicReference<String> imagesStr, @NonNull AtomicBoolean done) {
        Timber.d(">> evaluating JS");
        webview.evaluateJavascript(getJsPagesScript(galleryInfo), s -> {
            Timber.d(">> JS evaluated");
            imagesStr.set(StringHelper.protect(s));
            done.set(true);
        });
    }

    // TODO optimize
    private String getJsPagesScript(@NonNull String galleryInfo) {
        StringBuilder sb = new StringBuilder();
        FileHelper.getAssetAsString(HentoidApp.Companion.getInstance().getAssets(), "hitomi_pages.js", sb);
        return sb.toString().replace("$galleryInfo", galleryInfo);
    }

    // TODO doc
    private void updateContentInfo(@NonNull Content content, @NonNull String galleryInfoStr) throws Exception {
        int firstBrace = galleryInfoStr.indexOf("{");
        int lastBrace = galleryInfoStr.lastIndexOf("}");
        if (firstBrace > -1 && lastBrace > -1) {
            String galleryJson = galleryInfoStr.substring(firstBrace, lastBrace + 1);
            HitomiGalleryInfo galleryInfo = JsonHelper.jsonToObject(galleryJson, HitomiGalleryInfo.class);
            galleryInfo.updateContent(content);
        } else throw new EmptyResultException("Couldn't find gallery information");
    }

    @Override
    protected List<String> parseImages(@NonNull Content content) {
        /// We won't use that as parseImageListImpl is overriden directly
        return null;
    }
}
