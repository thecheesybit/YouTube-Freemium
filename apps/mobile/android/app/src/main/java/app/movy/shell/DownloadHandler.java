package app.movy.shell;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;

/** Hands WebView downloads to the system DownloadManager (http/https only). */
public final class DownloadHandler implements DownloadListener {
    private final Context ctx;

    public DownloadHandler(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long length) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Toast.makeText(ctx, "This download type is not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(uri);
            req.setMimeType(mimeType);
            req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(ctx, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
