package tv.webremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the screen-share permission alive. Android requires a foreground service, with a notification, for as long as an
 * app holds a MediaProjection. It runs from the moment the user allows sharing on the TV until they stop it.
 */
public class CaptureService extends Service {

    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String CHANNEL = "share";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.share_title), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(R.string.share_notification))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjection projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
            if (ScreenCapture.current != null) ScreenCapture.current.stop();
            new ScreenCapture(this, projection);
        } catch (RuntimeException refused) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        ScreenCapture capture = ScreenCapture.current;
        if (capture != null) capture.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
