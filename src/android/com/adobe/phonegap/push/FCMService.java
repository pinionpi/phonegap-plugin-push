package com.adobe.phonegap.push;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.WearableExtender;
import androidx.core.app.RemoteInput;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;

import com.eunite.atwork.EncryptUtils;
import com.eunite.atwork.R;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;

@SuppressLint("NewApi")
public class FCMService extends FirebaseMessagingService implements PushConstants {

  private static final String LOG_TAG = "Push_FCMService";
  private static HashMap<Integer, ArrayList<String>> messageMap = new HashMap<Integer, ArrayList<String>>();

  // 2020-07-15 Try WakeLock for voice call (See GcmService.onMessageReceived)
  // 2021-09-08 keyguardLock disableKeyguard/reenableKeyguard => not work
  // 2021-09-08 Android moveToBackground (also clearScreenAndKeyguardFlags) / See BackgroundModeExt.java
  private static PowerManager.WakeLock wakeLock;
  private static KeyguardManager.KeyguardLock keyguardLock; // See also FLAG_DISMISS_KEYGUARD

  public void setNotification(int notId, String message) {
    ArrayList<String> messageList = messageMap.get(notId);
    if (messageList == null) {
      messageList = new ArrayList<String>();
      messageMap.put(notId, messageList);
    }

    if (message.isEmpty()) {
      messageList.clear();
    } else {
      messageList.add(message);
    }
  }

