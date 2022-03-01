#!/usr/bin/env node

var fs = require('fs');
var path = require('path');
var et = require('elementtree');

module.exports = function(context) {
    console.log("editing package name");
    var config_xml = path.join(context.opts.projectRoot, 'config.xml');

    var data = fs.readFileSync(config_xml).toString();
    var etree = et.parse(data);

    var id = etree.getroot().attrib.id;

    var java = path.join(context.opts.projectRoot, 'plugins/cordova-plugin-mediapipepose/src/android/com/mediapipe/PoseTemplate.java');
    var javaData = fs.readFileSync(java).toString().replaceAll("<change_me_8IAnXxPstw>", id);

    try {
        fs.writeFileSync(context.opts.projectRoot + '/plugins/cordova-plugin-mediapipepose/src/android/com/mediapipe/Pose.java', javaData);
        // file written successfully
        console.log("changed id to : " + id);
    } catch (err) {
        console.error(err);
    }
};