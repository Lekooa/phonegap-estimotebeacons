/*
Android implementation of Cordova plugin for Estimote Beacons.

JavaDoc for Estimote Android API: https://estimote.github.io/Android-SDK/JavaDocs/
*/

package com.evothings;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.util.Log;

import com.estimote.coresdk.common.config.EstimoteSDK;
import com.estimote.coresdk.observation.region.RegionUtils;
import com.estimote.coresdk.observation.region.beacon.BeaconRegion;
import com.estimote.coresdk.observation.utils.Proximity;
import com.estimote.coresdk.recognition.packets.Beacon;
import com.estimote.coresdk.recognition.packets.ConfigurableDevice;
import com.estimote.coresdk.service.BeaconManager;
import com.estimote.mgmtsdk.common.exceptions.DeviceConnectionException;
import com.estimote.mgmtsdk.connection.api.DeviceConnection;
import com.estimote.mgmtsdk.connection.api.DeviceConnectionCallback;
import com.estimote.mgmtsdk.connection.api.DeviceConnectionProvider;
import com.estimote.mgmtsdk.feature.settings.SettingCallback;
import com.estimote.mgmtsdk.feature.settings.api.Settings;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plugin class for the Estimote Beacon plugin.
 */
public class EstimoteBeacons extends CordovaPlugin {
    private static final String LOGTAG = "EstimoteBeacons";
    //private static final String ESTIMOTE_DEFAULT_PROXIMITY_UUID = "B9407F30-F5F8-466E-AFF9-25556B57FE6D";
    private static final int REQUEST_ENABLE_BLUETOOTH = 1;

    private CordovaInterface mCordovaInterface;
    private EstimoteSDK mEstimoteSDK;
    private BeaconManager mBeaconManager;

    private ArrayList<ConfigurableDevice> mRangedDevices;
    private DeviceConnected mConnectedDevice;
    private boolean mIsConnected;

    // Maps and variables that keep track of Cordova callbacks.
    private HashMap<String, CallbackContext> mRangingCallbackContexts;
    private HashMap<String, CallbackContext> mMonitoringCallbackContexts;
    private CallbackContext mDiscoveringCallbackContext;

    private CallbackContext mBluetoothStateCallbackContext;
    private CallbackContext mDeviceConnectionCallback;
    private CallbackContext mDeviceDisconnectionCallback;

    /**
     * Create JSON object representing a BeaconRegion.
     */
    private static JSONObject makeJSONBeaconRegion(BeaconRegion region) throws JSONException {
        return makeJSONBeaconRegion(region, null);
    }

