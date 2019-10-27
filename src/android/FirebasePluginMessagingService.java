package org.apache.cordova.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.app.Notification;
import android.text.TextUtils;
import android.content.ContentResolver;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.commons.io.IOUtils;

public class FirebasePluginMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FirebasePlugin";

    /**
     * Get a string from resources without importing the .R package
     *
     * @param name Resource Name
     * @return Resource
     */
    private String getStringResource(String name) {
        return this.getString(
                this.getResources().getIdentifier(
                        name, "string", this.getPackageName()
                )
        );
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // [START_EXCLUDE]
        // There are two types of messages data messages and notification messages. Data messages are handled
        // here in onMessageReceived whether the app is in the foreground or background. Data messages are the type
        // traditionally used with GCM. Notification messages are only received here in onMessageReceived when the app
        // is in the foreground. When the app is in the background an automatically generated notification is displayed.
        // When the user taps on the notification they are returned to the app. Messages containing both notification
        // and data payloads are treated as notification messages. The Firebase console always sends notification
        // messages. For more see: https://firebase.google.com/docs/cloud-messaging/concept-options
        // [END_EXCLUDE]

        // Pass the message to the receiver manager so any registered receivers can decide to handle it
        boolean wasHandled = FirebasePluginMessageReceiverManager.onMessageReceived(remoteMessage);
        if (wasHandled) {
            Log.d(TAG, "Message was handled by a registered receiver");

            // Don't process the message in this method.
            return;
        }

        // TODO(developer): Handle FCM messages here.
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        String title = "";
        String text = "";
        String id = "";
        String sound = "";
        String lights = "";
        Map<String, String> data = remoteMessage.getData();

        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
            text = remoteMessage.getNotification().getBody();
            id = remoteMessage.getMessageId();
        } else if (data != null) {
            this.storeMessage(data);
            title = data.get("title");
            text = data.get("text");
            id = data.get("id");
            sound = data.get("sound");
            lights = data.get("lights"); //String containing hex ARGB color, miliseconds on, miliseconds off, example: '#FFFF00FF,1000,3000'

            if (TextUtils.isEmpty(text)) {
                text = data.get("body");
            }
        }

        if (TextUtils.isEmpty(id)) {
            Random rand = new Random();
            int n = rand.nextInt(50) + 1;
            id = Integer.toString(n);
        }

        Log.d(TAG, "From: " + remoteMessage.getFrom());
        Log.d(TAG, "Notification Message id: " + id);
        Log.d(TAG, "Notification Message Title: " + title);
        Log.d(TAG, "Notification Message Body/Text: " + text);
        Log.d(TAG, "Notification Message Sound: " + sound);
        Log.d(TAG, "Notification Message Lights: " + lights);

        // TODO: Add option to developer to configure if show notification when app on foreground
        if (!TextUtils.isEmpty(text) || !TextUtils.isEmpty(title) || (data != null && !data.isEmpty())) {
            boolean showNotification = (FirebasePlugin.inBackground() || !FirebasePlugin.hasNotificationsCallback()) && (!TextUtils.isEmpty(text) || !TextUtils.isEmpty(title));
            sendNotification(id, title, text, data, showNotification, sound, lights);
        }
    }

    private void sendNotification(String id, String title, String messageBody, Map<String, String> data, boolean showNotification, String sound, String lights) {
        Bundle bundle = new Bundle();
        bundle.putString("title", title);
        bundle.putString("body", messageBody);
        for (String key : data.keySet()) {
            bundle.putString(key, data.get(key));
        }

        if (showNotification) {
            Intent intent = new Intent(this, OnNotificationOpenReceiver.class);
            intent.putExtras(bundle);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT);

            String channelId = this.getStringResource("default_notification_channel_id");
            String channelName = this.getStringResource("default_notification_channel_name");
            Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
            notificationBuilder
                    .setContentTitle(title)
                    .setContentText(messageBody)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(messageBody))
                    .setAutoCancel(true)
                    .setSound(defaultSoundUri)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_MAX);

            String subText = data.get("subText");
            if (subText != null && !TextUtils.isEmpty(subText)) {
                notificationBuilder.setSubText(subText);
            }

            String largeIcon = data.get("largeIcon");
            if (largeIcon != null && !TextUtils.isEmpty(largeIcon)) {
                notificationBuilder.setLargeIcon(getBitmapFromURL(largeIcon));
            }

            String bigPicture = data.get("bigPicture");
            if (bigPicture != null && !TextUtils.isEmpty(bigPicture)) {
                notificationBuilder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(getBitmapFromURL(bigPicture)));
            }

            int resID = getResources().getIdentifier("notification_icon", "drawable", getPackageName());
            if (resID != 0) {
                notificationBuilder.setSmallIcon(resID);
            } else {
                notificationBuilder.setSmallIcon(getApplicationInfo().icon);
            }

            if (sound != null) {
                Log.d(TAG, "sound before path is: " + sound);
                Uri soundPath = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getPackageName() + "/raw/" + sound);
                Log.d(TAG, "Parsed sound is: " + soundPath.toString());
                notificationBuilder.setSound(soundPath);
            } else {
                Log.d(TAG, "Sound was null ");
            }

            int lightArgb = 0;
            if (lights != null) {
                try {
                    String[] lightsComponents = lights.replaceAll("\\s", "").split(",");
                    if (lightsComponents.length == 3) {
                        lightArgb = Color.parseColor(lightsComponents[0]);
                        int lightOnMs = Integer.parseInt(lightsComponents[1]);
                        int lightOffMs = Integer.parseInt(lightsComponents[2]);

                        notificationBuilder.setLights(lightArgb, lightOnMs, lightOffMs);
                    }
                } catch (Exception e) {
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                int accentID = getResources().getIdentifier("accent", "color", getPackageName());
                notificationBuilder.setColor(getResources().getColor(accentID, null));
            }

            Notification notification = notificationBuilder.build();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                int iconID = android.R.id.icon;
                int notiID = getResources().getIdentifier("notification_big", "drawable", getPackageName());
                if (notification.contentView != null) {
                    notification.contentView.setImageViewResource(iconID, notiID);
                }
            }
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // Since android Oreo notification channel is needed.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                List<NotificationChannel> channels = notificationManager.getNotificationChannels();

                boolean channelExists = false;
                for (int i = 0; i < channels.size(); i++) {
                    if (channelId.equals(channels.get(i).getId())) {
                        channelExists = true;
                    }
                }

                if (!channelExists) {
                    NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
                    channel.enableLights(true);
                    channel.enableVibration(true);
                    channel.setShowBadge(true);
                    if (lights != null) {
                        channel.setLightColor(lightArgb);
                    }
                    notificationManager.createNotificationChannel(channel);
                }
            }

            notificationManager.notify(id.hashCode(), notification);
        } else {
            bundle.putBoolean("tap", false);
            FirebasePlugin.sendNotification(bundle, this.getApplicationContext());
        }
    }

    private static Bitmap getBitmapFromURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);
            return myBitmap;
        } catch (IOException e) {
            // Log exception
            return null;
        }
    }

    private void storeMessage(Map<String,String> messageData) {
      try {
        JSONObject newMessage = new JSONObject(messageData);
        newMessage.put("tap", false);
        newMessage.put("toRead", true);

        JSONArray messages = this.getMessages();
        messages.put(newMessage);

        this.updateLocalMessages(messages);
      } catch (JSONException e) {
          e.printStackTrace();
          Log.d(TAG, "storeMessage: " + e.toString());
      }
    }

    private JSONArray getMessages() throws JSONException {
        String fileContent = "[]";
        String filename = "messages.json";
        try {
            Context context = this.getApplicationContext();
            File file = new File(context.getFilesDir(), filename);
            byte[] bytesArray = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            fis.read(bytesArray);
            fis.close();
            fileContent = new String(bytesArray);
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "Message file content: " + e.toString());
            this.initLocalMessages();
        }
        return new JSONArray(fileContent);
    }

    private void initLocalMessages() {
      this.updateLocalMessages(new JSONArray());
    }

    private void updateLocalMessages(JSONArray messages) {
      Context context = this.getApplicationContext();
      String filename = "messages.json";
      File file = new File(context.getFilesDir(), filename);
      String fileContents = messages.toString();
      FileOutputStream outputStream;
      try {
          outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
          outputStream.write(fileContents.getBytes());
          outputStream.close();
      } catch (Exception e) {
          e.printStackTrace();
          Log.d(TAG, "Message Store: " + e.toString());
      }
    }
}
