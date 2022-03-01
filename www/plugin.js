var exec = require('cordova/exec');

var PLUGIN_NAME = 'Pose';

var Pose = {
    echo: function (phrase, cb) {
        exec(cb, null, PLUGIN_NAME, 'echo', [phrase]);
    },
    getLandmarks: function (cb) {
        exec((data) => cb(JSON.parse(data)), null, PLUGIN_NAME, 'getLandmarks', []);
    },
    getLandmarksDebugString: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'getLandmarksDebugString', []);
    },
    setLabelCallback: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'setLabelCallback', []);
    },
    setVideoCallback: function (cb) {
        exec(cb, null, PLUGIN_NAME, 'setVideoCallback', []);
    }
};

module.exports = Pose;
