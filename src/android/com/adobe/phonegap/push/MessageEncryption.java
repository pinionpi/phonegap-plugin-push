package com.adobe.phonegap.push;

import static com.adobe.phonegap.push.PushConstants.*;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.eunite.atwork.EncryptUtils;
import com.google.firebase.messaging.FirebaseMessagingService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

import androidx.core.app.NotificationCompat;

// 2024-03-17 Bridge code from old FCMService.java to call setNotificationMessage from FCMServer.kt
public class MessageEncryption {

    private static final String LOG_TAG = "Push_FCMService";

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
    public static void setNotificationMessage(FCMService svc, int notId, Bundle extras, NotificationCompat.Builder mBuilder) {
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
                final Context context = svc.getBaseContext();
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

                                    if (channelInfo != null) {
                                        // Gateway room name order
                                        String roomName0 = "";
                                        String channelJoinType = item.optString("channelJoinType");
                                        // Default name from channelInfo, room
                                        if (roomName0 == null || "".equals(roomName0)) {
                                            roomName0 = channelInfo.optString("name");
                                        }
                                        if (roomName0 == null || "".equals(roomName0)) {
                                            roomName0 = room.optString("name");
                                        }
                                        if (channelJoinType != null && "provider".equals(channelJoinType)) {
                                            String providerRoomName = channelInfo.optString("providerRoomName");
                                            String custcode = channelInfo.optString("custcode");
                                            String company = channelInfo.optString("company");
                                            if (providerRoomName != null && !"".equals(providerRoomName)) {
                                                roomName0 = providerRoomName;
                                            } else if (custcode != null && !"".equals(custcode)) {
                                                roomName0 = "[" + custcode + "] " + roomName0;
                                            } else if (company != null && !"".equals(company)) {
                                                roomName0 = company + " " + roomName0;
                                            }
                                        }
                                        // customerRoomName
                                        if (channelJoinType != null && "customer".equals(channelJoinType)) {
                                            roomName0 = channelInfo.optString("customerRoomName");
                                        }
                                        // Fallback to name from channelInfo, room
                                        if (roomName0 == null || "".equals(roomName0)) {
                                            roomName0 = channelInfo.optString("name");
                                        }
                                        if (roomName0 == null || "".equals(roomName0)) {
                                            roomName0 = room.optString("name");
                                        }
                                        senderName = roomName0;
                                        Log.w(LOG_TAG, "[channel] channelJoinType=" + channelJoinType + ", senderName=" + senderName);
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
                if (lock != null && !lock.isEmpty() && text != null && !text.isEmpty() && ("link".equals(type) || "notification".equals(type) || "text".equals(type) || "topic".equals(type) || "file".equals(type) || "survey".equals(type) || "page".equals(type))) {
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

                boolean isYouHaveAnnounceText = "You have a new announcement.".equals(message);
                boolean hasText = message != null && message.length() > 0 && !isYouHaveAnnounceText;
                boolean isFileWithText = "file".equals(type) && hasText;
                boolean isSurveyWithText = "survey".equals(type) && hasText;

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
                    // if ("file".equals(type))       message = "sent you a file.";
                    if ("file".equals(type) && !hasText)  message = "sent you a file.";
                    else if ("contact".equals(type))    message = "sent you a contact.";
                    else if ("location".equals(type))   message = "sent you a location.";
                    else if ("secret".equals(type))     message = "sent you a secret message.";
                    else if ("survey".equals(type) && !hasText) message = "sent you a survey.";
                    else if ("page".equals(type) && !hasText) message = "sent you a message.";
                }
                if ("page".equals(type)) {
                    if (message.contains("%sender%")) {
                        message = message.replaceAll("%sender%", udenName);
                    } else {
                        message = udenName + " : " + message;
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
                    // String sep = "link".equals(type) || "text".equals(type) || "topic".equals(type) ? " : " : " ";
                    String sep = "link".equals(type) || "text".equals(type) || "topic".equals(type) || isFileWithText || isSurveyWithText ? " : " : " ";
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
                final Context context = svc.getBaseContext();
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

        displayNotificationMessage(svc, notId, extras, mBuilder, title, message);
    }

    // 2018-05-30 Extract method displayNotificationMessage out of setNotificationMessage
    // 2018-11-27 Support title and message
    // 2019-08-30 Repeat split setNotificationMessage/displayNotificationMessage from GCMIntentService to FCMService
    private static void displayNotificationMessage(FCMService svc, int notId, Bundle extras, NotificationCompat.Builder mBuilder, String title, String message) {
        String style = extras.getString(STYLE, STYLE_TEXT);
        if (PushConstants.STYLE_INBOX.equals(style)) {
            svc.setNotification(notId, message);

            mBuilder.setContentText(message); // fromHtml(message)

            ArrayList<String> messageList = svc.messageMap.get(notId);
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
            svc.setNotification(notId, "");

            NotificationCompat.BigPictureStyle bigPicture = new NotificationCompat.BigPictureStyle();
            bigPicture.bigPicture(svc.getBitmapFromURL(extras.getString(PICTURE)));
            bigPicture.setBigContentTitle(title); // fromHtml(title)
            bigPicture.setSummaryText(svc.fromHtml(extras.getString(SUMMARY_TEXT)));

            mBuilder.setContentTitle(title); // fromHtml(title)
            mBuilder.setContentText(message); // fromHtml(message)

            mBuilder.setStyle(bigPicture);
        } else {
            svc.setNotification(notId, "");

            NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();

            if (message != null) {
                mBuilder.setContentText(message); // fromHtml(message)

                bigText.bigText(message); // fromHtml(message)
                bigText.setBigContentTitle(title); // fromHtml(title)

                String summaryText = extras.getString(SUMMARY_TEXT);
                if (summaryText != null) {
                    bigText.setSummaryText(svc.fromHtml(summaryText));
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

}
