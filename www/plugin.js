var exec = require('cordova/exec');

var PLUGIN_NAME = 'Pose';

var Pose = {
    getLandmarks: function (cb) {
        exec((data) => cb(JSON.parse(data)), null, PLUGIN_NAME, 'getLandmarks', []);
    },
    getLandmarksDebugString: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'getLandmarksDebugString', []);
    },
    getVideoFrame: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'getVideoFrame', []);
    },
    setLabelCallback: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'setLabelCallback', []);
    }
};

module.exports = Pose;
