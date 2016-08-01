package com.example.android.sunshine.app.sync;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;

public class SendToWearService extends IntentService {
    public final String LOG_TAG = SendToWearService.class.getSimpleName();

    // message path for weather update info
    private static final String MESSAGE1_PATH = "/weatherUpdate";

    private GoogleApiClient mGoogleApiClient;
    private NodeApi.NodeListener nodeListener;
    private String remoteNodeId;

    public SendToWearService() {
        super("SendToWearService");
        //Log.d(LOG_TAG, "SendToWearService Constructor");
    }

    @Override
    protected void onHandleIntent(Intent workIntent) {
        //Log.d(LOG_TAG, "onHandleIntent");

        // set up and initiate wear communications
        setUpWearComms();
    }

    private void setUpWearComms() {

        //Log.d(LOG_TAG, "setUpWearComms");
        nodeListener = new NodeApi.NodeListener() {
            @Override
            public void onPeerConnected(Node node) {
                remoteNodeId = node.getId();
            }

            @Override
            public void onPeerDisconnected(Node node) {
            }
        };

        // Create GoogleApiClient
        Context context = this;
        mGoogleApiClient = new GoogleApiClient.Builder(context).addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
            @Override
            public void onConnected(Bundle bundle) {

                //Log.d(LOG_TAG, "GoogleApiClient onConnected");

                // Register Node listener
                Wearable.NodeApi.addListener(mGoogleApiClient, nodeListener);

                // If there is a connected node, get it's id that is used when sending messages
                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                    @Override
                    public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                        if (getConnectedNodesResult.getStatus().isSuccess() && getConnectedNodesResult.getNodes().size() > 0) {
                            remoteNodeId = getConnectedNodesResult.getNodes().get(0).getId();
                            //Log.d(LOG_TAG, "onResult from GetConnectedNodes - remoteNodeId: " + remoteNodeId);
                            //Log.d(LOG_TAG, " --> calling updateWear");
                            updateWear();
                        }
                    }
                });
            }

            @Override
            public void onConnectionSuspended(int i) {
                //Log.d(LOG_TAG, "GoogleApiClient onConnectionSuspended");
            }
        }).addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
            @Override
            public void onConnectionFailed(ConnectionResult connectionResult) {
                //Log.d(LOG_TAG, "GoogleApiClient onConnectionFailed");
                //if (connectionResult.getErrorCode() == ConnectionResult.API_UNAVAILABLE)
                //Toast.makeText(getApplicationContext(), getString(R.string.wearable_api_unavailable), Toast.LENGTH_LONG).show();
            }
        }).addApi(Wearable.API).build();

        // tell the Google API Client to connect now
        mGoogleApiClient.connect();
    }

    private void updateWear() {
        //Log.d(LOG_TAG, "updateWear *********");

        if (null == mGoogleApiClient) {
            //Log.d(LOG_TAG, "null == msGoogleApiClient");
            return;
        }

        if (!mGoogleApiClient.isConnected()) {
            //Log.d(LOG_TAG, "mGoogleApiClient is not connected!");
            if (mGoogleApiClient.isConnecting()) {
                //Log.d(LOG_TAG, "But, mGoogleApiClient is trying to connect");
            }
            // try to connect again
            mGoogleApiClient.connect();
        } else {
            //Log.d(LOG_TAG, "$$$ CORRECT! mGoogleApiClient is connected!");

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            String mHighTemp = sp.getString(SunshineSyncAdapter.LAST_HIGH_TEMP, "");
            String mLowTemp = sp.getString(SunshineSyncAdapter.LAST_LOW_TEMP, "");
            String mIcon = sp.getString(SunshineSyncAdapter.LAST_ICON, "800");
            String updated = sp.getString(SunshineSyncAdapter.LAST_UPDATE, "");
            //Log.d(LOG_TAG, "updateWear getInfoFromSharedPrefs - Hi Temp: " + mHighTemp + "  Low Temp: " + mLowTemp + " weather ID: " + mIcon + " updated: " + updated);

            String weather = mIcon + "," + mHighTemp + "," + mLowTemp + "," + updated;
            byte[] currentWeather = weather.getBytes(StandardCharsets.UTF_8);
            //Log.d(LOG_TAG, "---- weather ---- " + weather);

            Wearable.MessageApi.sendMessage(mGoogleApiClient, remoteNodeId, MESSAGE1_PATH, currentWeather).setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                @Override
                public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                    //Log.d(LOG_TAG, " onResult status: " + sendMessageResult.getStatus().toString());
                }
            });

            //Disconnect Google API client
            mGoogleApiClient.disconnect();
            mGoogleApiClient = null;
        }
    }
}

