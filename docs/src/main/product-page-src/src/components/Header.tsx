import * as React from "react"
import { Link } from "gatsby"

import nexusLogo from "../../static/img/logos/nexus.png"

export default function Header() {
  return (
    <header className="header">
      <div className="container">
        <div className="logo">
          <img className="" src={nexusLogo} alt={"Nexus Logo"} />
          <span>Blue Brain Nexus</span>
        </div>
      </div>
    </header>
  )
}
