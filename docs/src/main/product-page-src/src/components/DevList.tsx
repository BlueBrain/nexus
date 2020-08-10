import * as React from "react"

const DevList: React.FC = () => {
  return (
    <div className="dev-list">
      <ul className="submenu">
        <li>
          <a>Overview</a>
        </li>
        <li>
          <a>Architecture</a>
        </li>
        <li>
          <a>Releases</a>
        </li>
        <li>
          <a>Roadmap</a>
        </li>
        <li>
          <a>Building Knowledge graphs with the Forge</a>
        </li>
        <li>
          <h4 className="submenu-title">By Product</h4>
          <ul className="submenu">
            <li>
              <a>Nexus Forge</a>
            </li>
            <li>
              <a>Nexus Fusion</a>
            </li>
            <li>
              <a>Nexus Delta</a>
            </li>
            <li>
              <a>Nexus.js</a>
            </li>
            <li>
              <a>Python SDK</a>
            </li>
            <li>
              <a>CLI</a>
            </li>
          </ul>
        </li>
      </ul>
    </div>
  )
}

export default DevList
