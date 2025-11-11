var exec = require('cordova/exec');

exports.start = function (success, error) {
    exec(success, error, 'LiveOCR', 'start', []);
};

exports.stop = function () {
    exec(function(){}, function(){}, 'LiveOCR', 'stop', []);
};
