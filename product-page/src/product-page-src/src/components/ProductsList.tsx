import * as React from "react"
import { Link } from "gatsby"

const ProductList: React.FC = () => {
  return (
    <div>
      <ul className="submenu">
        <li>
          <Link to="/products/nexus-fusion">Nexus Fusion</Link>
        </li>
        <li>
          <Link to="/products/nexus-forge">Nexus Forge</Link>
        </li>
        <li>
          <Link to="/products/nexus-delta">Nexus Delta</Link>
        </li>
      </ul>
    </div>
  )
}

export default ProductList
