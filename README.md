## What's working here?

iOS:

technology | working?
-- | :-:
ranging | OK
secure ranging | OK
monitoring | NOT TESTED
secure monitoring | NOT TESTED
discovery | NOT TESTED

Android: 

techonology | 1.0.1 | 1.0.3
-- | :-: | :-:
ranging | OK | NOT WORKING
secure ranging | WORKING-ISH | NOT WORKING
monitoring | OK | NOT TESTED
secure monitoring | NOT TESTED | NOT TESTED
discovery | NOT WORKING | OK

The ranging technology in 1.0.1 version works with a `beaconRegion()` and not a `beaconSecureRegion()` object. The ranging shows both non secure and secure beacons.
See https://github.com/Estimote/Android-SDK/issues/211.

## About the Cordova plug-in Estimote

This plug-in makes it easy to develop Cordova apps for [Estimote](https://estimote.com) beacons. Use JavaScript and HTML to develop apps that take advantage of the capabilities of Estimote beacons.

## Beacon Finder example app

Try out the Beacon Finder example app, which is available in the examples folder in this repository. Find out more in the [README file](examples/beacon-finder/README.md) and look into the details of the [example source code](examples/beacon-finder/www/).

## How to create an app using the plugin

See the instructions in the Beacon Finder [README file](examples/beacon-finder/README.md).

## Documentation

The file [documentation.md](documentation.md) contains an overview of the plug-in API.

Documentation of all functions is available in the JavaScript API implementation file [EstimoteBeacons.js](plugin/src/js/EstimoteBeacons.js).

## Credits

The Lekooa team updated this plug-in to version 1.0.*.

Many thanks goes to [Konrad Dzwinel](https://github.com/kdzwinel) who developed the original version of this plug-in and provided valuable support and advice for the redesign of the plug-in.
