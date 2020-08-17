import * as React from "react"

import fusion from "../../static/img/fusion.webp"
import delta from "../../static/img/delta.webp"
import forge from "../../static/img/forge.webp"

const ProductDiagram: React.FC<{ name: string }> = ({ name }) => {
  let diagram

  switch (name) {
    case "nexus-fusion":
      diagram = fusion
      break
    case "nexus-delta":
      diagram = delta
      break
    case "nexus-forge":
      diagram = forge
      break
    default:
      diagram = fusion
  }

  return (
    <div className="product-diagram">
      <img src={diagram} />
    </div>
  )
}

export default ProductDiagram
