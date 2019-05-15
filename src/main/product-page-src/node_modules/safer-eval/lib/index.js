/**
* @copyright 2017 Commenthol
* @license MIT
*/
'use strict';

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } }

function _createClass(Constructor, protoProps, staticProps) { if (protoProps) _defineProperties(Constructor.prototype, protoProps); if (staticProps) _defineProperties(Constructor, staticProps); return Constructor; }

var vm = require('vm');

var _require = require('./common'),
    createContext = _require.createContext,
    allow = _require.allow;
/**
* reuse saferEval context
* @class
* @example
* const {SaferEval} = require('safer-eval')
* const safer = new SaferEval()
* let res1 = safer.runInContext('new Date('1970-01-01')')
* let res2 = safer.runInContext('new Date('1970-07-01')')
*/


var SaferEval =
/*#__PURE__*/
function () {
  /**
  * @param {Object} [context] - allowed context
  * @param {Object} [options] - options for `vm.runInContext`
  */
  function SaferEval(context, options) {
    _classCallCheck(this, SaferEval);

    // define disallowed objects in context
    var __context = createContext(); // apply "allowed" context vars


    allow(context, __context);
    this._context = vm.createContext(__context);
    this._options = options;
  }
  /**
  * @param {String} code - a string containing javascript code
  * @return {Any} evaluated code
  */


  _createClass(SaferEval, [{
    key: "runInContext",
    value: function runInContext(code) {
      if (typeof code !== 'string') {
        throw new TypeError('not a string');
      }

      var src = 'Object.constructor = function () {};\n';
      src += 'return ' + code + ';\n';
      return vm.runInContext('(function () {"use strict"; ' + src + '})()', this._context, this._options);
    }
  }]);

  return SaferEval;
}();
/**
* A safer approach for eval. (node)
*
* In node the `vm` module is used to sandbox the evaluation of `code`.
*
* `context` allows the definition of passed in Objects into the sandbox.
* Take care, injected `code` can overwrite those passed context props!
* Check the tests under "harmful context"!
*
* @static
* @throws Error
* @param {String} code - a string containing javascript code
* @param {Object} [context] - define globals, properties for evaluation context
* @return {Any} evaluated code
* @example
* var code = `{d: new Date('1970-01-01'), b: new Buffer('data')}`
* var res = saferEval(code, {Buffer: Buffer})
* // => toString.call(res.d) = '[object Date]'
* // => toString.call(res.b) = '[object Buffer]'
*/


function saferEval(code, context) {
  return new SaferEval(context).runInContext(code);
}

module.exports = saferEval;
module.exports.SaferEval = SaferEval;