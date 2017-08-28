/*
Android implementation of Cordova plugin for Estimote Beacons.

JavaDoc for Estimote Android API: https://estimote.github.io/Android-SDK/JavaDocs/
*/

package com.evothings;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.util.Log;

import com.estimote.coresdk.common.config.EstimoteSDK;
import com.estimote.coresdk.observation.region.RegionUtils;
import com.estimote.coresdk.observation.region.beacon.BeaconRegion;
import com.estimote.coresdk.observation.region.beacon.SecureBeaconRegion;
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
 * Estimote default UUID: B9407F30-F5F8-466E-AFF9-25556B57FE6D.
 */
public class EstimoteBeacons extends CordovaPlugin {

    private static final String LOGTAG = "EstimoteBeacons";
    private static final int REQUEST_ENABLE_BLUETOOTH = 1;

    private CordovaInterface cordovaInterface;

    private EstimoteSDK estimoteSDK;
    private BeaconManager beaconManager;

    private ArrayList<ConfigurableDevice> discoveredDevices;
    private DeviceConnected connectedDevice;

    // Maps and variables that keep track of Cordova callbacks.
    private HashMap<String, CallbackContext> rangingCallbackContexts;
    private HashMap<String, CallbackContext> monitoringCallbackContexts;
    private CallbackContext discoveringCallbackContext;

    // Booleans to keep track of listeners.
    private boolean isRangingListenerSet;
    private boolean isMonitoringListenerSet;
    private boolean isDeviceDiscoveringListenerSet;

    private CallbackContext bluetoothStateCallbackContext;
    private CallbackContext deviceConnectionCallback;
    private CallbackContext deviceDisconnectionCallback;

    /**
     * Create JSON object representing a region.
     */
    private static JSONObject makeJSONBeaconRegion(BeaconRegion region) throws JSONException {
        JSONObject json = new JSONObject();

        json.put("identifier", region.getIdentifier());
        json.put("proximityUUID", region.getProximityUUID());
        json.put("major", region.getMajor());
        json.put("minor", region.getMinor());

        return json;
    }

    /**
     * Create JSON object representing ranging information.
     */
    private static JSONObject makeJSONRangingInformation(BeaconRegion region, List<Beacon> beacons) throws JSONException {
        // Create JSON object.
        JSONObject json = new JSONObject();
        json.put("region", makeJSONBeaconRegion(region));
        json.put("beacons", makeJSONBeaconArray(beacons));
        return json;
    }

    /**
     * Create JSON object representing a beacon list.
     */
    private static JSONArray makeJSONBeaconArray(List<Beacon> beacons) throws JSONException {
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

    /**
     * Makes a JSON object for monitoring callback.
     */
    private static JSONObject makeJSONMonitoringInformation(BeaconRegion region, String state) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("region", makeJSONBeaconRegion(region));
        json.put("state", state);
        return json;
    }

    /**
     * Makes a key from UUID + major + minor.
     */
    private static String beaconRegionHashMapKey(String uuid, Integer major, Integer minor) {
        // Use ':' for easier decomposition.
        return (uuid == null ? "0" : uuid) + ":" + (major == null ? "0" : major) + ":" + (minor == null ? "0" : minor);
    }

    /**
     * Makes a JSON object from a ConfigurableDevice list.
     */
    private static JSONObject makeJSONDeviceInformation(List<ConfigurableDevice> devices) throws JSONException {
        // Create JSON object.
        JSONObject json = new JSONObject();
        json.put("devices", makeJSONDeviceArray(devices));
        return json;
    }

