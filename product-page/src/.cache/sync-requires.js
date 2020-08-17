const { hot } = require("react-hot-loader/root")

// prefer default export if available
const preferDefault = m => m && m.default || m


exports.components = {
  "component---src-pages-404-tsx": hot(preferDefault(require("/app/src/pages/404.tsx"))),
  "component---src-pages-index-tsx": hot(preferDefault(require("/app/src/pages/index.tsx"))),
  "component---src-templates-product-page-tsx": hot(preferDefault(require("/app/src/templates/ProductPage.tsx")))
}

