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
        <li>
          <h4 className="submenu-title">Utilities</h4>
          <ul>
            <li>
              <Link to="https://bluebrainnexus.io/docs/utilities#js-sdk-for-nexus">
                Nexus.js
              </Link>
            </li>
            <li>
              <Link to="https://bluebrainnexus.io/docs/utilities#nexus-python-sdk">
                Python SDK
              </Link>
            </li>
            <li>
              <Link to="https://bluebrainnexus.io/docs/utilities#nexus-cli">
                CLI
              </Link>
            </li>
          </ul>
        </li>
      </ul>
    </div>
  )
}

export default ProductList
