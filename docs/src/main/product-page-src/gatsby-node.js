const data = require("./data")

exports.createPages = async ({ actions: { createPage } }) => {
  // Product Pages
  data.products.forEach(product => {
    createPage({
      path: `/products/${product.slug}/`,
      component: require.resolve("./src/templates/ProductPage.tsx"),
      context: { product },
    })
  })
}
