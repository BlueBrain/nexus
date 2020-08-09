import * as React from "react"

const DevList: React.FC = () => {
  return (
    <div className="dev-list">
      <div>
        <ul className="submenu">
          <a>
            <li>Overview</li>
          </a>
          <a>
            <li>Architecture</li>
          </a>
          <a>
            <li>Releases</li>
          </a>
          <a>
            <li>Roadmap</li>
          </a>
          <a>
            <li>Building Knowledge graphs with the Forge</li>
          </a>
        </ul>
      </div>
      <div>
        <h4>By Product</h4>
        <ul className="submenu">
          <a>
            <li>Nexus Forge</li>
          </a>
          <a>
            <li>Nexus Fusion</li>
          </a>
          <a>
            <li>Nexus Delta</li>
          </a>
          <a>
            <li>Nexus.js</li>
          </a>
          <a>
            <li>Python SDK</li>
          </a>
          <a>
            <li>CLI</li>
          </a>
        </ul>
      </div>
    </div>
  )
}

export default DevList
