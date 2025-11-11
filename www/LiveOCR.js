var exec = require('cordova/exec');

exports.start = function (options, success, error) {
  options = options || {};
  exec(success, error, 'LiveOCR', 'start', [options]);
};
