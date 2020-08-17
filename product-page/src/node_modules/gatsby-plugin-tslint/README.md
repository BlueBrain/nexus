# gatsby-plugin-tslint

Provides drop-in support for TSLint. Based off of [gatsby-plugin-eslint](https://github.com/mongkuen/gatsby-plugin-eslint).


**NOTE:**  This plugin is currently only available for Gatsby v2.

## Installation

1. Install the `gatsby-plugin-tslint` plugin:

    `npm install --save-dev gatsby-plugin-tslint`

    or

    `yarn add --dev gatsby-plugin-tslint`


2. Install [TSLint](https://palantir.github.io/tslint/) and [`tslint-loader`](https://github.com/wbuchwalter/tslint-loader):

    `npm install --save-dev tslint tslint-loader`

    or

    `yarn add --dev tslint tslint-loader`

## Usage

Add into `gatsby-config.js`.

```javascript
// gatsby-config.js

module.exports = {
  plugins: [
    'gatsby-plugin-tslint'
  ]
}
```

If no options are specified, the plugin defaults to:

1. Lint `.ts` and `.tsx` files.
2. Exclude `node_modules`, `.cache`, and `public` folders from linting.

You can specify your own linting filetypes and exclusions:

```javascript
// gatsby-config.js
module.exports = {
  plugins: [
    {
      resolve: 'gatsby-plugin-tslint',
      options: {
        test: /\.ts$|\.tsx$/,
        exclude: /(node_modules|cache|public)/
      }
    }
  ]
}
```

## Configuring TSLint

This plugin assumes that you use a `tslint.json` file to configure TSLint. Use those files to do all of your configuration.
