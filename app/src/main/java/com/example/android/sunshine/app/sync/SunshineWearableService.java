package com.example.android.sunshine.app.sync;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.android.gms.common.api.GoogleApiClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Arjun on 05-Nov-2016 for Sunshine Wearable.
 */

public class SunshineWearableService extends WearableListenerService implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

    private static final String TAG = "SunWatchService";

    public static final String DATA_PATH = "/wearable/sunwatch/data";
    public static final String DATA_READY = "dataReady";

    private boolean isConnected;

    private GoogleApiClient apiClient;

    public void onCreate() {
        super.onCreate();

        apiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        apiClient.connect();
        Log.d(TAG, "Created..");
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        isConnected = true;
        Log.d(TAG, "Connected!!");
    }

    @Override
    public void onConnectionSuspended(int i) {
        isConnected = false;
        Log.d(TAG, "Connection suspended: " + i);
    }

    public void onPeerConnected(Node node) {
        super.onPeerConnected(node);

        Log.d(TAG, node.getDisplayName());
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        isConnected = false;
        Log.d(TAG, "Connection failed: " + connectionResult.getErrorMessage());
    }

    public void onMessageReceived(MessageEvent messageEvent) {
        if(messageEvent.getPath().equals(DATA_PATH)) {
            Log.d(TAG, "Received");
            if(new String(messageEvent.getData()).equals(DATA_READY)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sendDataToSunWatch(getAllRelevantForecastData());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    @Nullable
    private byte[] getAllRelevantForecastData() throws IOException {

        Cursor forecastData = getContentResolver().query(WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                Utility.getPreferredLocation(this), System.currentTimeMillis()),
                new String[] { WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
                               WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                               WeatherContract.WeatherEntry.COLUMN_MIN_TEMP },
                null,
                null,
                WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");

        if(forecastData == null)
            return null;

        if(!forecastData.moveToFirst()) {
            forecastData.close();
            return null;
        }

        Bitmap artResBitmap = BitmapFactory.decodeResource(getResources(), Utility.getArtResourceForWeatherCondition(forecastData.getInt(0)));
        String maxTemp = Utility.formatTemperature(this, forecastData.getDouble(1));
        String minTemp = Utility.formatTemperature(this, forecastData.getDouble(2));

        forecastData.close();

        artResBitmap = Bitmap.createScaledBitmap(artResBitmap,
                (int) getResources().getDimension(R.dimen.wearable_icon_size),
                (int) getResources().getDimension(R.dimen.wearable_icon_size),
                false);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        artResBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);

        List<byte[]> dataBytes = new ArrayList<>();
        dataBytes.add(outputStream.toByteArray());
        dataBytes.add(maxTemp.getBytes());
        dataBytes.add(minTemp.getBytes());

        outputStream.reset();

        ObjectOutputStream objectStream = new ObjectOutputStream(outputStream);
        objectStream.writeObject(dataBytes);

        return outputStream.toByteArray();
    }

    private void sendDataToSunWatch(byte[] data) {
        if(isConnected) {
            NodeApi.GetConnectedNodesResult result = Wearable.NodeApi.getConnectedNodes(apiClient).await();

            for(Node node : result.getNodes()) {
                Wearable.MessageApi.sendMessage(apiClient, node.getId(), DATA_PATH, data).await();
            }
        }
    }

    public void onDestroy() {
        super.onDestroy();
        if (apiClient != null && apiClient.isConnected()) {
            apiClient.disconnect();
        }
    }
}