// A static JSON require works on Node 18 and lets esbuild include the version in Electron's bundle.
module.exports = require('../package.json').version;
