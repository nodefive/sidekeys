package com.unl0cker.bixby;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Android only lets an app see and control other apps' media sessions
 * (MediaSessionManager.getActiveSessions) while it holds notification access; SideKeys needs
 * that to know which app is playing and to skip and seek in it. No callbacks are overridden:
 * notifications are never read.
 */
public class MediaListener extends NotificationListenerService {
}
