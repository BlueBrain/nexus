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
var OPTION_ALWAYS = "always";
var OPTION_NEVER = "never";
var CURLY_PRESENCE_VALUES = [OPTION_ALWAYS, OPTION_NEVER];
var CURLY_PRESENCE_OBJECT = {
    enum: CURLY_PRESENCE_VALUES,
    type: "string",
};
var Rule = /** @class */ (function (_super) {
    __extends(Rule, _super);
    function Rule() {
        return _super !== null && _super.apply(this, arguments) || this;
    }
    Rule.prototype.apply = function (sourceFile) {
        var option = Array.isArray(this.ruleArguments) ? this.ruleArguments[0] : undefined;
        return this.applyWithFunction(sourceFile, walk, option);
    };
    /* tslint:disable:object-literal-sort-keys */
    Rule.metadata = {
        ruleName: "jsx-curly-brace-presence",
        description: "Enforce curly braces or disallow unnecessary curly braces in JSX props",
        hasFix: true,
        optionsDescription: Lint.Utils.dedent(templateObject_1 || (templateObject_1 = __makeTemplateObject(["\nOne of the following options may be provided under the \"props\" key:\n\n* `\"", "\"` requires JSX attributes to have curly braces around string literal values\n* `\"", "\"` requires JSX attributes to NOT have curly braces around string literal values\n\nIf no option is provided, \"", "\" is chosen as default."], ["\nOne of the following options may be provided under the \"props\" key:\n\n* \\`\"", "\"\\` requires JSX attributes to have curly braces around string literal values\n* \\`\"", "\"\\` requires JSX attributes to NOT have curly braces around string literal values\n\nIf no option is provided, \"", "\" is chosen as default."])), OPTION_ALWAYS, OPTION_NEVER, OPTION_NEVER),
        options: {
            type: "object",
            properties: {
                props: CURLY_PRESENCE_OBJECT,
            },
        },
        optionExamples: [
            "{ props: \"" + OPTION_ALWAYS + "\" }",
            "{ props: \"" + OPTION_NEVER + "\" }",
        ],
        type: "style",
        typescriptOnly: false,
    };
    /* tslint:enable:object-literal-sort-keys */
    Rule.FAILURE_CURLY_BRACE_SUPERFLUOUS = "JSX attribute must NOT have curly braces around string literal";
    Rule.FAILURE_CURLY_BRACE_MISSING = "JSX attribute must have curly braces around string literal";
    return Rule;
}(Lint.Rules.AbstractRule));
exports.Rule = Rule;
function walk(ctx) {
    return ts.forEachChild(ctx.sourceFile, validateCurlyBraces);
    function validateCurlyBraces(node) {
        if (_3_0_1.isJsxAttribute(node)) {
            if (typeof ctx.options === "object" && ctx.options.props === OPTION_ALWAYS) {
                validateCurlyBracesArePresent(node);
            }
            else {
                validateCurlyBracesAreNotPresent(node);
            }
        }
        return ts.forEachChild(node, validateCurlyBraces);
    }
    function validateCurlyBracesArePresent(node) {
        var initializer = node.initializer;
        if (initializer !== undefined) {
            var hasStringInitializer = initializer.kind === ts.SyntaxKind.StringLiteral;
            if (hasStringInitializer) {
                var fix = Lint.Replacement.replaceNode(initializer, "{" + initializer.getText() + "}");
                ctx.addFailureAtNode(initializer, Rule.FAILURE_CURLY_BRACE_MISSING, fix);
            }
        }
    }
    function validateCurlyBracesAreNotPresent(node) {
        var initializer = node.initializer;
        if (initializer !== undefined
            && _3_0_1.isJsxExpression(initializer)
            && initializer.expression !== undefined) {
            if (_3_0_1.isStringLiteral(initializer.expression)) {
                var stringLiteralWithoutCurlies = initializer.expression.getText();
                var fix = Lint.Replacement.replaceNode(initializer, stringLiteralWithoutCurlies);
                ctx.addFailureAtNode(initializer, Rule.FAILURE_CURLY_BRACE_SUPERFLUOUS, fix);
            }
            else if (_3_0_1.isTextualLiteral(initializer.expression)) {
                var textualLiteralContent = initializer.expression.text;
                var fix = Lint.Replacement.replaceNode(initializer, "\"" + textualLiteralContent + "\"");
                ctx.addFailureAtNode(initializer, Rule.FAILURE_CURLY_BRACE_SUPERFLUOUS, fix);
            }
        }
    }
}
var templateObject_1;
