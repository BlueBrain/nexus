"use strict";

var _interopRequireDefault = require("@babel/runtime/helpers/interopRequireDefault");

exports.__esModule = true;
exports.promptPackageManager = exports.setPackageManager = exports.getPackageManager = void 0;

var _gatsbyCoreUtils = require("gatsby-core-utils");

var _prompts = _interopRequireDefault(require("prompts"));

var _reporter = _interopRequireDefault(require("../reporter"));

const packageMangerConfigKey = `cli.packageManager`;

const getPackageManager = () => (0, _gatsbyCoreUtils.getConfigStore)().get(packageMangerConfigKey);

exports.getPackageManager = getPackageManager;

const setPackageManager = packageManager => {
  (0, _gatsbyCoreUtils.getConfigStore)().set(packageMangerConfigKey, packageManager);

  _reporter.default.info(`Preferred package manager set to "${packageManager}"`);
};

exports.setPackageManager = setPackageManager;

const promptPackageManager = async () => {
  const promptsAnswer = await (0, _prompts.default)([{
    type: `select`,
    name: `package_manager`,
    message: `Which package manager would you like to use ?`,
    choices: [{
      title: `yarn`,
      value: `yarn`
    }, {
      title: `npm`,
      value: `npm`
    }],
    initial: 0
  }]);
  const response = promptsAnswer.package_manager;

  if (response) {
    setPackageManager(response);
  }

  return response;
};

exports.promptPackageManager = promptPackageManager;