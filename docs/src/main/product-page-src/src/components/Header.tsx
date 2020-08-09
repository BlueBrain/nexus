import * as React from "react"
import { Link } from "gatsby"
// @ts-ignore
import SiteNav, { ContentGroup } from "react-site-nav"

import nexusLogo from "../../static/img/logos/nexus.png"

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
          <SiteNav>
            <ContentGroup title="About" height="200">
              {/* 3. You can add anything in a ContentGroup */}
              <ul>
                {/* react router link! */}
                <li>
                  <Link to="/my-story">My Story</Link>
                </li>
                <li>Another list item</li>
              </ul>
            </ContentGroup>
            <ContentGroup title="Contact" height="200">
              Free text followed by some links.
              <br />
              <a href="mailto:yusinto@gmail.com">Email</a>
              <br />
              <a href="https://github.com/yusinto">Github</a>
            </ContentGroup>
          </SiteNav>
        </div>
      </div>
    </header>
  )
}