    /**
     * Create JSON object representing a region in the given state.
     */
    private static JSONObject makeJSONBeaconRegion(BeaconRegion region, String state) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("identifier", region.getIdentifier());
        json.put("uuid", region.getProximityUUID());
        json.put("major", region.getMajor());
        json.put("minor", region.getMinor());
        if (state != null) {
            json.put("state", state);
        }
        return json;
    }

    /**
     * Plugin initializer.
     */
    @Override
    public void pluginInitialize() {
        super.pluginInitialize();
        Log.i(LOGTAG, "pluginInitialize");

        mCordovaInterface = cordova;
        mCordovaInterface.setActivityResultCallback(this);

        if (mBeaconManager == null) {
            mBeaconManager = new BeaconManager(mCordovaInterface.getActivity());
        }
        mBeaconManager.setErrorListener(new BeaconManager.ErrorListener() {
            @Override
            public void onError(Integer integer) {
                Log.e(LOGTAG, "BeaconManager error: " + integer);
            }
        });

        mRangedDevices = new ArrayList<ConfigurableDevice>();
        mIsConnected = false;

        mRangingCallbackContexts = new HashMap<String, CallbackContext>();
        mMonitoringCallbackContexts = new HashMap<String, CallbackContext>();
    }

    /**
     * Plugin reset.
     * Called when the WebView does a top-level navigation or refreshes.
     */
    @Override
    public void onReset() {
        super.onReset();
        Log.i(LOGTAG, "onReset");

        disconnectBeaconManager();

        mRangingCallbackContexts = new HashMap<String, CallbackContext>();
        mMonitoringCallbackContexts = new HashMap<String, CallbackContext>();
        mDiscoveringCallbackContext = null;
    }

    /**
     * The final call you receive before your activity is destroyed.
     *  
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOGTAG, "onDestroy");

        disconnectConnectedDevice();
        disconnectBeaconManager();
    }

    /**
     * Disconnect from the beacon manager.
     *  
     */
    private void disconnectBeaconManager() {
        if (mBeaconManager != null && mIsConnected) {
            mBeaconManager.disconnect();
            mIsConnected = false;
        }
    }

    /**
     * Entry point for JavaScript calls.
     */
    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
            throws JSONException {
        boolean res = true;

        if ("beacons_startRangingBeaconsInRegion".equals(action)) {
            startRangingBeaconsInRegion(args, callbackContext);
        } else if ("beacons_stopRangingBeaconsInRegion".equals(action)) {
            stopRangingBeaconsInRegion(args, callbackContext);
        } else if ("beacons_startMonitoringForRegion".equals(action)) {
            startMonitoringForRegion(args, callbackContext);
        } else if ("beacons_stopMonitoringForRegion".equals(action)) {
            stopMonitoringForRegion(args, callbackContext);
        } else if ("beacons_startDiscoveringDevices".equals(action)) {
            startDiscoveringDevices(callbackContext);
        } else if ("beacons_stopDiscoveringDevices".equals(action)) {
            stopDiscoveringDevices(callbackContext);
        } else if ("beacons_setupAppIDAndAppToken".equals(action)) {
            setupAppIDAndAppToken(args, callbackContext);
        } else if ("beacons_connectToDevice".equals(action)) {
            connectToDevice(args, callbackContext);
        } else if ("beacons_disconnectFromDevice".equals(action)) {
            disconnectConnectedDevice(callbackContext);
        } else if ("beacons_writeConnectedProximityUUID".equals(action)) {
            writeConnectedProximityUUID(args, callbackContext);
        } else if ("beacons_writeConnectedMajor".equals(action)) {
            writeConnectedMajor(args, callbackContext);
        } else if ("beacons_writeConnectedMinor".equals(action)) {
            writeConnectedMinor(args, callbackContext);
        } else if ("bluetooth_bluetoothState".equals(action)) {
            checkBluetoothState(callbackContext);
        } else {
            res = false;
        }

        return res;
    }

    /**
     * If Bluetooth is off, open a Bluetooth dialog.
     */
    private void checkBluetoothState(final CallbackContext callbackContext) throws JSONException {
        Log.i(LOGTAG, "checkBluetoothState");

        // Check that no Bluetooth state request is in progress.
        if (mBluetoothStateCallbackContext != null) {
            callbackContext.error("Bluetooth state request already in progress");
        } else {
            // Check if Bluetooth is enabled.
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (!bluetoothAdapter.isEnabled()) {
                // Open Bluetooth dialog on the UI thread.
                final CordovaPlugin self = this;
                mBluetoothStateCallbackContext = callbackContext;
                Runnable openBluetoothDialog = new Runnable() {
                    public void run() {
                        Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        mCordovaInterface.startActivityForResult(self, enableIntent, REQUEST_ENABLE_BLUETOOTH);
                    }
                };
                mCordovaInterface.getActivity().runOnUiThread(openBluetoothDialog);
            } else {
                // Bluetooth is enabled, return the result to JavaScript,
                sendResultForBluetoothEnabled(callbackContext);
            }
        }
    }

    /**
     * Check if Bluetooth is enabled and return result to JavaScript.
     */
    private void sendResultForBluetoothEnabled(CallbackContext callbackContext) {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            callbackContext.success(1);
        } else {
            callbackContext.success(0);
        }
    }

    /**
     * Called when the Bluetooth dialog is closed.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.i(LOGTAG, "onActivityResult");

        if (REQUEST_ENABLE_BLUETOOTH == requestCode) {
            sendResultForBluetoothEnabled(mBluetoothStateCallbackContext);
            mBluetoothStateCallbackContext = null;
        }
    }

    /**
     * Start ranging for beacons.
     */
    private void startRangingBeaconsInRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "startRangingBeaconsInRegion");

        JSONObject json = cordovaArgs.getJSONObject(0);
        // The region is final because used in the onServiceReady method.
        final BeaconRegion region = createBeaconRegion(json);

        // Check the region validity.
        if (region != null) {
            String key = beaconRegionHashMapKey(region);

            if (mRangingCallbackContexts.get(key) == null) {
                // Add callback to hash map.
                mRangingCallbackContexts.put(key, callbackContext);

                // Create ranging listener.
                mBeaconManager.setRangingListener(new PluginRangingListener());

                // If connected start ranging immediately, otherwise first connect.
                if (mIsConnected) {
                    startRanging(region, callbackContext);
                } else {
                    Log.i(LOGTAG, "connect");

                    mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                        @Override
                        public void onServiceReady() {
                            Log.i(LOGTAG, "onServiceReady");

                            mIsConnected = true;
                            startRanging(region, callbackContext);
                        }
                    });
                }
            }
        } else {
            callbackContext.error("Invalid UUID.");
        }
    }

    /**
     * Helper method.
     */
    private void startRanging(BeaconRegion region, CallbackContext callbackContext) {
        try {
            Log.i(LOGTAG, "startRanging region: " + beaconRegionHashMapKey(region));

            mBeaconManager.startRanging(region);
        } catch (Exception e) {
            Log.e(LOGTAG, "startRanging error:", e);

            callbackContext.error("Start ranging RemoteException");
        }
    }

    /**
     * Stop ranging for beacons.
     */
    private void stopRangingBeaconsInRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "stopRangingBeaconsInRegion");

        JSONObject json = cordovaArgs.getJSONObject(0);
        BeaconRegion region = createBeaconRegion(json);

        // Check the region validity.
        if (region != null) {
            String key = beaconRegionHashMapKey(region);

            CallbackContext rangingCallback = mRangingCallbackContexts.get(key);
            // If ranging callback does not exist call error callback
            if (rangingCallback == null) {
                callbackContext.error("Region not ranged");
            } else {
                // Remove ranging callback from hash map.
                mRangingCallbackContexts.remove(key);

                // Clear ranging callback on JavaScript side.
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(false);
                rangingCallback.sendPluginResult(result);

                // Stop ranging if connected.
                if (mIsConnected) {
                    try {
                        Log.i(LOGTAG, "stopRanging region: " + beaconRegionHashMapKey(region));

                        // Stop ranging.
                        mBeaconManager.stopRanging(region);

                        // Send back success.
                        callbackContext.success();
                    } catch (Exception e) {
                        Log.e(LOGTAG, "stopRanging", e);

                        callbackContext.error("stopRanging RemoteException");
                    }
                } else {
                    callbackContext.error("Not connected");
                }
            }
        } else {
            callbackContext.error("Invalid UUID.");
        }
    }

    /**
     * Start monitoring for region.
     */

    private void startMonitoringForRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "startMonitoringForRegion");

        JSONObject json = cordovaArgs.getJSONObject(0);
        final BeaconRegion region = createBeaconRegion(json);

        // Check the region validity.
        if (region != null) {

            String key = beaconRegionHashMapKey(region);

            if (mMonitoringCallbackContexts.get(key) == null) {
                // Add callback to hash map.
                mMonitoringCallbackContexts.put(key, callbackContext);

                // Create monitoring listener.
                mBeaconManager.setMonitoringListener(new PluginMonitoringListener());

                // If connected start monitoring immediately, otherwise first connect.
                if (mIsConnected) {
                    startMonitoring(region, callbackContext);
                } else {
                    Log.i(LOGTAG, "connect");

                    mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                        @Override
                        public void onServiceReady() {
                            Log.i(LOGTAG, "onServiceReady");

                            mIsConnected = true;
                            startMonitoring(region, callbackContext);
                        }
                    });
                }
            }
        } else {
            callbackContext.error("Invalid region.");
        }
    }

    /**
     * Helper method.
     */
    private void startMonitoring(BeaconRegion region, CallbackContext callbackContext) {
        try {
            Log.i(LOGTAG, "startMonitoring");

            mBeaconManager.startMonitoring(region);
        } catch (Exception e) {
            Log.e(LOGTAG, "startMonitoring error:", e);

            callbackContext.error("startMonitoring RemoteException");
        }
    }

    /**
     * Stop monitoring for region.
     */
    private void stopMonitoringForRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "stopMonitoringForRegion");

        JSONObject json = cordovaArgs.getJSONObject(0);
        BeaconRegion region = createBeaconRegion(json);

        // Check the region validity.
        if (region != null) {

            String key = beaconRegionHashMapKey(region);

            CallbackContext monitoringCallback = mMonitoringCallbackContexts.get(key);
            // If monitoring callback does not exist call error callback
            if (monitoringCallback == null) {
                callbackContext.error("Region not monitored");
            } else {
                // Remove monitoring callback from hash map.
                mMonitoringCallbackContexts.remove(key);

                // Clear monitoring callback on JavaScript side.
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(false);
                monitoringCallback.sendPluginResult(result);

                // Stop monitoring if connected.
                if (mIsConnected) {
                    try {
                        Log.i(LOGTAG, "stopMonitoring");

                        // Stop monitoring.
                        mBeaconManager.stopMonitoring(beaconRegionHashMapKey(region));

                        // Send back success.
                        callbackContext.success();
                    } catch (Exception e) {
                        Log.e(LOGTAG, "stopMonitoring", e);

                        callbackContext.error("stopMonitoring RemoteException");
                    }
                } else {
                    callbackContext.error("Not connected");
                }
            }
        } else {
            callbackContext.error("Invalid region.");
        }
    }

    /**
     * Start discovering connectivity packets.
     */
    private void startDiscoveringDevices(final CallbackContext callbackContext) throws JSONException {
        Log.i(LOGTAG, "startDiscoveringDevices");

        if (mDiscoveringCallbackContext == null) {
            mDiscoveringCallbackContext = callbackContext;
            // Create discovering listener.
            mBeaconManager.setConfigurableDevicesListener(new PluginDiscoveringListener());

            // If connected start discovering immediately, otherwise first connect.
            if (mIsConnected) {
                startDiscovering(callbackContext);
            } else {
                Log.i(LOGTAG, "connect");

                mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                    @Override
                    public void onServiceReady() {
                        Log.i(LOGTAG, "onServiceReady");

                        mIsConnected = true;
                        startDiscovering(callbackContext);
                    }
                });
            }
        }
    }

    /**
     * Helper method.
     */
    private void startDiscovering(CallbackContext callbackContext) {
        try {
            Log.i(LOGTAG, "startDiscovering");

            mBeaconManager.startConfigurableDevicesDiscovery();
        } catch (Exception e) {
            Log.e(LOGTAG, "startDiscovering error:", e);

            callbackContext.error("startDiscovering RemoteException");
        }
    }

    /**
     * Stop discovering for connectivity packets.
     */
    private void stopDiscoveringDevices(final CallbackContext callbackContext) throws JSONException {
        Log.i(LOGTAG, "stopDiscoveringDevices");

        // If discovering callback does not exist call error callback
        if (mDiscoveringCallbackContext == null) {
            callbackContext.error("Region not being discovered");
        } else {
            // Clear discovering callback on JavaScript side.
            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(false);
            mDiscoveringCallbackContext.sendPluginResult(result);
            mDiscoveringCallbackContext = null;

            // Stop ranging if connected.
            if (mIsConnected) {
                try {
                    Log.i(LOGTAG, "stopDiscovering");

                    // Stop ranging.
                    mBeaconManager.stopConfigurableDevicesDiscovery();

                    // Send back success.
                    callbackContext.success();
                } catch (Exception e) {
                    Log.e(LOGTAG, "stopDiscovering", e);

                    callbackContext.error("stopDiscovering RemoteException");
                }
            } else {
                callbackContext.error("not connected");
            }
        }
    }

    /**
     * Authenticate with Estimote Cloud
     */
    private void setupAppIDAndAppToken(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "setupAppIDAndAppToken");

        if (mEstimoteSDK == null) {
            mEstimoteSDK = new EstimoteSDK();

            EstimoteSDK.initialize(cordova.getActivity(), cordovaArgs.getString(0), cordovaArgs.getString(1));

            PluginResult r = new PluginResult(PluginResult.Status.OK);
            callbackContext.sendPluginResult(r);
        } else {
            callbackContext.error("already authenticated to Estimote Cloud: " + EstimoteSDK.getAppId());
        }
    }

    /**
     * Find device in rangedDevices, with MAC address
     */
    private ConfigurableDevice findDevice(String macAddress) {
        Log.i(LOGTAG, "findDevice(String)");

        int size = mRangedDevices.size();
        ConfigurableDevice current = null;
        boolean searching = true;
        while (searching && size > 0) {
            current = mRangedDevices.get(size - 1);
            if (current.macAddress.toString().equals(macAddress)) {
                searching = false;
            } else {
                size--;
            }
        }
        if (searching) {
            current = null;
        }

        return current;
    }

    /**
     * Find device in rangedDevices, from JSON
     */
    private ConfigurableDevice findDevice(JSONObject json) throws JSONException {
        String macAddress = json.optString("macAddress", "");
        return findDevice(macAddress);
    }

    /**
     * Connect to a device
     */
    private void connectToDevice(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        Log.i(LOGTAG, "connectToDevice");

        JSONObject json = cordovaArgs.getJSONObject(0);
        final ConfigurableDevice device = findDevice(json);

        if (device == null) {
            callbackContext.error("could not find device");
        } else {
            // devices are jealous creatures and don't like competition
            if (mConnectedDevice != null && !mConnectedDevice.getDevice().macAddress.equals(device.macAddress)) {
                disconnectConnectedDevice();
            }
            mDeviceConnectionCallback = callbackContext;
            final DeviceConnectionProvider deviceConnectionProvider = new DeviceConnectionProvider(
                    cordova.getActivity());

            deviceConnectionProvider.connectToService(new DeviceConnectionProvider.ConnectionProviderCallback() {
                @Override
                public void onConnectedToService() {
                    Log.i(LOGTAG, "onConnectedToService");

                    mConnectedDevice = new DeviceConnected(deviceConnectionProvider.getConnection(device), device);
                    mConnectedDevice.getDeviceConnection().connect(new PluginDeviceConnectionCallback());
                }
            });
        }
    }

    /**
     * Disconnect connected beacon
     */
    private void disconnectConnectedDevice() {
        Log.i(LOGTAG, "disconnectConnectedDevice");

        if (mConnectedDevice != null && mConnectedDevice.getDeviceConnection().isConnected()) {
            mConnectedDevice.getDeviceConnection().close();
            mConnectedDevice.setDeviceConnection(null);
        }
    }

    /**
     * Disconnect connected device, c/o Cordova
     */
    private void disconnectConnectedDevice(final CallbackContext callbackContext) throws JSONException {
        Log.i(LOGTAG, "disconnectConnectedDevice (cordova)");

        mDeviceDisconnectionCallback = callbackContext;
        disconnectConnectedDevice();
    }

    /**
     * Write Proximity UUID to connected beacon
     */
    private void writeConnectedProximityUUID(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "writeConnectedProximityUUID");

        if (mConnectedDevice != null && mConnectedDevice.getDeviceConnection().isConnected()) {
            String uuid = cordovaArgs.getString(0);
            Log.i(LOGTAG, "New UUID to be put: " + uuid);
            UUID newUuid = UUID.fromString(uuid);

            final UUID[] currentUuid = new UUID[0];
            // Recover the current UUID to be replaced by
            mConnectedDevice.getDeviceConnection().settings.beacon.proximityUUID().get(new SettingCallback<UUID>() {
                @Override
                public void onSuccess(UUID uuid) {
                    currentUuid[0] = uuid;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.i(LOGTAG, "Could not recover current UUID");

                    callbackContext.error("could not recover current UUID");
                }
            });

            // already correct, skip
            if (!newUuid.equals(currentUuid[0])) {
                mConnectedDevice.getDeviceConnection().settings.beacon.proximityUUID().set(newUuid,
                        new SettingCallback<UUID>() {
                            @Override
                            public void onSuccess(UUID uuid) {
                                Log.i(LOGTAG, "UUID changed");
                            }

                            @Override
                            public void onFailure(DeviceConnectionException e) {
                                Log.i(LOGTAG, "UUID not changed");

                                callbackContext.error("could not change UUID");
                            }
                        });
            } else {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(r);
            }
        }
    }

    /**
     * Write Major to connected beacon
     */
    private void writeConnectedMajor(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "writeConnectedMajor");

        if (mConnectedDevice != null && mConnectedDevice.getDeviceConnection().isConnected()) {
            String major = cordovaArgs.getString(0);
            Log.i(LOGTAG, "New major to be put: " + major);
            Integer newMajor = Integer.decode(major);

            final Integer[] currentMajor = new Integer[1];
            // Recover the current major to be replaced by
            mConnectedDevice.getDeviceConnection().settings.beacon.major().get(new SettingCallback<Integer>() {
                @Override
                public void onSuccess(Integer major) {
                    currentMajor[0] = major;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.i(LOGTAG, "Could not recover current major");

                    callbackContext.error("could not recover major");
                }
            });

            // already correct, skip
            if (!newMajor.equals(currentMajor[0])) {
                mConnectedDevice.getDeviceConnection().settings.beacon.major().set(newMajor,
                        new SettingCallback<Integer>() {
                            @Override
                            public void onSuccess(Integer major) {
                                Log.i(LOGTAG, "Major changed");
                            }

                            @Override
                            public void onFailure(DeviceConnectionException e) {
                                Log.i(LOGTAG, "Major not changed");

                                callbackContext.error("could not change major");
                            }
                        });
            } else {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(r);
            }
        }
    }

    /**
     * Write Minor to connected beacon
     */
    private void writeConnectedMinor(CordovaArgs cordovaArgs, final CallbackContext callbackContext)
            throws JSONException {
        Log.i(LOGTAG, "writeConnectedMinor");

        if (mConnectedDevice != null && mConnectedDevice.getDeviceConnection().isConnected()) {
            String minor = cordovaArgs.getString(0);
            Log.i(LOGTAG, "New minor to be put: " + minor);
            Integer newMinor = Integer.decode(minor);

            final Integer[] currentMinor = new Integer[1];
            // Recover the current minor to be replaced by
            mConnectedDevice.getDeviceConnection().settings.beacon.minor().get(new SettingCallback<Integer>() {
                @Override
                public void onSuccess(Integer minor) {
                    currentMinor[0] = minor;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.i(LOGTAG, "Could not recover current minor");

                    callbackContext.error("could not recover minor");
                }
            });

            // already correct, skip
            if (newMinor.equals(currentMinor[0])) {
                mConnectedDevice.getDeviceConnection().settings.beacon.minor().set(newMinor,
                        new SettingCallback<Integer>() {
                            @Override
                            public void onSuccess(Integer minor) {
                                Log.i(LOGTAG, "minor changed");
                            }

                            @Override
                            public void onFailure(DeviceConnectionException e) {
                                Log.i(LOGTAG, "Minor not changed");

                                callbackContext.error("could not change minor");
                            }
                        });
            } else {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(r);
            }
        }
    }

    /**
     * Create JSON object representing beacon info.
     */
    private JSONObject makeJSONBeaconInfo(BeaconRegion region, List<Beacon> beacons) throws JSONException {
        // Create JSON object.
        JSONObject json = new JSONObject();
        json.put("region", makeJSONBeaconRegion(region));
        json.put("beacons", makeJSONBeaconArray(beacons));
        return json;
    }

    /**
     * Create JSON object representing a beacon list.
     */
    private JSONArray makeJSONBeaconArray(List<Beacon> beacons) throws JSONException {
        JSONArray jsonArray = new JSONArray();

        for (Beacon b : beacons) {

            // Compute proximity value.
            Proximity proximityValue = RegionUtils.computeProximity(b);
            String proximity = "unknown";
            if (proximityValue == Proximity.IMMEDIATE) {
                proximity = "immediate";
            } else if (proximityValue == Proximity.NEAR) {
                proximity = "near";
            } else if (proximityValue == Proximity.FAR) {
                proximity = "far";
            }

            // Construct JSON object for beacon.
            JSONObject json = new JSONObject();

            json.put("proximityUUID", b.getProximityUUID().toString());
            json.put("major", b.getMajor());
            json.put("minor", b.getMinor());
            json.put("proximity", proximity);
            json.put("macAddress", b.getMacAddress().toString());
            json.put("accuracy", RegionUtils.computeAccuracy(b));
            json.put("rssi", b.getRssi());

            jsonArray.put(json);
        }
        return jsonArray;
    }

    private String beaconRegionHashMapKey(String uuid, Integer major, Integer minor) {
        // use % for easier decomposition.
        return (uuid == null ? "0" : uuid) + "%" + (major == null ? "0" : major) + "%" + (minor == null ? "0" : minor);
    }

    private JSONObject makeJSONDeviceInfo(List<ConfigurableDevice> devices) throws JSONException {
        // Create JSON object.
        JSONObject json = new JSONObject();
        json.put("devices", makeJSONDeviceArray(devices));
        return json;
    }

    private JSONArray makeJSONDeviceArray(List<ConfigurableDevice> devices) throws JSONException {
        JSONArray jsonArray = new JSONArray();
        for (ConfigurableDevice d : devices) {
            // Construct JSON object for device.
            JSONObject json = new JSONObject();
            json.put("macAddress", d.macAddress);
            json.put("type", d.type);
            json.put("txPower", d.txPower);
            json.put("appVersion", d.appVersion);
            json.put("bootloaderVersion", d.bootloaderVersion);
            json.put("deviceId", d.deviceId);
            json.put("discoveryTime", d.discoveryTime);
            json.put("isClose", d.isClose);
            json.put("isShaken", d.isShaken);
            json.put("rssi", d.rssi);
            jsonArray.put(json);
        }
        return jsonArray;
    }

    private String beaconRegionHashMapKey(BeaconRegion region) {

        UUID uuid = region.getProximityUUID();

        return beaconRegionHashMapKey((uuid == null ? null : uuid.toString()), region.getMajor(), region.getMinor());
    }

    /**
     * Create a BeaconRegion object from Cordova arguments.
     */
    private BeaconRegion createBeaconRegion(JSONObject json) {

        String uuid = json.optString("uuid", null);

        Integer major = optUInt16Null(json, "major");
        Integer minor = optUInt16Null(json, "minor");

        String identifier = json.optString("identifier", beaconRegionHashMapKey(uuid, major, minor));

        BeaconRegion result = null;

        if (isValidUuid(uuid)) {
            UUID finalUuid = uuid != null ? UUID.fromString(uuid) : null;
            result = new BeaconRegion(identifier, finalUuid, major, minor);
        }

        return result;
    }

    /**
     * Returns the value mapped by name if it exists and is a positive integer
     * no larger than 0xFFFF.
     * Returns null otherwise.
     */
    private Integer optUInt16Null(JSONObject json, String name) {
        int i = json.optInt(name, -1);
        Integer res = null;
        if (i >= 0 && i <= (0xFFFF)) {
            res = i;
        }
        return res;
    }

    /**
     * Check if the given UUID is valid.
     */
    private boolean isValidUuid(String uuid) {
        return uuid == null || Pattern.matches("[[a-f]|[0-9]]{8}-[[a-f]|[0-9]]{4}-[[a-f]|[0-9]]{4}-[[a-f]|[0-9]]{4}-[[a-f]|[0-9]]{12}",
                uuid);
    }

    /**
     * Listener for ranging events.
     */
    private class PluginRangingListener implements BeaconManager.BeaconRangingListener {
        @Override
        public void onBeaconsDiscovered(BeaconRegion region, List<Beacon> beacons) {
            // Note that results are not delivered on UI thread.

            Log.i(LOGTAG, "onBeaconsDiscovered: " + beacons.toString());

            try {
                // Find region callback.
                String key = beaconRegionHashMapKey(region);
                CallbackContext rangingCallback = mRangingCallbackContexts.get(key);
                if (rangingCallback == null) {
                    // No callback found.
                    Log.e(LOGTAG, "onBeaconsDiscovered no callback found for key: " + key);
                } else {
                    // Create JSON beacon info object.
                    JSONObject json = makeJSONBeaconInfo(region, beacons);

                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    rangingCallback.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "onBeaconsDiscovered error:", e);
            }
        }
    }

    /**
     * Listener for monitoring events.
     */
    private class PluginMonitoringListener implements BeaconManager.BeaconMonitoringListener {
        @Override
        public void onEnteredRegion(BeaconRegion region, List<Beacon> beacons) {
            // Note that results are not delivered on UI thread.
            Log.i(LOGTAG, "onEnteredRegion");

            sendBeaconRegionInfo(region, "inside");
        }

        @Override
        public void onExitedRegion(BeaconRegion region) {
            // Note that results are not delivered on UI thread.
            Log.i(LOGTAG, "onExitedRegion");

            sendBeaconRegionInfo(region, "outside");
        }

        private void sendBeaconRegionInfo(BeaconRegion region, String state) {
            try {
                // Find region callback.
                String key = beaconRegionHashMapKey(region);
                CallbackContext monitoringCallback = mMonitoringCallbackContexts.get(key);
                if (monitoringCallback == null) {
                    // No callback found.
                    Log.e(LOGTAG, "sendBeaconRegionInfo no callback found for key: " + key);
                } else {
                    // Create JSON region info object with the given state.
                    JSONObject json = makeJSONBeaconRegion(region, state);

                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    monitoringCallback.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "sendBeaconRegionInfo error:", e);
            }
        }
    }

    /**
     * Listener for discovering events.
     */
    private class PluginDiscoveringListener implements BeaconManager.ConfigurableDevicesListener {
        @Override
        public void onConfigurableDevicesFound(List<ConfigurableDevice> devices) {
            // Note that results are not delivered on UI thread.
            Log.i(LOGTAG, "onDevicesDiscovered");

            try {
                // Find region callback.
                if (null == mDiscoveringCallbackContext) {
                    // No callback found.
                    Log.e(LOGTAG, "onDevicesDiscovered no callback found.");
                } else {
                    // Create JSON device info object.
                    JSONObject json = makeJSONDeviceInfo(devices);

                    Log.i(LOGTAG, json.toString());
                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    mDiscoveringCallbackContext.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "onDevicesDiscovered error:", e);
            }
        }
    }

    /**
     * Listener for device connection events
     */
    private class PluginDeviceConnectionCallback implements DeviceConnectionCallback {
        @Override
        public void onConnected() {
            CallbackContext callback = mDeviceConnectionCallback;

            if (callback != null) {
                try {
                    JSONObject json = new JSONObject();

                    json.put("batteryPercentage",
                            mConnectedDevice.getDeviceConnection().settings.power.batteryPercentage());
                    json.put("color", mConnectedDevice.getDeviceConnection().settings.deviceInfo.color());
                    json.put("macAddress", mConnectedDevice.getDevice().macAddress);
                    json.put("major", mConnectedDevice.getDeviceConnection().settings.beacon.major());
                    json.put("minor", mConnectedDevice.getDeviceConnection().settings.beacon.minor());
                    json.put("name", mConnectedDevice.getDeviceConnection().settings.deviceInfo.name());
                    json.put("uuid", mConnectedDevice.getDeviceConnection().settings.beacon.proximityUUID());

                    Log.i(LOGTAG, "2");
                    Settings settings = mConnectedDevice.getDeviceConnection().settings;
                    JSONObject jsonSettings = new JSONObject();
                    jsonSettings.put("advertisingIntervalMillis", settings.beacon.advertisingInterval());
                    jsonSettings.put("batteryLevel", settings.power.batteryPercentage());
                    jsonSettings.put("broadcastingPower", settings.beacon.transmitPower());
                    jsonSettings.put("firmware", settings.deviceInfo.firmware());
                    jsonSettings.put("hardware", settings.deviceInfo.hardware());

                    Log.i(LOGTAG, "3");
                    // finish up response param
                    json.put("settings", jsonSettings);

                    Log.i(LOGTAG, "4");
                    Log.i(LOGTAG, json.toString());
                    // pass back to web
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    callback.sendPluginResult(r);
                } catch (JSONException e) {
                    Log.i(LOGTAG, "inError");
                    String msg;
                    msg = "connection succeeded, could not marshall object: ";
                    msg = msg.concat(e.getMessage());

                    callback.error(msg);
                }
            }
            // cleanup
            mDeviceConnectionCallback = null;
        }

        @Override
        public void onConnectionFailed(DeviceConnectionException e) {
            CallbackContext callback = mDeviceConnectionCallback;

            if (callback != null) {
                // pass back to js
                callback.error(e.getMessage());

                // print stacktrace to android logs
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                Log.e(LOGTAG, sw.toString());
            }
            // cleanup
            mDeviceConnectionCallback = null;
        }

        @Override
        public void onDisconnected() {
            CallbackContext callback = mDeviceDisconnectionCallback;

            if (callback != null) {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callback.sendPluginResult(r);
            }
            // cleanup
            mDeviceDisconnectionCallback = null;
        }
    }

    private class DeviceConnected {
        private DeviceConnection mDeviceConnection;
        private ConfigurableDevice mDevice;

        DeviceConnected(DeviceConnection deviceConnection, ConfigurableDevice device) {
            this.mDeviceConnection = deviceConnection;
            this.mDevice = device;
        }

        DeviceConnection getDeviceConnection() {
            return mDeviceConnection;
        }

        void setDeviceConnection(DeviceConnection deviceConnection) {
            mDeviceConnection = deviceConnection;
        }

        ConfigurableDevice getDevice() {
            return mDevice;
        }
    }
}
