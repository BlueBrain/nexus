const data = require("./data.json")

exports.createPages = async ({ actions: { createPage } }) => {
  // Product Pages
  console.log({ data })
  data.products.forEach(product => {
    createPage({
      path: `/products/${product.slug}/`,
      component: require.resolve("./src/templates/ProductPage.tsx"),
      context: { product },
    })
  })
}