  @Override
  public void onMessageReceived(RemoteMessage message) {

    String from = message.getFrom();
    Log.d(LOG_TAG, "onMessage - from: " + from);

    Bundle extras = new Bundle();

    if (message.getNotification() != null) {
      extras.putString(TITLE, message.getNotification().getTitle());
      extras.putString(MESSAGE, message.getNotification().getBody());
      extras.putString(SOUND, message.getNotification().getSound());
      extras.putString(ICON, message.getNotification().getIcon());
      extras.putString(COLOR, message.getNotification().getColor());
    }
    for (Map.Entry<String, String> entry : message.getData().entrySet()) {
      extras.putString(entry.getKey(), entry.getValue());
    }

    if (extras != null && isAvailableSender(from)) {
      Context applicationContext = getApplicationContext();

      // 2020-07-15 WakeLock for call
      final String rawOp = extras.getString("rawOp");
      if ("call".equals(rawOp)) {
        try {
          PowerManager pm = (PowerManager) applicationContext.getSystemService(Context.POWER_SERVICE);
          // Tested on Android O (8.0.0 API 26)
          wakeLock = pm.newWakeLock((PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP), "eunite:wakelocktag");

          // Not Working Options
          //wakeLock = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP, "eunite:wakelocktag");
          //wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "eunite:wakelocktag");
          //wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "eunite:wakelocktag");

          // 2021-09-08 acquire wakeLock with timeout 2s
          wakeLock.acquire(2*1000L);
          Log.w(LOG_TAG, "acquire wakeLock=" + wakeLock + ", isHeld=" + wakeLock.isHeld());
        } catch (Exception ex) {
          Log.w(LOG_TAG, "Wake Lock Acquire Failed.", ex);
        }
        /* Not disableKeyguard here => See BackgroundModeExt.java
        if (wakeLock != null) {
          // KeyguardManager => Now use SHOW_WHEN_LOCKED
          KeyguardManager keyguardManager = (KeyguardManager) applicationContext.getSystemService(Context.KEYGUARD_SERVICE);
          keyguardLock =  keyguardManager.newKeyguardLock("eunite:wakelocktag");
          Log.w(LOG_TAG, "disableKeyguard: keyguardLock=" + keyguardLock);
          if (keyguardLock != null) {
            keyguardLock.disableKeyguard();
            Log.w(LOG_TAG, "acquire wakeLock + disableKeyguard => successfully");
          }
        }
        */
      } else {
        if (keyguardLock != null) {
          Log.w(LOG_TAG, "reenableKeyguard: keyguardLock=" + keyguardLock);
          keyguardLock.reenableKeyguard();
          keyguardLock = null;
        }
        // Cannot release => WakeLock under-locked
        if (wakeLock != null) {
          Log.w(LOG_TAG, "release wakeLock=" + wakeLock + ", isHeld=" + wakeLock.isHeld());
          if (wakeLock.isHeld()) {
            wakeLock.release();
          }
          wakeLock = null;
        }
        Log.w(LOG_TAG, "Check keyguardLock=" + keyguardLock + ", wakeLock=" + wakeLock);
      }

      /*
      KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
      if (keyguardManager.isKeyguardLocked()) {
          keyguardManager.requestDismissKeyguard(???, ???);

      }
      */

      SharedPreferences prefs = applicationContext.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH,
          Context.MODE_PRIVATE);
      boolean forceShow = prefs.getBoolean(FORCE_SHOW, false);
      boolean clearBadge = prefs.getBoolean(CLEAR_BADGE, false);
      String messageKey = prefs.getString(MESSAGE_KEY, MESSAGE);
      String titleKey = prefs.getString(TITLE_KEY, TITLE);

      extras = normalizeExtras(applicationContext, extras, messageKey, titleKey);

      if (clearBadge) {
        PushPlugin.setApplicationIconBadgeNumber(getApplicationContext(), 0);
      }

      // if we are in the foreground and forceShow is `false` only send data
      if (!forceShow && PushPlugin.isInForeground()) {
        Log.d(LOG_TAG, "foreground");
        extras.putBoolean(FOREGROUND, true);
        extras.putBoolean(COLDSTART, false);
        PushPlugin.sendExtras(extras);
      }
      // if we are in the foreground and forceShow is `true`, force show the notification if the data has at least a message or title
      else if (forceShow && PushPlugin.isInForeground()) {
        Log.d(LOG_TAG, "foreground force");
        extras.putBoolean(FOREGROUND, true);
        extras.putBoolean(COLDSTART, false);

        showNotificationIfPossible(applicationContext, extras);
      }
      // if we are not in the foreground always send notification if the data has at least a message or title
      else {
        Log.d(LOG_TAG, "background");
        extras.putBoolean(FOREGROUND, false);
        extras.putBoolean(COLDSTART, PushPlugin.isActive());

        showNotificationIfPossible(applicationContext, extras);
      }

      /* 2021-09-08 Comment release wakeLock
      if (wakeLock != null && wakeLock.isHeld()) {
        try {
          wakeLock.release();
          wakeLock = null;
        } catch (Exception ex) {
          Log.w(LOG_TAG, "Wake Lock Release Failed.", ex);
        }
      }
      */
    }
  }

  /*
   * Change a values key in the extras bundle
   */
  private void replaceKey(Context context, String oldKey, String newKey, Bundle extras, Bundle newExtras) {
    Object value = extras.get(oldKey);
    if (value != null) {
      if (value instanceof String) {
        value = localizeKey(context, newKey, (String) value);

        newExtras.putString(newKey, (String) value);
      } else if (value instanceof Boolean) {
        newExtras.putBoolean(newKey, (Boolean) value);
      } else if (value instanceof Number) {
        newExtras.putDouble(newKey, ((Number) value).doubleValue());
      } else {
        newExtras.putString(newKey, String.valueOf(value));
      }
    }
  }

  /*
   * Normalize localization for key
   */
  private String localizeKey(Context context, String key, String value) {
    if (key.equals(TITLE) || key.equals(MESSAGE) || key.equals(SUMMARY_TEXT)) {
      try {
        JSONObject localeObject = new JSONObject(value);

        String localeKey = localeObject.getString(LOC_KEY);

        ArrayList<String> localeFormatData = new ArrayList<String>();
        if (!localeObject.isNull(LOC_DATA)) {
          String localeData = localeObject.getString(LOC_DATA);
          JSONArray localeDataArray = new JSONArray(localeData);
          for (int i = 0; i < localeDataArray.length(); i++) {
            localeFormatData.add(localeDataArray.getString(i));
          }
        }

        String packageName = context.getPackageName();
        Resources resources = context.getResources();

        int resourceId = resources.getIdentifier(localeKey, "string", packageName);

        if (resourceId != 0) {
          return resources.getString(resourceId, localeFormatData.toArray());
        } else {
          Log.d(LOG_TAG, "can't find resource for locale key = " + localeKey);

          return value;
        }
      } catch (JSONException e) {
        Log.d(LOG_TAG, "no locale found for key = " + key + ", error " + e.getMessage());

        return value;
      }
    }

    return value;
  }

  /*
   * Replace alternate keys with our canonical value
   */
  private String normalizeKey(String key, String messageKey, String titleKey, Bundle newExtras) {
    if (key.equals(BODY) || key.equals(ALERT) || key.equals(MP_MESSAGE) || key.equals(GCM_NOTIFICATION_BODY)
        || key.equals(TWILIO_BODY) || key.equals(messageKey) || key.equals(AWS_PINPOINT_BODY)) {
      return MESSAGE;
    } else if (key.equals(TWILIO_TITLE) || key.equals(SUBJECT) || key.equals(titleKey)) {
      return TITLE;
    } else if (key.equals(MSGCNT) || key.equals(BADGE)) {
      return COUNT;
    } else if (key.equals(SOUNDNAME) || key.equals(TWILIO_SOUND)) {
      return SOUND;
    } else if (key.equals(AWS_PINPOINT_PICTURE)) {
      newExtras.putString(STYLE, STYLE_PICTURE);
      return PICTURE;
    } else if (key.startsWith(GCM_NOTIFICATION)) {
      return key.substring(GCM_NOTIFICATION.length() + 1, key.length());
    } else if (key.startsWith(GCM_N)) {
      return key.substring(GCM_N.length() + 1, key.length());
    } else if (key.startsWith(UA_PREFIX)) {
      key = key.substring(UA_PREFIX.length() + 1, key.length());
      return key.toLowerCase();
    } else if (key.startsWith(AWS_PINPOINT_PREFIX)) {
      return key.substring(AWS_PINPOINT_PREFIX.length() + 1, key.length());
    } else {
      return key;
    }
  }

  /*
   * Parse bundle into normalized keys.
   */
  private Bundle normalizeExtras(Context context, Bundle extras, String messageKey, String titleKey) {
    Log.d(LOG_TAG, "normalize extras");
    Iterator<String> it = extras.keySet().iterator();
    Bundle newExtras = new Bundle();

    while (it.hasNext()) {
      String key = it.next();

      Log.d(LOG_TAG, "key = " + key);

      // If normalizeKeythe key is "data" or "message" and the value is a json object extract
      // This is to support parse.com and other services. Issue #147 and pull #218
      if (key.equals(PARSE_COM_DATA) || key.equals(MESSAGE) || key.equals(messageKey)) {
        Object json = extras.get(key);
        // Make sure data is json object stringified
        if (json instanceof String && ((String) json).startsWith("{")) {
          Log.d(LOG_TAG, "extracting nested message data from key = " + key);
          try {
            // If object contains message keys promote each value to the root of the bundle
            JSONObject data = new JSONObject((String) json);
            if (data.has(ALERT) || data.has(MESSAGE) || data.has(BODY) || data.has(TITLE) || data.has(messageKey)
                || data.has(titleKey)) {
              Iterator<String> jsonIter = data.keys();
              while (jsonIter.hasNext()) {
                String jsonKey = jsonIter.next();

                Log.d(LOG_TAG, "key = data/" + jsonKey);

                String value = data.getString(jsonKey);
                jsonKey = normalizeKey(jsonKey, messageKey, titleKey, newExtras);
                value = localizeKey(context, jsonKey, value);

                newExtras.putString(jsonKey, value);
              }
            } else if (data.has(LOC_KEY) || data.has(LOC_DATA)) {
              String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
              Log.d(LOG_TAG, "replace key " + key + " with " + newKey);
              replaceKey(context, key, newKey, extras, newExtras);
            }
          } catch (JSONException e) {
            Log.e(LOG_TAG, "normalizeExtras: JSON exception");
          }
        } else {
          String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
          Log.d(LOG_TAG, "replace key " + key + " with " + newKey);
          replaceKey(context, key, newKey, extras, newExtras);
        }
      } else if (key.equals(("notification"))) {
        Bundle value = extras.getBundle(key);
        Iterator<String> iterator = value.keySet().iterator();
        while (iterator.hasNext()) {
          String notifkey = iterator.next();

          Log.d(LOG_TAG, "notifkey = " + notifkey);
          String newKey = normalizeKey(notifkey, messageKey, titleKey, newExtras);
          Log.d(LOG_TAG, "replace key " + notifkey + " with " + newKey);

          String valueData = value.getString(notifkey);
          valueData = localizeKey(context, newKey, valueData);

          newExtras.putString(newKey, valueData);
        }
        continue;
        // In case we weren't working on the payload data node or the notification node,
        // normalize the key.
        // This allows to have "message" as the payload data key without colliding
        // with the other "message" key (holding the body of the payload)
        // See issue #1663
      } else {
        String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
        Log.d(LOG_TAG, "replace key " + key + " with " + newKey);
        replaceKey(context, key, newKey, extras, newExtras);
      }

    } // while

    return newExtras;
  }

  private int extractBadgeCount(Bundle extras) {
    int count = -1;
    String msgcnt = extras.getString(COUNT);

    try {
      if (msgcnt != null) {
        count = Integer.parseInt(msgcnt);
      }
    } catch (NumberFormatException e) {
      Log.e(LOG_TAG, e.getLocalizedMessage(), e);
    }

    return count;
  }

  private void showNotificationIfPossible(Context context, Bundle extras) {

    // Send a notification if there is a message or title, otherwise just send data
    String message = extras.getString(MESSAGE);
    String title = extras.getString(TITLE);
    String contentAvailable = extras.getString(CONTENT_AVAILABLE);
    String forceStart = extras.getString(FORCE_START);
    int badgeCount = extractBadgeCount(extras);
    if (badgeCount >= 0) {
      Log.d(LOG_TAG, "count =[" + badgeCount + "]");
      PushPlugin.setApplicationIconBadgeNumber(context, badgeCount);
    }
    if (badgeCount == 0) {
      NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      mNotificationManager.cancelAll();
    }

    Log.d(LOG_TAG, "message =[" + message + "]");
    Log.d(LOG_TAG, "title =[" + title + "]");
    Log.d(LOG_TAG, "contentAvailable =[" + contentAvailable + "]");
    Log.d(LOG_TAG, "forceStart =[" + forceStart + "]");

    if ((message != null && message.length() != 0) || (title != null && title.length() != 0)) {

      Log.d(LOG_TAG, "create notification");

      if (title == null || title.isEmpty()) {
        extras.putString(TITLE, getAppName(this));
      }

      createNotification(context, extras);
    }

    if (!PushPlugin.isActive() && "1".equals(forceStart)) {
      Log.d(LOG_TAG, "app is not running but we should start it and put in background");
      Intent intent = new Intent(this, PushHandlerActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.putExtra(PUSH_BUNDLE, extras);
      intent.putExtra(START_IN_BACKGROUND, true);
      intent.putExtra(FOREGROUND, false);
      startActivity(intent);
    } else if ("1".equals(contentAvailable)) {
      Log.d(LOG_TAG, "app is not running and content available true");
      Log.d(LOG_TAG, "send notification event");
      PushPlugin.sendExtras(extras);
    }
  }

  public void createNotification(Context context, Bundle extras) {
    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    String appName = getAppName(this);
    String packageName = context.getPackageName();
    Resources resources = context.getResources();

    int notId = parseInt(NOT_ID, extras);
    Intent notificationIntent = new Intent(this, PushHandlerActivity.class);
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    //notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    notificationIntent.putExtra(PUSH_BUNDLE, extras);
    notificationIntent.putExtra(NOT_ID, notId);

    SecureRandom random = new SecureRandom();
    int requestCode = random.nextInt();
    PendingIntent contentIntent = PendingIntent.getActivity(this, requestCode, notificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent dismissedNotificationIntent = new Intent(this, PushDismissedHandler.class);
    dismissedNotificationIntent.putExtra(PUSH_BUNDLE, extras);
    dismissedNotificationIntent.putExtra(NOT_ID, notId);
    dismissedNotificationIntent.putExtra(DISMISSED, true);
    dismissedNotificationIntent.setAction(PUSH_DISMISSED);

    requestCode = random.nextInt();
    PendingIntent deleteIntent = PendingIntent.getBroadcast(this, requestCode, dismissedNotificationIntent,
        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    // Test Sound Android Channel
    // See: https://stackoverflow.com/questions/50567164/custom-notification-sound-not-working-in-oreo
    // See: https://github.com/phonegap/phonegap-plugin-push/blob/master/src/android/com/adobe/phonegap/push/PushPlugin.java

    // https://github.com/phonegap/phonegap-plugin-push/blob/master/docs/API.md#channel-properties
    // The name of the sound file to be played upon receipt of the notification in this channel.
    // Cannot be changed after channel is created.

    // 2019-10-07 TODO Use sound from channel
    //android.resource://com.eunite.atwork/raw/noti
    //String soundname = "noti";
    //Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/raw/" + soundname);
    //Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + R.raw.test);
    //Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + R.raw.noti);

    NotificationCompat.Builder mBuilder = null;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      String channelID = extras.getString(ANDROID_CHANNEL_ID);

      // if the push payload specifies a channel use it
      if (channelID != null) {
        mBuilder = new NotificationCompat.Builder(context, channelID);
      } else {
        List<NotificationChannel> channels = mNotificationManager.getNotificationChannels();

        if (channels.size() == 1) {
          channelID = channels.get(0).getId();
        } else {
          channelID = extras.getString(ANDROID_CHANNEL_ID, DEFAULT_CHANNEL_ID);
        }
        Log.d(LOG_TAG, "Using channel ID = " + channelID);
        mBuilder = new NotificationCompat.Builder(context, channelID);
      }

    } else {
      mBuilder = new NotificationCompat.Builder(context);
    }

    mBuilder.setWhen(System.currentTimeMillis())
        .setContentTitle(fromHtml(extras.getString(TITLE)))
        .setTicker(fromHtml(extras.getString(TITLE)))
        .setContentIntent(contentIntent)
        .setDeleteIntent(deleteIntent)
        .setAutoCancel(true);

    SharedPreferences prefs = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
    String localIcon = prefs.getString(ICON, null);
    String localIconColor = prefs.getString(ICON_COLOR, null);
    boolean soundOption = prefs.getBoolean(SOUND, true);
    boolean vibrateOption = prefs.getBoolean(VIBRATE, true);
    Log.d(LOG_TAG, "stored icon=" + localIcon);
    Log.d(LOG_TAG, "stored iconColor=" + localIconColor);
    Log.d(LOG_TAG, "stored sound=" + soundOption);
    Log.d(LOG_TAG, "stored vibrate=" + vibrateOption);

    // 2018-07-18 Fix Notification Icon for Android N (color, vector icon)
    // <preference name="StatusBarBackgroundColor" value="#0D47A1" />
    // Fix icon color (Android Lollipop+)
    // https://stackoverflow.com/questions/30795431/android-push-notifications-icon-not-displaying-in-notification-white-square-sh
    // Cause: For 5.0 Lollipop "Notification icons must be entirely white". / Solution for target Sdk 21
    if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (localIconColor == null || !"".equals(localIconColor)) {
        localIconColor = "#0D47A1"; // atwork blue
      }
    }
    // Fix icon (Android 7.0 N+)
    // https://stackoverflow.com/questions/43092082/android-7-0-notification-icon-appearing-white-square
    if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      if (localIcon == null || !"".equals(localIcon)) {
        //localIcon = "ic_launcher";
        //localIcon = "loading_icon"; // has transparent
        localIcon = "ic_stat_name"; // Create via Android Studio: res -> New -> Image Asset
      }
    }

    /*
     * Notification Vibration
     */

    setNotificationVibration(extras, vibrateOption, mBuilder);

    /*
     * Notification Icon Color
     *
     * Sets the small-icon background color of the notification.
     * To use, add the `iconColor` key to plugin android options
     *
     */
    setNotificationIconColor(extras.getString(COLOR), mBuilder, localIconColor);

    /*
     * Notification Icon
     *
     * Sets the small-icon of the notification.
     *
     * - checks the plugin options for `icon` key
     * - if none, uses the application icon
     *
     * The icon value must be a string that maps to a drawable resource.
     * If no resource is found, falls
     *
     */
    setNotificationSmallIcon(context, extras, packageName, resources, mBuilder, localIcon);

    /*
     * Notification Large-Icon
     *
     * Sets the large-icon of the notification
     *
     * - checks the gcm data for the `image` key
     * - checks to see if remote image, loads it.
     * - checks to see if assets image, Loads It.
     * - checks to see if resource image, LOADS IT!
     * - if none, we don't set the large icon
     *
     */
    setNotificationLargeIcon(extras, packageName, resources, mBuilder);

    /*
     * Notification Sound
     */
    // Android Oreo must initialize sound per channel (init once)
    // https://stackoverflow.com/questions/46019496/notification-sound-on-api-26/46192246
    if (soundOption) {
      setNotificationSound(context, extras, mBuilder);
    }
    /*
    // This use soundname from Payload and play as Ringtone (Depend RingtoneManager)
    if (soundOption && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      String soundname = extras.getString(SOUNDNAME);
      if (soundname == null) {
        soundname = extras.getString(SOUND);
      }
      Log.d(LOG_TAG, "soundname=" + soundname);
      if (soundname != null && !soundname.contentEquals(SOUND_DEFAULT)) {
        Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                + "://" + context.getPackageName() + "/raw/" + soundname);
        Log.d(LOG_TAG, "soundUri=" + soundUri.toString());
        // FIXME mChannel variable
        //if (mNotificationManager.areNotificationsEnabled() && mChannel.getImportance() != NotificationManager.IMPORTANCE_NONE) {
        if (mNotificationManager.areNotificationsEnabled()) {
          try {
            Ringtone r = RingtoneManager.getRingtone(context, soundUri);
            r.play();
          } catch (Exception ex) {
            Log.w(LOG_TAG, "Cannot play sound: " + sound, ex);
          }
        }
      }
    } else if (soundOption) {
      setNotificationSound(context, extras, mBuilder);
    }
    */

    /*
     *  LED Notification
     */
    setNotificationLedColor(extras, mBuilder);

    /*
     *  Priority Notification
     */
    setNotificationPriority(extras, mBuilder);

    /*
     * Notification message
     */
    setNotificationMessage(notId, extras, mBuilder);

    /*
     * Notification count
     */
    setNotificationCount(context, extras, mBuilder);

    /*
     *  Notification ongoing
     */
    setNotificationOngoing(extras, mBuilder);

    /*
     * Notification count
     */
    setVisibility(context, extras, mBuilder);

    /*
     * Notification add actions
     */
    createActions(extras, mBuilder, resources, packageName, notId);

    mNotificationManager.notify(appName, notId, mBuilder.build());
  }

  private void updateIntent(Intent intent, String callback, Bundle extras, boolean foreground, int notId) {
    intent.putExtra(CALLBACK, callback);
    intent.putExtra(PUSH_BUNDLE, extras);
    intent.putExtra(FOREGROUND, foreground);
    intent.putExtra(NOT_ID, notId);
  }

  private void createActions(Bundle extras, NotificationCompat.Builder mBuilder, Resources resources,
      String packageName, int notId) {
    Log.d(LOG_TAG, "create actions: with in-line");
    String actions = extras.getString(ACTIONS);
    if (actions != null) {
      try {
        JSONArray actionsArray = new JSONArray(actions);
        ArrayList<NotificationCompat.Action> wActions = new ArrayList<NotificationCompat.Action>();
        for (int i = 0; i < actionsArray.length(); i++) {
          int min = 1;
          int max = 2000000000;
          SecureRandom random = new SecureRandom();
          int uniquePendingIntentRequestCode = random.nextInt((max - min) + 1) + min;
          Log.d(LOG_TAG, "adding action");
          JSONObject action = actionsArray.getJSONObject(i);
          Log.d(LOG_TAG, "adding callback = " + action.getString(CALLBACK));
          boolean foreground = action.optBoolean(FOREGROUND, true);
          boolean inline = action.optBoolean("inline", false);
          Intent intent = null;
          PendingIntent pIntent = null;
          if (inline) {
            Log.d(LOG_TAG, "Version: " + android.os.Build.VERSION.SDK_INT + " = " + android.os.Build.VERSION_CODES.M);
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity");
              intent = new Intent(this, PushHandlerActivity.class);
            } else {
              Log.d(LOG_TAG, "push receiver");
              intent = new Intent(this, BackgroundActionButtonHandler.class);
            }

            updateIntent(intent, action.getString(CALLBACK), extras, foreground, notId);

            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity for notId " + notId);
              pIntent = PendingIntent.getActivity(this, uniquePendingIntentRequestCode, intent,
                  PendingIntent.FLAG_ONE_SHOT);
            } else {
              Log.d(LOG_TAG, "push receiver for notId " + notId);
              pIntent = PendingIntent.getBroadcast(this, uniquePendingIntentRequestCode, intent,
                  PendingIntent.FLAG_ONE_SHOT);
            }
          } else if (foreground) {
            intent = new Intent(this, PushHandlerActivity.class);
            updateIntent(intent, action.getString(CALLBACK), extras, foreground, notId);
            pIntent = PendingIntent.getActivity(this, uniquePendingIntentRequestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
          } else {
            intent = new Intent(this, BackgroundActionButtonHandler.class);
            updateIntent(intent, action.getString(CALLBACK), extras, foreground, notId);
            pIntent = PendingIntent.getBroadcast(this, uniquePendingIntentRequestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
          }

          NotificationCompat.Action.Builder actionBuilder = new NotificationCompat.Action.Builder(
              getImageId(resources, action.optString(ICON, ""), packageName), action.getString(TITLE), pIntent);

          RemoteInput remoteInput = null;
          if (inline) {
            Log.d(LOG_TAG, "create remote input");
            String replyLabel = action.optString(INLINE_REPLY_LABEL, "Enter your reply here");
            remoteInput = new RemoteInput.Builder(INLINE_REPLY).setLabel(replyLabel).build();
            actionBuilder.addRemoteInput(remoteInput);
          }

          NotificationCompat.Action wAction = actionBuilder.build();
          wActions.add(actionBuilder.build());

          if (inline) {
            mBuilder.addAction(wAction);
          } else {
            mBuilder.addAction(getImageId(resources, action.optString(ICON, ""), packageName), action.getString(TITLE),
                pIntent);
          }
          wAction = null;
          pIntent = null;
        }
        mBuilder.extend(new WearableExtender().addActions(wActions));
        wActions.clear();
      } catch (JSONException e) {
        // nope
      }
    }
  }

  private void setNotificationCount(Context context, Bundle extras, NotificationCompat.Builder mBuilder) {
    int count = extractBadgeCount(extras);
    if (count >= 0) {
      Log.d(LOG_TAG, "count =[" + count + "]");
      mBuilder.setNumber(count);
    }
  }

  private void setVisibility(Context context, Bundle extras, NotificationCompat.Builder mBuilder) {
    String visibilityStr = extras.getString(VISIBILITY);
    if (visibilityStr != null) {
      try {
        Integer visibility = Integer.parseInt(visibilityStr);
        if (visibility >= NotificationCompat.VISIBILITY_SECRET && visibility <= NotificationCompat.VISIBILITY_PUBLIC) {
          mBuilder.setVisibility(visibility);
        } else {
          Log.e(LOG_TAG, "Visibility parameter must be between -1 and 1");
        }
      } catch (NumberFormatException e) {
        e.printStackTrace();
      }
    }
  }

  private void setNotificationVibration(Bundle extras, Boolean vibrateOption, NotificationCompat.Builder mBuilder) {
    String vibrationPattern = extras.getString(VIBRATION_PATTERN);
    if (vibrationPattern != null) {
      String[] items = vibrationPattern.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
      long[] results = new long[items.length];
      for (int i = 0; i < items.length; i++) {
        try {
          results[i] = Long.parseLong(items[i].trim());
        } catch (NumberFormatException nfe) {
        }
      }
      mBuilder.setVibrate(results);
    } else {
      if (vibrateOption) {
        mBuilder.setDefaults(Notification.DEFAULT_VIBRATE);
      }
    }
  }

  private void setNotificationOngoing(Bundle extras, NotificationCompat.Builder mBuilder) {
    boolean ongoing = Boolean.parseBoolean(extras.getString(ONGOING, "false"));
    mBuilder.setOngoing(ongoing);
  }

  /**
   * Change History for FCMService setNotificationMessage:
   * 2019-08-30 Repeat split setNotificationMessage/displayNotificationMessage from GCMIntentService to FCMService
   * 2022-10-19 grouping keys: offline
   *
   * Change History for GCMIntentService setNotificationMessage:
   * 2018-05-17 POC Edit notification message => OK
   * 2018-05-17 POC Read Key File => OK
   * 2018-05-30 Integrate Decrypt Push in Android AtWork2018
   * 2018-05-30 Determine which keyFile from push payload rawMesg.lock => OK
   * 2018-06-06 Teerawat In case not text message use message from server
   * 2018-07-18 show sender udenName for roomType group
   * 2018-10-24 Support pushChatMI
   * 2018-11-28 Offline EncProfile complete cases: room type x message type x lock text x EncProfile
   *      room type (friend,group)
   *      message type (file,contact,location,secret,link,notification,text)
   *      lock text (nolock,unlock,unlockfail)
   *      EncProfile (solved uden name, group room name, unresolved)
   *
   * HowTo decodeURIComponent in Android
   * Ref: https://stackoverflow.com/questions/2632175/decoding-uri-query-string-in-java
   *
   * @param notId
   * @param extras
   * @param mBuilder
   */
  private void setNotificationMessage(int notId, Bundle extras, NotificationCompat.Builder mBuilder) {
    String title = extras.getString(TITLE);
    String message = extras.getString(MESSAGE);
    //Log.d(LOG_TAG, "setNotificationMessage: message=" + message);
    //Log.d(LOG_TAG, "setNotificationMessage: extras=" + extras);

    try {

      // Under extras:
      final String rawOp = extras.getString("rawOp");
      final String rawTo = extras.getString("rawTo");

      //final String id = extras.getString("id");
      //final String when = extras.getString("when");
      final String type = extras.getString("type");
      final String subType = extras.getString("subType");
      final String text = extras.getString("text");
      final String lock = extras.getString("lock");
      Log.i(LOG_TAG, "[payload] notId=" + notId + ", rawOp=" + rawOp + ", rawTo=" + rawTo + ", type=" + type + ", text=" + text + ", lock=" + lock);

      final String roomType = extras.getString("roomType");

      // 2018-11-26 EncProfile not send senderName, udenName
      final String chatroom = extras.getString("chatroom");
      final String senderCode = extras.getString("senderCode");
      String senderName = extras.getString("senderName");
      String udenName = extras.getString("udenName");

      if ("pushFeedMI".equals(rawOp) || "pushChatMI".equals(rawOp)) {

        // Common
        final StringBuilder buf = new StringBuilder();
        final Context context = getBaseContext();
        final File filesDir = context.getFilesDir();
        String[] rawToSplit = rawTo.split("/");
        final String me = rawToSplit[0] + "/" + rawToSplit[1];
        final String myPrefix = me.replaceAll("[/]", "__");
        final String myKEYCODE = myPrefix + "__KEYCODE"; // EX: "WAS__1.489__KEYCODE"

        // load udenMap cache (See t1_contact.loadUdenMapCache)
        // load chatlist cache
        // room type: friend, group
        // friend
        //   title  :: udenName (same as senderName for type friend)
        //   body  :: text
        // group
        //   title  :: senderName (solved by chatroom)
        //   body  :: udenName (solved by senderCode) : text

        // Solve senderName (group room name)
        if ("group".equals(roomType) && (senderName == null || senderName.isEmpty())) {
          // room name from chatlist cache
          senderName = ""; // Unknown Group

          // load chatlist cache
          // EX: "t1_chat/WAS__90001.88__chatlist.cache" (NOT ".cache.mb")
          File chatlistFile = new File(new File(filesDir, "t1_chat"), me.replace("/", "__") + "__chatlist.cache");
          JSONObject chatlistData = null;
          buf.setLength(0);
          if (EncryptUtils.readFileToBuf(buf, chatlistFile)) {
            String chatlistText = buf.toString();
            chatlistData = new JSONObject(chatlistText);
          }

          // find list[chatroom].name
          if (chatlistData != null) {
            JSONArray list = chatlistData.getJSONArray("list");
            for (int i = 0, size = list.length(); i < size; i++) {
              JSONObject item = list.getJSONObject(i);
              if (chatroom.equals(item.optString("chatroom"))) {
                senderName = item.optString("name"); // Untitled Group
                JSONObject room;
                try {
                  room = item.getJSONObject("room");
                } catch (Exception e) {
                  room = null;
                }
                if (room != null) {
                  JSONObject channelInfo;
                  try {
                    channelInfo = room.getJSONObject("channelInfo");
                  } catch (Exception e) {
                    channelInfo = null;
                  }

                  if(channelInfo != null){
                    String roomName0 = channelInfo.optString("name");
                    if(roomName0 == null || "".equals(roomName0)){
                      roomName0 = room.optString("name");
                    }

                    String channelJoinType = item.optString("channelJoinType");
                    if (roomName0 != null && channelJoinType != null && "provider".equals(channelJoinType)){
                      String custcode = channelInfo.optString("custcode");
                      String company = channelInfo.optString("company");
                      if (custcode != null && !"".equals(custcode)) {
                        roomName0 = "[" + custcode + "] " + roomName0;
                      } else if (company != null && !"".equals(company)) {
                        roomName0 = company + " " + roomName0;
                      }
                    }
                    senderName = roomName0;
                  }
                }

                break;
              }
            }
          }

          // not found senderName
          if (senderName == null || senderName.isEmpty()) {
            Log.w(LOG_TAG, "[pushChatMI] Cannot find group name for chatroom: " + chatroom);
            //Log.d(LOG_TAG, "[pushChatMI] DEBUG chatlistData: " + chatlistData);
          }
        }

        // Load udenMap from file
        // EX: "t1_contact/WAS__90001.91__udenMap.cache.mb"
        File udenMapFile = new File(new File(filesDir, "t1_contact"), myPrefix + "__udenMap.cache.mb");
        JSONObject udenMapData = null;
        buf.setLength(0);
        if (EncryptUtils.readFileToBuf(buf, udenMapFile)) {
          String udenMapText = buf.toString();
          udenMapData = new JSONObject(udenMapText);
        }

        // Solve udenName
        if (udenName == null || udenName.isEmpty()) {
          // uden name from udenMap cache
          udenName = ""; // Unknown Contact

          // udenMap[udenId]
          String udenId = senderCode.split("/")[1];
          if (udenMapData != null && udenMapData.has(udenId)) {
            JSONObject uden = udenMapData.getJSONObject(udenId);
            String nameAlias = uden.has("nameAlias") ? uden.getString("nameAlias") : "";
            if (nameAlias == null || nameAlias.isEmpty()) {
              udenName = uden.optString("name"); // Untitled Contact
            } else {
              udenName = nameAlias;
            }
          }
        }

        // Solve title
        boolean hasSubject = true;
        if ("notification".equals(type)) {
          // notification
          hasSubject = false; //(senderName != null && !senderName.isEmpty() && udenName != null && !udenName.isEmpty());
          title = "notification";
        } else if ("announcement".equals(roomType)) {
          // announcement
          title = "Announcement";
          hasSubject = (udenName != null && !udenName.isEmpty());
        } else if ("group".equals(roomType)) {
          // group
          hasSubject = (senderName != null && !senderName.isEmpty() && udenName != null && !udenName.isEmpty());
          if (senderName == null || senderName.isEmpty()) {
            title = "notification";
          } else if ("Meet".equals(senderName) && ("page".equals(type) || "link".equals(type))) {
            title = "@work Services";
          } else {
            title = senderName;
          }
        } else {
          // friend
          hasSubject = (udenName != null && !udenName.isEmpty());
          if (udenName == null || udenName.isEmpty()) {
            title = "notification";
          } else {
            title = udenName;
          }
        }
        Log.i(LOG_TAG, "[pushChatMI] Solve title: " + title);

        // Solve lock text
        if (lock != null && !lock.isEmpty() && text != null && !text.isEmpty() && ("link".equals(type) || "notification".equals(type) || "text".equals(type) || "topic".equals(type))) {
          final String[] lockSplit = lock.split("[:]");
          final String encrypted = text.trim();
          Log.d(LOG_TAG, "[payload] decrypt pushChatMI text which is locked: encrypted=" + encrypted);

          // Find keyText support both OLD/NEW way
          String keyText = null;
          final String refType = lockSplit[0]; // "R";
          final String refNode = lockSplit[1]; // "WAS__1.254" / Format: roomRegion + "__" + roomId;
          final String keyVer = lockSplit[2]; // "A"
          final String fn = myKEYCODE + "#" + refType + "#" + refNode + "#" + keyVer + ".mb";

          // Find keyText from keyFile (OLD way save each key)
          File keysDir = new File(filesDir, "keys");
          File keyFile = new File(keysDir, fn);

          // Read File into buf
          Log.d(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " => exists=" + keyFile.exists());
          buf.setLength(0);
          if (keyFile.exists() && EncryptUtils.readFileToBuf(buf, keyFile)) {
            String keyB64 = buf.toString();
            Log.d(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " => content='" + keyB64 + "'");
            keyText = keyB64.trim(); // readFileToBuf produce space
          }

          // Find keyText from keysMapFile (NEW way save all keys together)
          // Read keysMapFile e.g. WUS__1.2151__keys.cache.mb
          // NOTE keys.cache.mb under folder=keys and prefix with me
          if (keyText == null || keyText.isEmpty()) {
            File keysMapFile = new File(keysDir, myPrefix + "__keys.cache.mb");
            String keysMapText;
            JSONObject keysMapData = null;
            // Read File into buf and parse json
            Log.d(LOG_TAG, "Read keysMapFile=" + keysMapFile.getAbsolutePath() + " => exists=" + keysMapFile.exists());
            buf.setLength(0);
            if (keysMapFile.exists() && EncryptUtils.readFileToBuf(buf, keysMapFile)) {
              keysMapText = buf.toString();
              Log.d(LOG_TAG, "Read keysMapFile=" + keysMapFile.getAbsolutePath() + " => content.length='" + keysMapText.length() + "'");
              keysMapText = keysMapText.trim(); // readFileToBuf produce space
              keysMapData = new JSONObject(keysMapText);
              //Log.d(LOG_TAG, "Read keysMapFile=" + keysMapFile.getAbsolutePath() + " => " + keysMapData); // dump map of keys.cache.mb
            }
            // Lookup key e.g. WUS__1.2151__KEYCODE#R#WUS__5.1864#A.mb => same as fn but is key of map
            if (keysMapData != null) {
              keyText = keysMapData.getString(fn);
            }
          }

          // decrypt with key available
          if (keyText != null && !keyText.isEmpty()) {
            // 2018-05-30 Integrate Decrypt Push in Android AtWork2018
            String decrypted = EncryptUtils.aesgcmDecryptText(keyText, encrypted);
            Log.d(LOG_TAG, "encrypted=" + encrypted + ", decrypted=" + decrypted);
            String decoded = decrypted;
            // URLDecoder: Incomplete trailing escape (%) pattern
            //String decoded = URLDecoder.decode(decrypted, "UTF-8");

            Log.i(LOG_TAG, "Decrypt locked text '" + text + "' to '" + decoded + "'");
            message = decoded;

          } else {
            Log.w(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " => FILE NOT FOUND");
            if ("announcement".equals(roomType)) {
              message = "New announcement is released and needs your attention.";
              hasSubject = false;
            }
            if (hasSubject) {
              if ("link".equals(type))                message = "sent you a link.";
              else if ("notification".equals(type))   message = "sent you a notification.";
              else if ("text".equals(type))           message = "sent you a message.";
              else                                    message = "sent you a message.";
            }
          }

        }

        // Solve mentions in message
        // e.g. 'Test [@WAS/1.2234@]   after text'
        if (message != null && message.contains("[@") && message.contains("@]")) {
          Log.i(LOG_TAG, "Solving mentions in message '" + message + "' ...");
          buf.setLength(0);

          String m = message;
          boolean error = false;
          int txtFo = 0;
          int idxFo;
          int idxTo;
          do {

            // clip mention tag
            idxFo = m.indexOf("[@", txtFo);
            if (idxFo == -1) {
              // append current text (last)
              String currentText = m.substring(txtFo, m.length());
              buf.append(currentText);
              break;
            }
            idxTo = m.indexOf("@]", idxFo);
            if (idxTo == -1) {
              error = true;
              break;
            }
            Log.d(LOG_TAG, "[DEBUG] mentions: m='" + m + "', txtFo=" + txtFo + ", idxFo=" + idxFo + ", idxTo=" + idxTo);
            String currentText = m.substring(txtFo, idxFo);
            String mentionUden = m.substring(idxFo + 2, idxTo);
            txtFo = idxTo + 2;

            // append current text
            buf.append(currentText);

            // append mention name
            Log.i(LOG_TAG, "[DEBUG] mentions: mentionUden=" + mentionUden);

            String mentionName = "";
            if (mentionUden.contains("/")) { // e.g. WAS/1.23
              String udenId = mentionUden.split("/")[1];
              if (udenMapData != null && udenMapData.has(udenId)) {
                JSONObject uden = udenMapData.getJSONObject(udenId);
                String nameAlias = uden.has("nameAlias") ? uden.getString("nameAlias") : "";
                if (nameAlias == null || nameAlias.isEmpty()) {
                  mentionName = uden.optString("name"); // Untitled Contact
                } else {
                  mentionName = nameAlias;
                }
              } else {
                if (me.equals(mentionUden)) {
                  mentionName = "[me]";
                } else {
                  Log.w(LOG_TAG, "[WARN] mentions: cannot solve mentionUden=" + mentionUden);
                  error = true;
                  break;
                }
              }
            }
            if (mentionName == null || mentionName.isEmpty()) {
              // error
              error = true;
              break;
              // For testing, display mentionUden
              //buf.append("{{ERR:" + mentionUden + "}}");
              //continue;
            }
            buf.append(mentionName);

          } while (idxFo != -1);

          if (error) {
            Log.i(LOG_TAG, "Solving mentions fail: '" + message + "', txtFo=" + txtFo + ", idxFo=" + idxFo);
            if ("announcement".equals(roomType)) {
              //message = "We have an announcement!!";
              message = "New announcement is released and needs your attention.";
              hasSubject = false;
            } else if ("group".equals(roomType) && hasSubject) {
              message = "sent you a message.";
            } else {
              message = "You have got a message.";
            }
          } else {
            message = buf.toString(); // solved
            Log.i(LOG_TAG, "Solving mentions done: '" + message + "'");
          }
        }

        // Solve message
        // hasSubject for various types
                /*
                [Unresolved]
                   notification
                   you have got a file. (default message from server)
                [Solved / friend]
                   uden name
                   sent you a file.
                [Solved / group]
                   room name
                   uden name sent you a file.
                 */
        if (hasSubject) {
          if ("file".equals(type))       message = "sent you a file.";
          else if ("contact".equals(type))    message = "sent you a contact.";
          else if ("location".equals(type))   message = "sent you a location.";
          else if ("secret".equals(type))     message = "sent you a secret message.";
        }
        if ("page".equals(type)) {
          if (message.contains("%sender%")) {
            message = message.replaceAll("%sender%", udenName);
          }
        }
        else if ("topic".equals(type) && subType != null) {
          if ("create".equals(subType)) {
              message = "Topic has been created.";
          } else if ("delete".equals(subType)) {
              message = "Topic has been deleted.";
          } else {
              message = "Topic has been updated.";
          }
        }
        else if ("link".equals(type) && lock == null && text != null && !text.isEmpty()) {
          // Solve Meet@Work message
          message = text;
        }
        else if (("group".equals(roomType) || "announcement".equals(roomType)) && hasSubject) {
          // show sender udenName for roomType group
          String sep = "link".equals(type) || "text".equals(type) || "topic".equals(type) ? " : " : " ";
          message = udenName + sep + message;
        }

        Log.i(LOG_TAG, "[pushChatMI] Solve message: " + message);

      } else if ("pushMesg".equals(rawOp) && "text".equals(type) && lock != null && lock.length() > 0) {

        final JSONObject lockData = new JSONObject(lock);
        final String encrypted = text.trim();
        Log.d(LOG_TAG, "[payload] decrypt pushMesg text which is locked: encrypted=" + encrypted);

        // NOTE: Should avoid sending rawMesg JSON (still use rawMesgData.to)
        // Parse JSON in Android
        final String rawMesg = extras.getString("rawMesg");
        Log.d(LOG_TAG, "[payload] rawMesg=" + rawMesg);
        final JSONObject rawMesgData = new JSONObject(rawMesg);

        // myKEYCODE from rawMesg.to
        final String me = rawMesgData.getString("to"); // this message is push to me, then use to lookup myKEYCODE
        final String myKEYCODE = me.replaceAll("[/]", "__") + "__KEYCODE"; // EX: "WAS__1.489__KEYCODE"

        final String refType = lockData.getString("refType"); // "R";
        final String refNode = lockData.getString("refNode"); // "WAS__1.254" / Format: roomRegion + "__" + roomId;
        final String keyVer = lockData.getString("keyVer"); // "A"
        final String fn = myKEYCODE + "#" + refType + "#" + refNode + "#" + keyVer + ".mb";

        // Read File into buf
        final StringBuilder buf = new StringBuilder();
        final Context context = getBaseContext();
        final File filesDir = context.getFilesDir();
        File keysDir = new File(filesDir, "keys");
        File keyFile = new File(keysDir, fn);

        //Log.d(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " ...");
        if (EncryptUtils.readFileToBuf(buf, keyFile)) {
          String keyB64 = buf.toString(); // key
          Log.d(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " => content='" + keyB64 + "'");
          keyB64 = keyB64.trim(); // readFileToBuf produce space

          // Decrypt + Decode
          String decrypted = EncryptUtils.aesgcmDecryptText(keyB64, encrypted);
          Log.d(LOG_TAG, "encrypted=" + encrypted + ", decrypted=" + decrypted);
          String decoded = decrypted;
          // URLDecoder: Incomplete trailing escape (%) pattern
          //String decoded = URLDecoder.decode(decrypted, "UTF-8");

          // Modify message (show sender udenName for roomType group)
          if ("group".equals(roomType)) {
            message = udenName + " : " + decoded;
          } else {
            message = decoded;
          }
          Log.i(LOG_TAG, "Decrypt locked text '" + text + "' to '" + message + "'");

        } else {
          Log.w(LOG_TAG, "Read keyFile=" + keyFile.getAbsolutePath() + " => FILE NOT FOUND");
        }

      } else {
        // no lock or not support for rawOp+type
        Log.d(LOG_TAG, "Normal message: " + message);
      }

    } catch (JSONException ex) {
      Log.w(LOG_TAG, "Parse push payload json failed", ex);

    } catch (Exception ex) {
      Log.w(LOG_TAG, "Decrypt push failed", ex);

    }

    displayNotificationMessage(notId, extras, mBuilder, title, message);
  }

  // 2018-05-30 Extract method displayNotificationMessage out of setNotificationMessage
  // 2018-11-27 Support title and message
  // 2019-08-30 Repeat split setNotificationMessage/displayNotificationMessage from GCMIntentService to FCMService
  private void displayNotificationMessage(int notId, Bundle extras, NotificationCompat.Builder mBuilder, String title, String message) {
    String style = extras.getString(STYLE, STYLE_TEXT);
    if (STYLE_INBOX.equals(style)) {
      setNotification(notId, message);

      mBuilder.setContentText(message); // fromHtml(message)

      ArrayList<String> messageList = messageMap.get(notId);
      Integer sizeList = messageList.size();
      if (sizeList > 1) {
        String sizeListMessage = sizeList.toString();
        String stacking = sizeList + " more";
        if (extras.getString(SUMMARY_TEXT) != null) {
          stacking = extras.getString(SUMMARY_TEXT);
          stacking = stacking.replace("%n%", sizeListMessage);
        }
        NotificationCompat.InboxStyle notificationInbox = new NotificationCompat.InboxStyle();
        notificationInbox.setBigContentTitle(title); // fromHtml(title)
        notificationInbox.setSummaryText(stacking); // fromHtml(stacking)

        for (int i = messageList.size() - 1; i >= 0; i--) {
          notificationInbox.addLine(messageList.get(i)); // fromHtml(messageList.get(i))
        }

        mBuilder.setStyle(notificationInbox);
      } else {
        NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();
        if (message != null) {
          bigText.bigText(message); // fromHtml(message)
          bigText.setBigContentTitle(title); // fromHtml(title)
          mBuilder.setStyle(bigText);
        }
      }
    } else if (STYLE_PICTURE.equals(style)) {
      setNotification(notId, "");

      NotificationCompat.BigPictureStyle bigPicture = new NotificationCompat.BigPictureStyle();
      bigPicture.bigPicture(getBitmapFromURL(extras.getString(PICTURE)));
      bigPicture.setBigContentTitle(title); // fromHtml(title)
      bigPicture.setSummaryText(fromHtml(extras.getString(SUMMARY_TEXT)));

      mBuilder.setContentTitle(title); // fromHtml(title)
      mBuilder.setContentText(message); // fromHtml(message)

      mBuilder.setStyle(bigPicture);
    } else {
      setNotification(notId, "");

      NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();

      if (message != null) {
        mBuilder.setContentText(message); // fromHtml(message)

        bigText.bigText(message); // fromHtml(message)
        bigText.setBigContentTitle(title); // fromHtml(title)

        String summaryText = extras.getString(SUMMARY_TEXT);
        if (summaryText != null) {
          bigText.setSummaryText(fromHtml(summaryText));
        }

        mBuilder.setStyle(bigText);
      }
      /*
      else {
          mBuilder.setContentText("<missing message content>");
      }
      */
    }
  }

  private void setNotificationSound(Context context, Bundle extras, NotificationCompat.Builder mBuilder) {
    String soundname = extras.getString(SOUNDNAME);
    if (soundname == null) {
      soundname = extras.getString(SOUND);
    }
    if (SOUND_RINGTONE.equals(soundname)) {
      mBuilder.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI);
    } else if (soundname != null && !soundname.contentEquals(SOUND_DEFAULT)) {
      Uri sound = Uri
          .parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/raw/" + soundname);
      Log.d(LOG_TAG, sound.toString());
      mBuilder.setSound(sound);
    } else {
      mBuilder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
    }
  }

  private void setNotificationLedColor(Bundle extras, NotificationCompat.Builder mBuilder) {
    String ledColor = extras.getString(LED_COLOR);
    if (ledColor != null) {
      // Converts parse Int Array from ledColor
      String[] items = ledColor.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
      int[] results = new int[items.length];
      for (int i = 0; i < items.length; i++) {
        try {
          results[i] = Integer.parseInt(items[i].trim());
        } catch (NumberFormatException nfe) {
        }
      }
      if (results.length == 4) {
        mBuilder.setLights(Color.argb(results[0], results[1], results[2], results[3]), 500, 500);
      } else {
        Log.e(LOG_TAG, "ledColor parameter must be an array of length == 4 (ARGB)");
      }
    }
  }

  private void setNotificationPriority(Bundle extras, NotificationCompat.Builder mBuilder) {
    String priorityStr = extras.getString(PRIORITY);
    if (priorityStr != null) {
      try {
        Integer priority = Integer.parseInt(priorityStr);
        if (priority >= NotificationCompat.PRIORITY_MIN && priority <= NotificationCompat.PRIORITY_MAX) {
          mBuilder.setPriority(priority);
        } else {
          Log.e(LOG_TAG, "Priority parameter must be between -2 and 2");
        }
      } catch (NumberFormatException e) {
        e.printStackTrace();
      }
    }
  }

  private Bitmap getCircleBitmap(Bitmap bitmap) {
    if (bitmap == null) {
      return null;
    }

    final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
    final Canvas canvas = new Canvas(output);
    final int color = Color.RED;
    final Paint paint = new Paint();
    final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
    final RectF rectF = new RectF(rect);

    paint.setAntiAlias(true);
    canvas.drawARGB(0, 0, 0, 0);
    paint.setColor(color);
    float cx = bitmap.getWidth() / 2;
    float cy = bitmap.getHeight() / 2;
    float radius = cx < cy ? cx : cy;
    canvas.drawCircle(cx, cy, radius, paint);

    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    canvas.drawBitmap(bitmap, rect, rect, paint);

    bitmap.recycle();

    return output;
  }

  private void setNotificationLargeIcon(Bundle extras, String packageName, Resources resources,
      NotificationCompat.Builder mBuilder) {
    String gcmLargeIcon = extras.getString(IMAGE); // from gcm
    String imageType = extras.getString(IMAGE_TYPE, IMAGE_TYPE_SQUARE);
    if (gcmLargeIcon != null && !"".equals(gcmLargeIcon)) {
      if (gcmLargeIcon.startsWith("http://") || gcmLargeIcon.startsWith("https://")) {
        Bitmap bitmap = getBitmapFromURL(gcmLargeIcon);
        if (IMAGE_TYPE_SQUARE.equalsIgnoreCase(imageType)) {
          mBuilder.setLargeIcon(bitmap);
        } else {
          Bitmap bm = getCircleBitmap(bitmap);
          mBuilder.setLargeIcon(bm);
        }
        Log.d(LOG_TAG, "using remote large-icon from gcm");
      } else {
        AssetManager assetManager = getAssets();
        InputStream istr;
        try {
          istr = assetManager.open(gcmLargeIcon);
          Bitmap bitmap = BitmapFactory.decodeStream(istr);
          if (IMAGE_TYPE_SQUARE.equalsIgnoreCase(imageType)) {
            mBuilder.setLargeIcon(bitmap);
          } else {
            Bitmap bm = getCircleBitmap(bitmap);
            mBuilder.setLargeIcon(bm);
          }
          Log.d(LOG_TAG, "using assets large-icon from gcm");
        } catch (IOException e) {
          int largeIconId = 0;
          largeIconId = getImageId(resources, gcmLargeIcon, packageName);
          if (largeIconId != 0) {
            Bitmap largeIconBitmap = BitmapFactory.decodeResource(resources, largeIconId);
            mBuilder.setLargeIcon(largeIconBitmap);
            Log.d(LOG_TAG, "using resources large-icon from gcm");
          } else {
            Log.d(LOG_TAG, "Not setting large icon");
          }
        }
      }
    }
  }

  private int getImageId(Resources resources, String icon, String packageName) {
    int iconId = resources.getIdentifier(icon, DRAWABLE, packageName);
    if (iconId == 0) {
      iconId = resources.getIdentifier(icon, "mipmap", packageName);
    }
    return iconId;
  }

  private void setNotificationSmallIcon(Context context, Bundle extras, String packageName, Resources resources,
      NotificationCompat.Builder mBuilder, String localIcon) {
    int iconId = 0;
    String icon = extras.getString(ICON);
    if (icon != null && !"".equals(icon)) {
      iconId = getImageId(resources, icon, packageName);
      Log.d(LOG_TAG, "using icon from plugin options");
    } else if (localIcon != null && !"".equals(localIcon)) {
      iconId = getImageId(resources, localIcon, packageName);
      Log.d(LOG_TAG, "using icon from plugin options");
    }
    if (iconId == 0) {
      Log.d(LOG_TAG, "no icon resource found - using application icon");
      iconId = context.getApplicationInfo().icon;
    }
    mBuilder.setSmallIcon(iconId);
  }

  private void setNotificationIconColor(String color, NotificationCompat.Builder mBuilder, String localIconColor) {
    int iconColor = 0;
    if (color != null && !"".equals(color)) {
      try {
        iconColor = Color.parseColor(color);
      } catch (IllegalArgumentException e) {
        Log.e(LOG_TAG, "couldn't parse color from android options");
      }
    } else if (localIconColor != null && !"".equals(localIconColor)) {
      try {
        iconColor = Color.parseColor(localIconColor);
      } catch (IllegalArgumentException e) {
        Log.e(LOG_TAG, "couldn't parse color from android options");
      }
    }
    if (iconColor != 0) {
      mBuilder.setColor(iconColor);
    }
  }

  public Bitmap getBitmapFromURL(String strURL) {
    try {
      URL url = new URL(strURL);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(15000);
      connection.setDoInput(true);
      connection.connect();
      InputStream input = connection.getInputStream();
      return BitmapFactory.decodeStream(input);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static String getAppName(Context context) {
    CharSequence appName = context.getPackageManager().getApplicationLabel(context.getApplicationInfo());
    return (String) appName;
  }

  private int parseInt(String value, Bundle extras) {
    int retval = 0;

    try {
      retval = Integer.parseInt(extras.getString(value));
    } catch (NumberFormatException e) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
    } catch (Exception e) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
    }

    return retval;
  }

  private Spanned fromHtml(String source) {
    if (source != null)
      return Html.fromHtml(source);
    else
      return null;
  }

  private boolean isAvailableSender(String from) {
    SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH,
        Context.MODE_PRIVATE);
    String savedSenderID = sharedPref.getString(SENDER_ID, "");

    Log.d(LOG_TAG, "sender id = " + savedSenderID);

    return from.equals(savedSenderID) || from.startsWith("/topics/");
  }
}
