// configure all the files to copy from each of the resource paths.
// key of object is the source file, value is the destination location.
// the directory/file structure used closely mirrors how the resources
// are stored in each platform
var androidFilesToCopy = {
    // android icons
    "model.tflite": "model.tflite",
    "labels.txt": "labels.txt"
};

// required node modules
var fs = require('fs');
var path = require('path');
var rootdir = "";
var buildDir = "";

// these resource paths need to exist in the root of your Codova project
var configAndroidPath = 'model/';

// android platform resource path
var platformAndroidPath = 'platforms/android/app/src/main/ml';

filesToCopy(androidFilesToCopy, 'android');


// function that copies resource files to choosen platform
function filesToCopy(obj, platform) {
    var srcFile, destFile, destDir;

    Object.keys(obj).forEach(function (key) {
        if (platform === 'android') {
            srcFile = path.join(rootdir, configAndroidPath, key);
            destFile = path.join(buildDir, platformAndroidPath, obj[key]);
        }
        console.log('copying ' + srcFile + ' to ' + destFile);

        console.log('file exists: ' + fs.existsSync(srcFile));
        console.log('destination directory exists: ' + fs.existsSync(destDir));

        destDir = path.dirname(destFile);
        console.log("destination dir: " + destDir);
        if (!fs.existsSync(destDir)) {
            console.log('directory doesn\'t exist');
            fs.mkdirSync(destDir, {recursive: true});
        }

        console.log('destination directory exists: ' + fs.existsSync(destDir));

        if (fs.existsSync(srcFile) && fs.existsSync(destDir)) {
            fs.createReadStream(srcFile).pipe(fs.createWriteStream(destFile));
        }
    });
}