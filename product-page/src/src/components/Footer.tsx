import * as React from "react"

import DevList from "./DevList"
import ProductList from "./ProductsList"
import Termslist from "./Termslist"

import bbpLogo from "../../static/img/logos/epfl-bbp-portrait.png"
import gitterLogo from "../../static/img/logos/gitter.svg"
import linkedInLogo from "../../static/img/logos/linkedin.svg"
import githubLogoSquare from "../../static/img/logos/github-square.svg"
import twitterLogo from "../../static/img/logos/twitter-square.svg"

export default function Footer() {
  return (
    <footer className="footer gradient">
      <div className="container">
        <div className="content">
          <div className="columns">
            <div className="column">
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
              <ProductList />
            </div>
            <div className="column">
              <h3>Developers</h3>
              <DevList />
            </div>
            <div className="column">
              <h3>Terms</h3>
              <Termslist />
            </div>
            <div className="column">
              <h3>Contact Us</h3>
              <div className="social-icons">
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
              <div className="contacts">
                <p>EPFL Blue Brain Project</p>
                <p>Campus Biotech</p>
                <p>Chemin des Mines 9</p>
                <p>1202 Geneva</p>
                <p>
                  <a href="mailto:hello@bluebrainnexus.io">
                    hello@bluebrainnexus.io
                  </a>
                </p>
                <p>
                  <a href="tel:+41 21 69 37 660">+41 21 69 37 660</a>
                </p>
              </div>
            </div>
          </div>
          <div className="legal">
            <p>
              Blue Brain Nexus is Open Source and available under the Apache
              License 2.0.
            </p>
            <p>Blue Brain Project/EPFL 2005 – 2021. All rights reserved.</p>
          </div>
        </div>
      </div>
    </footer>
  )
}