    /**
     * Makes a JSON array object from a ConfigurableDevice list.
     */
    private static JSONArray makeJSONDeviceArray(List<ConfigurableDevice> devices) throws JSONException {
        JSONArray jsonArray = new JSONArray();
        for (ConfigurableDevice d : devices) {
            // Construct JSON object for each device.
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

    /**
     * Makes a key from a BeaconRegion object.
     */
    private static String beaconRegionHashMapKey(BeaconRegion region) {

        UUID uuid = region.getProximityUUID();

        return beaconRegionHashMapKey((uuid == null ? null : uuid.toString()), region.getMajor(), region.getMinor());
    }

    /**
     * Create a BeaconRegion object from Cordova arguments.
     */
    private static BeaconRegion createBeaconRegion(JSONObject json, boolean isSecure) {

        String uuid = json.optString("uuid", null);

        Integer major = optUInt16Null(json, "major");
        Integer minor = optUInt16Null(json, "minor");

        String identifier = json.optString("identifier", beaconRegionHashMapKey(uuid, major, minor));

        BeaconRegion result = null;

        if (isValidUuid(uuid)) {
            UUID finalUuid = uuid != null ? UUID.fromString(uuid) : null;

            if (isSecure) {
                result = new SecureBeaconRegion(identifier, finalUuid, major, minor);
            } else {
                result = new BeaconRegion(identifier, finalUuid, major, minor);
            }
        }

        return result;
    }

    /**
     * Returns the value mapped by name if it exists and is a positive integer no larger than 0xFFFF.
     * Returns null otherwise.
     */
    private static Integer optUInt16Null(JSONObject json, String name) {
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
    private static boolean isValidUuid(String uuid) {
        String validChars = "[[a-fA-F]|[0-9]]";
        return Pattern.matches(validChars + "{8}-" + validChars + "{4}-" + validChars + "{4}-" + validChars + "{4}-" + validChars + "{12}", uuid);
    }

    /**
     * Plugin initializer.
     */
    @Override
    public void pluginInitialize() {
        super.pluginInitialize();
        Log.d(LOGTAG, "Initializing plugin.");

        // Setting up the Cordova configuration.
        this.cordovaInterface = cordova;
        this.cordovaInterface.setActivityResultCallback(this);

        this.beaconManager = new BeaconManager(this.cordovaInterface.getActivity());

        this.beaconManager.setErrorListener(new BeaconManager.ErrorListener() {
            @Override
            public void onError(Integer integer) {
                Log.e(LOGTAG, "BeaconManager error: " + integer + ".");
            }
        });

        this.discoveredDevices = new ArrayList<ConfigurableDevice>();

        this.rangingCallbackContexts = new HashMap<String, CallbackContext>();
        this.monitoringCallbackContexts = new HashMap<String, CallbackContext>();

        this.isRangingListenerSet = false;
        this.isMonitoringListenerSet = false;
        this.isDeviceDiscoveringListenerSet = false;
    }

    /**
     * Plugin reset.
     * Called when the WebView does a top-level navigation or refreshes.
     */
    @Override
    public void onReset() {
        super.onReset();
        Log.d(LOGTAG, "Resetting WebView.");

        this.rangingCallbackContexts.clear();
        this.monitoringCallbackContexts.clear();
        this.discoveringCallbackContext = null;
    }

    /**
     * The final call received before the activity is destroyed.
     */
    @Override
    public void onDestroy() {
        Log.d(LOGTAG, "Destroying the WebView.");

        this.disconnectConnectedDevice();
        this.disconnectBeaconManager();

        super.onDestroy();
    }

    /**
     * Disconnect from the beacon manager.
     *  
     */
    private void disconnectBeaconManager() {
        Log.d(LOGTAG, "Disconnecting from BeaconService.");

        this.beaconManager.disconnect();
    }

    /**
     * Entry point for JavaScript calls.
     */
    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        boolean res = true;

        if ("beacons_startRangingBeaconsInRegion".equals(action)) {
            this.startRangingBeaconsInRegion(args, callbackContext, false);
        } else if ("beacons_stopRangingBeaconsInRegion".equals(action)) {
            this.stopRangingBeaconsInRegion(args, callbackContext, false);
        } else if ("beacons_startMonitoringForRegion".equals(action)) {
            this.startMonitoringForRegion(args, callbackContext);
        } else if ("beacons_stopMonitoringForRegion".equals(action)) {
            this.stopMonitoringForRegion(args, callbackContext);
        } else if ("beacons_startDiscoveringDevices".equals(action)) {
            this.startDiscoveringDevices(callbackContext);
        } else if ("beacons_stopDiscoveringDevices".equals(action)) {
            this.stopDiscoveringDevices(callbackContext);
        } else if ("beacons_setupAppIDAndAppToken".equals(action)) {
            this.setupAppIDAndAppToken(args, callbackContext);
        } else if ("beacons_startRangingSecureBeaconsInRegion".equals(action)) {
            this.startRangingBeaconsInRegion(args, callbackContext, true);
        } else if ("beacons_stopRangingSecureBeaconsInRegion".equals(action)) {
            this.stopRangingBeaconsInRegion(args, callbackContext, true);
        } else if ("beacons_connectToDevice".equals(action)) {
            this.connectToDevice(args, callbackContext);
        } else if ("beacons_disconnectFromDevice".equals(action)) {
            this.disconnectConnectedDevice(callbackContext);
        } else if ("beacons_writeConnectedProximityUUID".equals(action)) {
            this.writeConnectedProximityUUID(args, callbackContext);
        } else if ("beacons_writeConnectedMajor".equals(action)) {
            this.writeConnectedMajor(args, callbackContext);
        } else if ("beacons_writeConnectedMinor".equals(action)) {
            this.writeConnectedMinor(args, callbackContext);
        } else if ("bluetooth_bluetoothState".equals(action)) {
            this.checkBluetoothState(callbackContext);
        } else {
            res = false;
        }

        return res;
    }

    /**
     * If Bluetooth is off, open a Bluetooth dialog.
     */
    private void checkBluetoothState(final CallbackContext callbackContext) throws JSONException {
        Log.d(LOGTAG, "Checking Bluetooth state.");

        // Check that no Bluetooth state request is in progress.
        if (this.bluetoothStateCallbackContext != null) {
            callbackContext.error("Bluetooth state request already in progress.");
        } else {
            // Check if Bluetooth is enabled.
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (!bluetoothAdapter.isEnabled()) {
                // Open Bluetooth dialog on the UI thread.
                final CordovaPlugin self = this;
                this.bluetoothStateCallbackContext = callbackContext;
                Runnable openBluetoothDialog = new Runnable() {
                    public void run() {
                        Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        cordovaInterface.startActivityForResult(self, enableIntent, REQUEST_ENABLE_BLUETOOTH);
                    }
                };
                this.cordovaInterface.getActivity().runOnUiThread(openBluetoothDialog);
            } else {
                // Bluetooth is enabled, return the result to JavaScript.
                callbackContext.success(Activity.RESULT_OK);
            }
        }
    }

    /**
     * Check if Bluetooth is enabled and return result to JavaScript.
     */
    private void sendResultForBluetoothEnabled(CallbackContext callbackContext) {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            callbackContext.success(Activity.RESULT_OK);
        } else {
            callbackContext.success(Activity.RESULT_CANCELED);
        }
    }

    /**
     * Called when the Bluetooth dialog is closed.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.d(LOGTAG, "Resulting activity from Intent.");

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            sendResultForBluetoothEnabled(this.bluetoothStateCallbackContext);
            this.bluetoothStateCallbackContext = null;
        }
    }

    /**
     * Start ranging for beacons in the region passed in argument.
     */
    private void startRangingBeaconsInRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext, boolean isSecure) throws JSONException {
        JSONObject json = cordovaArgs.getJSONObject(0);

        // The region is final because used in the onServiceReady() method.
        final BeaconRegion region = createBeaconRegion(json, isSecure);

        Log.d(LOGTAG, "The region to range: " + region + ".");

        // Check the region validity.
        if (region != null) {
            String key = beaconRegionHashMapKey(region);

            // If the region is not actually ranged.
            if (this.rangingCallbackContexts.get(key) == null) {

                // Add callback to hash map.
                this.rangingCallbackContexts.put(key, callbackContext);

                // Create ranging listener.
                if (!this.isRangingListenerSet) {
                    this.beaconManager.setRangingListener(new PluginRangingListener());
                    this.isRangingListenerSet = true;
                }

                this.beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                    @Override
                    public void onServiceReady() {
                        Log.d(LOGTAG, "Connected to BeaconService.");

                        startRanging(region, callbackContext);
                    }
                });
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
            Log.d(LOGTAG, "Start ranging region: " + beaconRegionHashMapKey(region) + ".");

            this.beaconManager.startRanging(region);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error during ranging: " + e + ".");

            callbackContext.error("Ranging remote exception.");
        }
    }

    /**
     * Stop ranging for beacons.
     */
    private void stopRangingBeaconsInRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext, boolean isSecure) throws JSONException {
        JSONObject json = cordovaArgs.getJSONObject(0);

        BeaconRegion region = createBeaconRegion(json, isSecure);

        Log.d(LOGTAG, "The region to stop ranging: " + region + ".");

        // Check the region validity.
        if (region != null) {

            String key = beaconRegionHashMapKey(region);
            CallbackContext rangingCallback = this.rangingCallbackContexts.get(key);

            // If ranging callback does not exist call error callback.
            if (rangingCallback == null) {
                callbackContext.error("Region not ranged, can't stop ranging.");
            } else {

                // Remove ranging callback from hash map.
                this.rangingCallbackContexts.remove(key);

                // Clear ranging callback on JavaScript side.
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(false);
                rangingCallback.sendPluginResult(result);

                try {
                    Log.d(LOGTAG, "Stop ranging region: " + beaconRegionHashMapKey(region) + ".");

                    // Stop ranging.
                    this.beaconManager.stopRanging(region);

                    // Send back success.
                    callbackContext.success();
                } catch (Exception e) {
                    Log.e(LOGTAG, "Stopping ranging error: " + e + ".");

                    callbackContext.error("Stopping ranging remote exception.");
                }

            }
        } else {
            callbackContext.error("Invalid UUID.");
        }
    }

    /**
     * Start monitoring the given region.
     */

    private void startMonitoringForRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        JSONObject json = cordovaArgs.getJSONObject(0);
        final BeaconRegion region = createBeaconRegion(json, false);

        // Check the region validity.
        if (region != null) {

            String key = beaconRegionHashMapKey(region);
            if (this.monitoringCallbackContexts.get(key) == null) {

                // Add callback to hash map.
                this.monitoringCallbackContexts.put(key, callbackContext);

                // Create monitoring listener.
                if (!this.isMonitoringListenerSet) {
                    this.beaconManager.setMonitoringListener(new PluginMonitoringListener());
                    this.isMonitoringListenerSet = true;
                }

                this.beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                    @Override
                    public void onServiceReady() {
                        Log.d(LOGTAG, "Connected to BeaconService.");

                        startMonitoring(region, callbackContext);
                    }
                });
            }
        } else {
            callbackContext.error("Invalid UUID.");
        }
    }

    /**
     * Helper method.
     */
    private void startMonitoring(BeaconRegion region, CallbackContext callbackContext) {
        Log.d(LOGTAG, "Start monitoring region: " + region.getIdentifier() + ".");

        try {
            this.beaconManager.startMonitoring(region);
        } catch (Exception e) {
            Log.e(LOGTAG, "Monitoring error: " + e + ".");

            callbackContext.error("Starting monitoring remote exception.");
        }
    }

    /**
     * Stop monitoring the region.
     */
    private void stopMonitoringForRegion(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        JSONObject json = cordovaArgs.getJSONObject(0);
        BeaconRegion region = createBeaconRegion(json, false);

        // Check the region validity.
        if (region != null) {

            String key = beaconRegionHashMapKey(region);
            CallbackContext monitoringCallback = this.monitoringCallbackContexts.get(key);

            // If monitoring callback does not exist call error callback.
            if (monitoringCallback == null) {
                callbackContext.error("Region not monitored, can't stop.");
            } else {
                // Remove monitoring callback from hash map.
                this.monitoringCallbackContexts.remove(key);

                // Clear monitoring callback on JavaScript side.
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(false);
                monitoringCallback.sendPluginResult(result);

                try {
                    Log.d(LOGTAG, "Stop monitoring region: " + region.getIdentifier() + ".");

                    // Stop monitoring.
                    this.beaconManager.stopMonitoring(beaconRegionHashMapKey(region));

                    // Send back success.
                    callbackContext.success();
                } catch (Exception e) {
                    Log.e(LOGTAG, "Stop monitoring error: " + e + ".");

                    callbackContext.error("Stop monitoring remote exception.");
                }
            }
        } else {
            callbackContext.error("Invalid UUID.");
        }
    }

    /**
     * Start discovering connectivity packets.
     */
    private void startDiscoveringDevices(final CallbackContext callbackContext) throws JSONException {
        if (this.discoveringCallbackContext == null) {
            this.discoveringCallbackContext = callbackContext;

            // Create discovering listener.
            if (!this.isDeviceDiscoveringListenerSet) {
                this.beaconManager.setConfigurableDevicesListener(new PluginDiscoveringListener());
                this.isDeviceDiscoveringListenerSet = true;
            }

            this.beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                @Override
                public void onServiceReady() {
                    Log.d(LOGTAG, "Connected to BeaconService.");

                    startDiscovering(callbackContext);
                }
            });
        }
    }

    /**
     * Helper method.
     */
    private void startDiscovering(CallbackContext callbackContext) {
        Log.d(LOGTAG, "startDiscovering");

        try {
            this.beaconManager.startConfigurableDevicesDiscovery();
        } catch (Exception e) {
            Log.e(LOGTAG, "Starting discovering error: " + e + ".");

            callbackContext.error("Start discovering remote exception.");
        }
    }

    /**
     * Stop discovering for connectivity packets.
     */
    private void stopDiscoveringDevices(final CallbackContext callbackContext) throws JSONException {
        // If discovering callback does not exist call error callback
        if (this.discoveringCallbackContext == null) {
            callbackContext.error("Devices not being discovered, can't stop discovery.");
        } else {
            // Clear discovering callback on JavaScript side.
            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(false);
            this.discoveringCallbackContext.sendPluginResult(result);
            this.discoveringCallbackContext = null;

            try {
                Log.d(LOGTAG, "Stop discovering devices.");

                // Stop discovering.
                this.beaconManager.stopConfigurableDevicesDiscovery();

                // Send back success.
                callbackContext.success();
            } catch (Exception e) {
                Log.e(LOGTAG, "Stop discovering error: " + e + ".");

                callbackContext.error("Stop discovering remote exception.");
            }
        }
    }

    /**
     * Authenticate with Estimote Cloud.
     */
    private void setupAppIDAndAppToken(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        if (this.estimoteSDK == null) {
            this.estimoteSDK = new EstimoteSDK();

            Log.d(LOGTAG, "Setting up connection with Estimote cloud.");
            EstimoteSDK.initialize(this.cordova.getActivity(), cordovaArgs.getString(0), cordovaArgs.getString(1));

            PluginResult r = new PluginResult(PluginResult.Status.OK);
            callbackContext.sendPluginResult(r);
        } else {
            callbackContext.error("Already authenticated to Estimote cloud: " + EstimoteSDK.getAppId() + ".");
        }
    }

    /**
     * Find device in rangedDevices, with MAC address.
     */
    private ConfigurableDevice findDevice(String macAddress) {
        int size = this.discoveredDevices.size();
        ConfigurableDevice current = null;
        boolean searching = true;

        while (searching && size > 0) {
            current = this.discoveredDevices.get(size - 1);

            // If device found.
            if (current.macAddress.toString().equals(macAddress)) {
                searching = false;
            }
            // Else continue.
            else {
                size--;
            }
        }
        // If finally not found.
        if (searching) {
            current = null;
        }

        return current;
    }

    /**
     * Find device in rangedDevices, from JSON.
     */
    private ConfigurableDevice findDevice(JSONObject json) throws JSONException {
        String macAddress = json.optString("macAddress", "");
        return findDevice(macAddress);
    }

    /**
     * Connect to a device.
     */
    private void connectToDevice(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        Log.d(LOGTAG, "Connecting to device.");

        JSONObject json = cordovaArgs.getJSONObject(0);
        final ConfigurableDevice device = findDevice(json);

        if (device == null) {
            callbackContext.error("Could not find device to connect to.");
        } else {
            Log.d(LOGTAG, "Connecting to device: " + device.macAddress + ".");
            // Devices are jealous creatures and don't like competition.
            if (this.connectedDevice != null && !this.connectedDevice.getDevice().macAddress.equals(device.macAddress)) {
                disconnectConnectedDevice();
            }

            this.deviceConnectionCallback = callbackContext;

            final DeviceConnectionProvider deviceConnectionProvider = new DeviceConnectionProvider(cordova.getActivity());
            deviceConnectionProvider.connectToService(new DeviceConnectionProvider.ConnectionProviderCallback() {
                @Override
                public void onConnectedToService() {
                    Log.d(LOGTAG, "Connected to ConnectionService.");

                    connectedDevice = new DeviceConnected(deviceConnectionProvider.getConnection(device), device);
                    connectedDevice.getDeviceConnection().connect(new PluginDeviceConnectionCallback());
                }
            });
        }
    }

    /**
     * Disconnect from connected device, c/o Cordova.
     */
    private void disconnectConnectedDevice(CallbackContext callbackContext) throws JSONException {
        Log.d(LOGTAG, "disconnectConnectedDevice (cordova)");

        this.deviceDisconnectionCallback = callbackContext;
        disconnectConnectedDevice();
    }

    /**
     * Disconnect from connected beacon.
     */
    private void disconnectConnectedDevice() {

        if (this.connectedDevice != null && this.connectedDevice.getDeviceConnection().isConnected()) {
            Log.d(LOGTAG, "Disconnecting from device " + this.connectedDevice.getDevice().macAddress + ".");

            this.connectedDevice.getDeviceConnection().close();
            this.connectedDevice.setDeviceConnection(null);
        }
    }

    /**
     * Write Proximity UUID to connected beacon.
     */
    private void writeConnectedProximityUUID(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        if (this.connectedDevice != null && this.connectedDevice.getDeviceConnection().isConnected()) {

            String uuid = cordovaArgs.getString(0);
            Log.d(LOGTAG, "New UUID to be put: " + uuid + ".");
            final UUID newUuid = UUID.fromString(uuid);

            final UUID[] currentUuid = new UUID[1];
            // Recover the current UUID to be replaced by.
            this.connectedDevice.getDeviceConnection().settings.beacon.proximityUUID().get(new SettingCallback<UUID>() {
                @Override
                public void onSuccess(UUID uuid) {
                    Log.d(LOGTAG, "UUID to be erased: " + uuid.toString() + ".");
                    currentUuid[0] = uuid;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.e(LOGTAG, "Could not recover current UUID.");

                    callbackContext.error("Could not recover current UUID.");
                }
            });

            // Do not write the same UUID.
            if (!newUuid.equals(currentUuid[0])) {
                this.connectedDevice.getDeviceConnection().settings.beacon.proximityUUID().set(newUuid, new SettingCallback<UUID>() {
                    @Override
                    public void onSuccess(UUID uuid) {
                        Log.d(LOGTAG, "UUID changed to: " + uuid.toString() + ".");
                    }

                    @Override
                    public void onFailure(DeviceConnectionException e) {
                        Log.e(LOGTAG, "Could not change UUID to: " + newUuid.toString() + ".");

                        callbackContext.error("Could not change UUID.");
                    }
                });
            } else {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(r);
            }
        }
    }

    /**
     * Write Major to connected beacon.
     */
    private void writeConnectedMajor(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {

        if (this.connectedDevice != null && this.connectedDevice.getDeviceConnection().isConnected()) {

            String major = cordovaArgs.getString(0);
            Log.d(LOGTAG, "New major to be put: " + major + ".");
            final Integer newMajor = Integer.decode(major);

            final Integer[] currentMajor = new Integer[1];

            // Recover the current major to be replaced by.
            this.connectedDevice.getDeviceConnection().settings.beacon.major().get(new SettingCallback<Integer>() {
                @Override
                public void onSuccess(Integer major) {
                    Log.d(LOGTAG, "Major to be replaced: " + major.toString() + ".");
                    currentMajor[0] = major;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.e(LOGTAG, "Could not recover current major.");

                    callbackContext.error("Could not recover major.");
                }
            });

            // Do not write the same major.
            if (!newMajor.equals(currentMajor[0])) {
                this.connectedDevice.getDeviceConnection().settings.beacon.major().set(newMajor,
                        new SettingCallback<Integer>() {
                            @Override
                            public void onSuccess(Integer major) {
                                Log.d(LOGTAG, "Major changed to: " + major.toString() + ".");
                            }

                            @Override
                            public void onFailure(DeviceConnectionException e) {
                                Log.e(LOGTAG, "Major not changed to: " + newMajor.toString() + ".");

                                callbackContext.error("Could not change major.");
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
    private void writeConnectedMinor(CordovaArgs cordovaArgs, final CallbackContext callbackContext) throws JSONException {
        if (this.connectedDevice != null && this.connectedDevice.getDeviceConnection().isConnected()) {

            String minor = cordovaArgs.getString(0);
            Log.d(LOGTAG, "New minor to be put: " + minor + ".");
            final Integer newMinor = Integer.decode(minor);

            final Integer[] currentMinor = new Integer[1];

            // Recover the current minor to be replaced by.
            this.connectedDevice.getDeviceConnection().settings.beacon.minor().get(new SettingCallback<Integer>() {
                @Override
                public void onSuccess(Integer minor) {
                    Log.d(LOGTAG, "Minor to be replaced: " + minor.toString() + ".");
                    currentMinor[0] = minor;
                }

                @Override
                public void onFailure(DeviceConnectionException e) {
                    Log.e(LOGTAG, "Could not recover current minor.");

                    callbackContext.error("Could not recover minor.");
                }
            });

            // Do not write the same minor.
            if (newMinor.equals(currentMinor[0])) {
                this.connectedDevice.getDeviceConnection().settings.beacon.minor().set(newMinor,
                        new SettingCallback<Integer>() {
                            @Override
                            public void onSuccess(Integer minor) {
                                Log.d(LOGTAG, "Minor changed to: " + minor.toString() + ".");
                            }

                            @Override
                            public void onFailure(DeviceConnectionException e) {
                                Log.e(LOGTAG, "Minor not changed to: " + newMinor.toString() + ".");

                                callbackContext.error("Could not change minor.");
                            }
                        });
            } else {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(r);
            }
        }
    }

    /**
     * Listener for ranging events.
     */
    private class PluginRangingListener implements BeaconManager.BeaconRangingListener {
        @Override
        public void onBeaconsDiscovered(BeaconRegion region, List<Beacon> beacons) {
            // Note that results are not delivered on UI thread.

            Log.d(LOGTAG, "Discovered beacons: " + beacons.toString() + ".");

            try {
                // Find region callback.
                String key = beaconRegionHashMapKey(region);
                CallbackContext rangingCallback = rangingCallbackContexts.get(key);

                if (rangingCallback == null) {
                    // No callback found.
                    Log.e(LOGTAG, "No callback found for region: " + key + ".");
                } else {
                    // Create JSON beacon info object.
                    JSONObject json = makeJSONRangingInformation(region, beacons);

                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    rangingCallback.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "JSON error: " + e + ".");
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
            Log.d(LOGTAG, "Entered region: " + region.getIdentifier() + ".");

            sendBeaconRegionInfo(region, "entered");
        }

        @Override
        public void onExitedRegion(BeaconRegion region) {
            // Note that results are not delivered on UI thread.
            Log.d(LOGTAG, "Exited region: " + region.getIdentifier() + ".");

            sendBeaconRegionInfo(region, "exited");
        }

        private void sendBeaconRegionInfo(BeaconRegion region, String state) {
            try {
                // Find region callback.
                String key = beaconRegionHashMapKey(region);
                CallbackContext monitoringCallback = monitoringCallbackContexts.get(key);

                if (monitoringCallback == null) {
                    // No callback found.
                    Log.e(LOGTAG, "No callback found for key: " + key + ".");
                } else {
                    // Create JSON region info object with the given state.
                    JSONObject json = makeJSONMonitoringInformation(region, state);

                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    monitoringCallback.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "JSON error: " + e + ".");
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
            Log.d(LOGTAG, "Discovered configurable devices: " + devices.toString() + ".");

            try {
                // Find region callback.
                if (discoveringCallbackContext == null) {
                    // No callback found.
                    Log.e(LOGTAG, "No callback found for discoverable devices.");
                } else {
                    // Create JSON device info object.
                    JSONObject json = makeJSONDeviceInformation(devices);

                    // Send result to JavaScript.
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    r.setKeepCallback(true);
                    discoveringCallbackContext.sendPluginResult(r);
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "JSON error: " + e + ".");
            }
        }
    }

    /**
     * Listener for device connection events.
     */
    private class PluginDeviceConnectionCallback implements DeviceConnectionCallback {
        @Override
        public void onConnected() {
            CallbackContext callback = deviceConnectionCallback;

            if (callback != null) {
                try {
                    JSONObject json = new JSONObject();

                    json.put("batteryPercentage",
                            connectedDevice.getDeviceConnection().settings.power.batteryPercentage());
                    json.put("color", connectedDevice.getDeviceConnection().settings.deviceInfo.color());
                    json.put("macAddress", connectedDevice.getDevice().macAddress);
                    json.put("major", connectedDevice.getDeviceConnection().settings.beacon.major());
                    json.put("minor", connectedDevice.getDeviceConnection().settings.beacon.minor());
                    json.put("name", connectedDevice.getDeviceConnection().settings.deviceInfo.name());
                    json.put("uuid", connectedDevice.getDeviceConnection().settings.beacon.proximityUUID());

                    Settings settings = connectedDevice.getDeviceConnection().settings;
                    JSONObject jsonSettings = new JSONObject();
                    jsonSettings.put("advertisingIntervalMillis", settings.beacon.advertisingInterval());
                    jsonSettings.put("batteryLevel", settings.power.batteryPercentage());
                    jsonSettings.put("broadcastingPower", settings.beacon.transmitPower());
                    jsonSettings.put("firmware", settings.deviceInfo.firmware());
                    jsonSettings.put("hardware", settings.deviceInfo.hardware());

                    // finish up response param
                    json.put("settings", jsonSettings);

                    // pass back to web
                    PluginResult r = new PluginResult(PluginResult.Status.OK, json);
                    callback.sendPluginResult(r);
                } catch (JSONException e) {
                    Log.e(LOGTAG, "JSON error: " + e + ".");

                    String msg = "Connection succeeded, could not marshall object: " + e.getMessage() + ".";
                    callback.error(msg);
                }
            }
            // Clean up.
            deviceConnectionCallback = null;
        }

        @Override
        public void onConnectionFailed(DeviceConnectionException e) {
            CallbackContext callback = deviceConnectionCallback;

            if (callback != null) {
                // Pass back to JS.
                callback.error(e.getMessage());

                // Print stacktrace to android logs.
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                Log.e(LOGTAG, sw.toString());
            }
            // Clean up.
            deviceConnectionCallback = null;
        }

        @Override
        public void onDisconnected() {
            CallbackContext callback = deviceDisconnectionCallback;

            if (callback != null) {
                PluginResult r = new PluginResult(PluginResult.Status.OK);
                callback.sendPluginResult(r);
            }
            // Clean up.
            deviceDisconnectionCallback = null;
        }
    }

    private class DeviceConnected {
        private DeviceConnection deviceConnection;
        private ConfigurableDevice device;

        DeviceConnected(DeviceConnection deviceConnection, ConfigurableDevice device) {
            this.deviceConnection = deviceConnection;
            this.device = device;
        }

        DeviceConnection getDeviceConnection() {
            return this.deviceConnection;
        }

        void setDeviceConnection(DeviceConnection deviceConnection) {
            this.deviceConnection = deviceConnection;
        }

        ConfigurableDevice getDevice() {
            return this.device;
        }
    }
}
