package com.omarea.krscript.downloader;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import com.omarea.common.shared.FilePathResolver;
import com.omarea.common.ui.DialogHelper;
import com.omarea.krscript.R;

public class DownloaderReceiver extends BroadcastReceiver {
    private static DownloaderReceiver downloaderReceiver;

    public static void autoRegister(Context context) {
        if (downloaderReceiver == null) {
            downloaderReceiver = new DownloaderReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            ContextCompat.registerReceiver(context, downloaderReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED);
        }
    }

    public static void autoUnRegister(Context context) {
        if (downloaderReceiver != null) {
            context.unregisterReceiver(downloaderReceiver);
            downloaderReceiver = null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                try {
                    long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (downloadId <= 0) {
                        return;
                    }
                    DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                    if (downloadManager == null) {
                        return;
                    }
                    DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                    android.database.Cursor cursor = downloadManager.query(query);
                    boolean succeeded = false;
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                                if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                                    succeeded = true;
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                    if (!succeeded) {
                        return;
                    }
                    String type = downloadManager.getMimeTypeForDownloadedFile(downloadId);
                    if (TextUtils.isEmpty(type)) {
                        type = "*/*";
                    }
                    Uri uri = downloadManager.getUriForDownloadedFile(downloadId);
                    String path = new FilePathResolver().getPath(context, uri);
                    if (path != null && !path.isEmpty() && Downloader.isTrackedDownload(context, downloadId)) {
                        boolean completed = new Downloader(context, null).saveTaskCompleted(downloadId, path);
                        if (!completed) {
                            Downloader.releaseDownloadClaim(context, downloadId);
                            return;
                        }
                        try {
                            DialogHelper.Companion.helpInfo(context, context.getString(R.string.kr_download_completed), "" + path, null);
                        } catch (Exception ex) {
                            Toast.makeText(context, context.getString(R.string.kr_download_completed) + "\n" + path, Toast.LENGTH_LONG).show();
                        }
                    }
                } catch (Exception ex) {
                }
            }
        }
    }
}
