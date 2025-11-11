var exec = require('cordova/exec');

function start(options, success, fail) {
  // options: { regex, minStableFrames, boxPercent } – all optional
  exec(success, fail, 'LiveOCR', 'start', [options || {}]);
}

function stop(success, fail) {
  exec(success, fail, 'LiveOCR', 'stop', []);
}

module.exports = {
  start: start,
  stop: stop
};
