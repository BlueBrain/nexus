"use strict";
/**
 * @license
 * Copyright 2019 Palantir Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
var __extends = (this && this.__extends) || (function () {
    var extendStatics = function (d, b) {
        extendStatics = Object.setPrototypeOf ||
            ({ __proto__: [] } instanceof Array && function (d, b) { d.__proto__ = b; }) ||
            function (d, b) { for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p]; };
        return extendStatics(d, b);
    };
    return function (d, b) {
        extendStatics(d, b);
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    };
})();
var __makeTemplateObject = (this && this.__makeTemplateObject) || function (cooked, raw) {
    if (Object.defineProperty) { Object.defineProperty(cooked, "raw", { value: raw }); } else { cooked.raw = raw; }
    return cooked;
};
Object.defineProperty(exports, "__esModule", { value: true });
var Lint = require("tslint");
var _3_0_1 = require("tsutils/typeguard/3.0");
var ts = require("typescript");
var FRAGMENT_TAGNAME = "Fragment";
var REACT_FRAGEMNT_TAGNAME = "React.Fragment";
var Rule = /** @class */ (function (_super) {
    __extends(Rule, _super);
    function Rule() {
        return _super !== null && _super.apply(this, arguments) || this;
    }
    Rule.prototype.apply = function (sourceFile) {
        return this.applyWithFunction(sourceFile, walk);
    };
    Rule.metadata = {
        description: Lint.Utils.dedent(templateObject_1 || (templateObject_1 = __makeTemplateObject(["\n            Warn if unnecessary fragment is used'\n        "], ["\n            Warn if unnecessary fragment is used'\n        "]))),
        optionExamples: ["true"],
        options: null,
        optionsDescription: "",
        ruleName: "react-no-unnecessary-fragment",
        type: "style",
        typescriptOnly: false,
    };
    Rule.FAILURE_STRING = "Unnecessary Fragment are forbidden";
    return Rule;
}(Lint.Rules.AbstractRule));
exports.Rule = Rule;
function walk(ctx) {
    return ts.forEachChild(ctx.sourceFile, function cb(node) {
        if ((_3_0_1.isJsxFragment(node) && _3_0_1.isJsxOpeningFragment(node.openingFragment)) ||
            (_3_0_1.isJsxElement(node) && isJSXFragmentElement(node.openingElement))) {
            var numValidChildren = 0;
            for (var _i = 0, _a = node.children; _i < _a.length; _i++) {
                var child = _a[_i];
                if (_3_0_1.isJsxText(child)) {
                    if (!isInvalidJSXText(child)) {
                        numValidChildren += 1;
                    }
                }
                else {
                    numValidChildren += 1;
                }
                if (numValidChildren > 1) {
                    break;
                }
            }
            if (numValidChildren <= 1) {
                ctx.addFailureAtNode(node, Rule.FAILURE_STRING);
            }
        }
        else if (_3_0_1.isJsxSelfClosingElement(node) && isJSXFragmentElement(node)) {
            ctx.addFailureAtNode(node, Rule.FAILURE_STRING);
        }
        return ts.forEachChild(node, cb);
    });
}
function isJSXFragmentElement(node) {
    if (node.tagName.getText() === FRAGMENT_TAGNAME ||
        node.tagName.getText() === REACT_FRAGEMNT_TAGNAME) {
        return true;
    }
    return false;
}
function isInvalidJSXText(node) {
    return node.getText().trim() === "" ? true : false;
}
var templateObject_1;
