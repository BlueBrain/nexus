// prefer default export if available
const preferDefault = m => m && m.default || m

exports.components = {
  "component---src-pages-404-tsx": () => import("./../../src/pages/404.tsx" /* webpackChunkName: "component---src-pages-404-tsx" */),
  "component---src-pages-index-tsx": () => import("./../../src/pages/index.tsx" /* webpackChunkName: "component---src-pages-index-tsx" */),
  "component---src-templates-product-page-tsx": () => import("./../../src/templates/ProductPage.tsx" /* webpackChunkName: "component---src-templates-product-page-tsx" */)
}

