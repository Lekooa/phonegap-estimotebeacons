// Range beacons screen.
; (function (app) {
    app.startRangingSecureBeacons = function () {
        function onRange(beaconInfo) {
            displayBeconInfo(beaconInfo);
        }

        function onError(errorMessage) {
            console.log('Range error: ' + errorMessage);
        }

        function displayBeconInfo(beaconInfo) {
            // Clear beacon HTML items.
            $('#id-screen-range-secure-beacons .style-item-list').empty();

            // Sort beacons by distance.
            beaconInfo.beacons.sort(function (beacon1, beacon2) {
                return beacon1.distance > beacon2.distance;
            });

            // Generate HTML for beacons.
            $.each(beaconInfo.beacons, function (key, beacon) {
                var element = $(createBeaconHTML(beacon));
                $('#id-screen-range-secure-beacons .style-item-list').append(element);
            });
        };

        function createBeaconHTML(beacon) {
            var colorClasses = app.beaconColorStyle(beacon.color);
            var htm = '<div class="' + colorClasses + '">'
                + '<table><td>UUID:</td><td>' + beacon.proximityUUID
                + '<tr><td>Major:</td><td>' + beacon.major
                + '</td></tr><tr><td>Minor:</td><td>' + beacon.minor
                + '</td></tr><tr><td>Proximity:</td><td>' + beacon.proximity
                + '</td></tr></table></div>';
            return htm;
        };

        // Show screen.
        app.showScreen('id-screen-range-secure-beacons');
        $('#id-screen-range-secure-beacons .style-item-list').empty();

        // Request authorisation.
        estimote.beacons.requestAlwaysAuthorization();

        // Initialize Estimote cloud connection.
        estimote.beacons.setupAppIDAndAppToken("e-marger-hly", "4468abb019e6716831aa3926ea378abd");

        // Start secure ranging.
        estimote.beacons.startRangingSecureBeaconsInRegion(
            { uuid: "b9407f30-f5f8-466e-aff9-25556b57fe6d" },
            onRange,
            onError);
    };

    app.stopRangingSecureBeacons = function () {
        estimote.beacons.stopRangingSecureBeaconsInRegion({ uuid: "b9407f30-f5f8-466e-aff9-25556b57fe6d" });
        app.showHomeScreen();
    };

})(app);
