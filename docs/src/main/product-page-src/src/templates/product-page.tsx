import * as React from "react"

export type Product = {
  name: string
  slug: string
}

const ProductPage = ({ pageContext: { product } }) => {
  const { name } = product
  return (
    <div>
      <h1>{name}</h1>
    </div>
  )
}

export default ProductPage
