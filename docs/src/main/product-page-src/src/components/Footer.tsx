import * as React from "react"
import { Link } from "gatsby"

export default function Footer() {
  return (
    <footer className="footer gradient">
      <div className="content">
        <div className="columns">
          <a href="">
            <img className="logo"></img>
          </a>
        </div>
        <p>
          Blue Brain Nexus is Open Source and available under the Apache License
          2.0
        </p>
        <p>Blue Brain Project/EPFL 2005 – 2020. All rights reserved.</p>
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
