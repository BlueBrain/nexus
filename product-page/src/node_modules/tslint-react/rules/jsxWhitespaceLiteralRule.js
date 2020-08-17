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
var RESERVED_ENTITY = "&nbsp;";
var Rule = /** @class */ (function (_super) {
    __extends(Rule, _super);
    function Rule() {
        return _super !== null && _super.apply(this, arguments) || this;
    }
    Rule.prototype.apply = function (sourceFile) {
        return this.applyWithFunction(sourceFile, walk);
    };
    Rule.metadata = {
        description: Lint.Utils.dedent(templateObject_1 || (templateObject_1 = __makeTemplateObject(["\n            Warn if '&nbsp;' is used in JSX markup. Prefer {\" \"} over '&nbsp;'\n        "], ["\n            Warn if '&nbsp;' is used in JSX markup. Prefer {\" \"} over '&nbsp;'\n        "]))),
        optionExamples: ["true"],
        options: null,
        optionsDescription: "",
        ruleName: "jsx-whitespace-literal",
        type: "functionality",
        typescriptOnly: false,
    };
    Rule.FAILURE_STRING = "Expected '{\" \"}' instead of '&nbsp;' in JSX markup";
    return Rule;
}(Lint.Rules.AbstractRule));
exports.Rule = Rule;
function walk(ctx) {
    return ts.forEachChild(ctx.sourceFile, function cb(node) {
        if (_3_0_1.isJsxText(node)) {
            var text = node.getText();
            if (text.indexOf(RESERVED_ENTITY) > -1) {
                var regex = new RegExp(RESERVED_ENTITY, "g");
                var startIndices = [];
                var endIndices_1 = [];
                var countEnitiy = -1;
                var result = regex.exec(text);
                while (result !== null) {
                    if (startIndices[countEnitiy] !== undefined &&
                        endIndices_1[countEnitiy] !== undefined &&
                        startIndices[countEnitiy] + endIndices_1[countEnitiy] === result.index) {
                        endIndices_1[countEnitiy] = endIndices_1[countEnitiy] + RESERVED_ENTITY.length;
                    }
                    else {
                        startIndices.push(result.index);
                        endIndices_1.push(RESERVED_ENTITY.length);
                        countEnitiy += 1;
                    }
                    result = regex.exec(text);
                }
                startIndices.forEach(function (startIndex, index) {
                    var start = node.getStart() + startIndex;
                    var end = endIndices_1[index];
                    var spaces = " ".repeat(end / RESERVED_ENTITY.length);
                    var fix = Lint.Replacement.replaceFromTo(start, start + end, "{\"" + spaces + "\"}");
                    ctx.addFailureAt(start, end, Rule.FAILURE_STRING, fix);
                });
            }
        }
        return ts.forEachChild(node, cb);
    });
}
var templateObject_1;
