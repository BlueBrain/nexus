import * as React from "react"

const GettingStartedList: React.FC = () => {
  return (
    <div>
      <ul className="submenu">
        <li>
          <a href="https://bluebrainnexus.io/docs/understanding-knowledge-graphs">
            Understanding Knowledge Graphs
          </a>
        </li>
        <li>
          <a href="https://github.com/BlueBrain/nexus-forge/tree/master/examples/notebooks/getting-started">
            Building Knowledge Graphs with Forge
          </a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/try-nexus">Try Nexus</a>
        </li>
        <li>
          <a href="https://bluebrainnexus.io/docs/runnin-nexus">
            Running Nexus
          </a>
        </li>
      </ul>
    </div>
  )
}

export default GettingStartedList
