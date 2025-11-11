var exec = require('cordova/exec');

exports.start = function (opts, success, error) {
  exec(success, error, 'LiveOCR', 'start', [opts || {}]);
};

exports.stop = function (success, error) {
  exec(success, error, 'LiveOCR', 'stop', []);
};
