import * as React from "react"
import { Link } from "gatsby"

import nexusLogo from "../../static/img/logos/nexus.png"
import MobileMenu from "./MobileMenu"
import NavMenu from "./NavMenu"

export default function Header() {
  return (
    <header className="header">
      <div className="container">
        <div className="content stretch">
          <Link to="/">
            <div className="logo">
              <img className="" src={nexusLogo} alt={"Nexus Logo"} />
              <span>Blue Brain Nexus</span>
            </div>
          </Link>
          <NavMenu />
          <MobileMenu />
        </div>
      </div>
    </header>
  )
}
