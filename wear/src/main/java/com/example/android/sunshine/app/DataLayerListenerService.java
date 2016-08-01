/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Listens to DataItems and Messages from the local node.
 *
 * Taken from Google example code July, 2016
 */
public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "DataLayerService";

//    private static final String START_ACTIVITY_PATH = "/start-activity";
    private static final String DATA_ITEM_RECEIVED_PATH = "/data-item-received";
    public static final String COUNT_PATH = "/count";
//    public static final String IMAGE_PATH = "/image";
//    public static final String IMAGE_KEY = "photo";

    private final String MESSAGE1_PATH = "/weatherUpdate";

    // keys for shared prefs
    public static final String LAST_HIGH_TEMP = "LastHighTemp";
    public static final String LAST_LOW_TEMP = "LastLowTemp";
    public static final String LAST_ICON = "LastIcon";
    public static final String LAST_UPDATE = "LastUpdate";

    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        //LOGD(TAG, "onDataChanged: " + dataEvents);
        if (!mGoogleApiClient.isConnected() || !mGoogleApiClient.isConnecting()) {
            ConnectionResult connectionResult = mGoogleApiClient
                    .blockingConnect(30, TimeUnit.SECONDS);
            if (!connectionResult.isSuccess()) {
                Log.e(TAG, "DataLayerListenerService failed to connect to GoogleApiClient, "
                        + "error code: " + connectionResult.getErrorCode());
                return;
            }
        }

        // Loop through the events and send a message back to the node that created the data item.
        for (DataEvent event : dataEvents) {
            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();
            if (COUNT_PATH.equals(path)) {
                // Get the node id of the node that created the data item from the host portion of
                // the uri.
                String nodeId = uri.getHost();
                // Set the data of the message to be the bytes of the Uri.
                byte[] payload = uri.toString().getBytes();

                // Send the rpc
                Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, DATA_ITEM_RECEIVED_PATH,
                        payload);
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        //LOGD(TAG, "onMessageReceived: " + messageEvent);

        if (messageEvent.getPath().equals(MESSAGE1_PATH)) {
            // the message contains a weather update like this: "500,25,16, 21:42 - JUL 31 2016"
            // 500 is the current condition icon
            // 25 is the high
            // 16 is the low
            // 21:42 - JUL 31 2016 is the time and date the weather info was received on the mobile
            String newWeather = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            //Log.d(TAG, "onMessageReceived: " + newWeather);

            List<String> weatherDetails = Arrays.asList(newWeather.split(","));

            String mIcon = weatherDetails.get(0);
            String mHighTemp = weatherDetails.get(1);
            String mLowTemp = weatherDetails.get(2);
            String mUpdated = weatherDetails.get(3);

            // save the latest data in shared preferences for display by the watchface
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(DataLayerListenerService.this);
            SharedPreferences.Editor editor = sp.edit();
            editor.putString(LAST_HIGH_TEMP, mHighTemp);
            editor.putString(LAST_LOW_TEMP, mLowTemp);
            editor.putString(LAST_ICON, mIcon);
            editor.putString(LAST_UPDATE, mUpdated);
            editor.apply();

            //LOGD(TAG, "onMessageReceived - mLastHiTemp: " + mHighTemp + " mLastLowTemp: " + mLowTemp);
        }
    }

    public static void LOGD(final String tag, String message) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, message);
        }
    }
}