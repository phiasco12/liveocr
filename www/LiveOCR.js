var exec = require('cordova/exec');

var LiveOCR = {
  start: function (success, error) {
    exec(success, error, 'LiveOCR', 'start', []);
  },
  stop: function (success, error) {
    exec(success, error, 'LiveOCR', 'stop', []);
  }
};

module.exports = LiveOCR;
