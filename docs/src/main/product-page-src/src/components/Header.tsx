import * as React from "react"
import { Link } from "gatsby"

import nexusLogo from "../../static/img/logos/nexus.png"
import epflLogo from "../../static/img/logos/epfl.svg"
import MobileMenu from "./MobileMenu"
import NavMenu from "./NavMenu"

export default function Header() {
  return (
    <header className="header">
      <div className="container">
        <div className="content stretch">
          <a href="https://www.epfl.ch/" target="_blank">
            <div className="epfl-logo">
              <img src={epflLogo} alt="EPFL logo" />
            </div>
          </a>
          <Link to="/">
            <div className="logo">
              <img src={nexusLogo} alt={"Nexus Logo"} />
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
