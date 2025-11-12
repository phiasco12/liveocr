var exec = require('cordova/exec');

exports.start = function(success, error, options) {
  exec(success, error, 'StableCamera', 'start', [options]);
};

