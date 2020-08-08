import * as React from "react"
import MainLayout from "../layouts/main"

export type Product = {
  name: string
  slug: string
}

const ProductPage: React.FC<{ pageContext: { product: Product } }> = ({
  pageContext: { product },
}) => {
  const { name } = product
  return (
    <MainLayout>
      <h1>{name}</h1>
    </MainLayout>
  )
}

export default ProductPage
