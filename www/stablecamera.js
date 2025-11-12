var exec = require('cordova/exec');

exports.start = function (success, error) {
  exec(success, error, 'StableCamera', 'start', []);
};
