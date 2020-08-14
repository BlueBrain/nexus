import * as React from "react"

const DevList: React.FC = () => {
  return (
    <div className="dev-list">
      <ul className="submenu">
        <li>
          <a href="https://bluebrainnexus.io/docs">Overview</a>
        </li>

        <li>
          <a href="https://bluebrainnexus.io/docs/releases">Releases</a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/roadmap">Roadmap</a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/faq">FAQ</a>
        </li>
        <li>
          <h4 className="submenu-title">By Product</h4>
          <ul className="submenu">
            <li>
              <a href="https://bluebrainnexus.io/docs/fusion">Nexus Fusion</a>
            </li>
            <li>
              <a href="https://bluebrainnexus.io/docs/forge">Nexus Forge</a>
            </li>
            <li>
              <a href="https://bluebrainnexus.io/docs/delta">Nexus Delta</a>
            </li>
            <li>
              <a href="https://bluebrainnexus.io/docs/utilities">Utilities</a>
            </li>
          </ul>
        </li>
      </ul>
    </div>
  )
}

export default DevList
