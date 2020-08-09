import * as React from "react"
import { Link } from "gatsby"

import epflLogo from "../../static/img/logos/epfl.svg"
import bbpLogo from "../../static/img/logos/bbp.png"
import gitterLogo from "../../static/img/logos/gitter.svg"
import linkedInLogo from "../../static/img/logos/linkedin.svg"
import githubLogoSquare from "../../static/img/logos/github-square.svg"
import twitterLogo from "../../static/img/logos/twitter-square.svg"
import GettingStartedList from "./GettingStartedList"
import DevList from "./DevList"

export default function Footer() {
  return (
    <footer className="footer gradient">
      <div className="container">
        <div className="content">
          <div className="columns">
            <div className="column">
              <a href="https://www.epfl.ch/en/">
                <img className="logo" src={epflLogo} alt="EPFL Logo" />
              </a>
              <br />
              <a href="https://www.epfl.ch/research/domains/bluebrain/">
                <img
                  className="logo"
                  src={bbpLogo}
                  alt="Blue Brain Project Logo"
                />
              </a>
            </div>
            <div className="column">
              <h3>Products</h3>
              <ul>
                <Link to="/products/nexus-fusion">
                  <li>Nexus Fusion</li>
                </Link>
                <Link to="/products/nexus-forge">
                  <li>Nexus Forge</li>
                </Link>
                <Link to="/products/nexus-delta">
                  <li>Nexus Delta</li>
                </Link>
              </ul>
            </div>
            <div className="column is-one-quarter">
              <h3>Getting Started</h3>
              <GettingStartedList />
            </div>
            <div className="column is-one-quarter">
              <h3>Developers</h3>
              <DevList />
            </div>
            <div className="column social-icons">
              <a href="https://gitter.im/BlueBrain/nexus">
                <img className="logo" src={gitterLogo} alt="Gitter" />
              </a>
              <a href="https://www.linkedin.com/showcase/blue-brain-project/">
                <img className="logo" src={linkedInLogo} alt="LinkedIn" />
              </a>
              <a href="https://github.com/BlueBrain/nexus">
                <img className="logo" src={githubLogoSquare} alt="GitHub" />
              </a>
              <a href="https://twitter.com/bluebrainnexus">
                <img className="logo" src={twitterLogo} alt="Twitter" />
              </a>
            </div>
          </div>
          <p>
            Blue Brain Nexus is Open Source and available under the Apache
            License 2.0
          </p>
          <p>Blue Brain Project/EPFL 2005 – 2020. All rights reserved.</p>
        </div>
      </div>
    </footer>
  )
}

// footer.footer.gradient
//   .content
//     .columns
//       .column
//         a(href="https://www.epfl.ch/en/")
//             img(
//             class="logo"
//             src="../img/logos/epfl.svg"
//             alt="EPFL Logo"
//           )
//         br
//         a(href="https://www.epfl.ch/research/domains/bluebrain/")
//           img(
//             class="logo"
//             src="../img/logos/bbp.png"
//             alt="Blue Brain Project logo"
//           )
//       .column
//         a(href="/")
//           h3 Products
//         ul
//           a(href="/")
//             li Fusion
//           a(href="/")
//             li Forge
//           a(href="/")
//             li Delta
//           a(href="/")
//             li Nexus.js
//           a(href="/")
//             li CLI
//           a(href="/")
//             li Roadmap
//       .column.is-one-quarter
//         h3 Getting Started
//         +gettingStartedList("footer-list")
//       .column
//         h3 Developers
//         +devList("footer-list")
//       .column.social-icons
//         a(href="https://gitter.im/BlueBrain/nexus")
//           img(
//             class="logo"
//             src="../img/logos/gitter.svg"
//             alt="Gitter"
//           )
//         //- TODO missing link
//         a(href="/")
//           img(
//             class="logo"
//             src="../img/logos/linkedin.svg"
//             alt="LinkedIn"
//           )
//         a(href="https://github.com/BlueBrain/nexus")
//           img(
//             class="logo"
//             src="../img/logos/github-square.svg"
//             alt="Github"
//           )
//         a(href="https://twitter.com/bluebrainnexus")
//           img(
//             class="logo"
//             src="../img/logos/twitter-square.svg"
//             alt="Twitter"
//           )
