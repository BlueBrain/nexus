import * as React from "react"

const GettingStartedList: React.FC = () => {
  return (
    <div>
      <ul className="submenu">
        <li>
          <a href="https://bluebrainnexus.io/docs/getting-started/understanding-knowledge-graphs.html">
            Understanding Knowledge Graphs
          </a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/getting-started/try-nexus.html">
            Try Nexus
          </a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/getting-started/running-nexus.html">
            Running Nexus
          </a>
        </li>
      </ul>
    </div>
  )
}

export default GettingStartedList
