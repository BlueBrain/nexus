import * as React from "react"

import krembilLogo from "../../static/img/logos/krembil-logo.png"
import ebrainsLogo from "../../static/img/logos/ebrains.svg"
import epflLogo from "../../static/img/logos/epfl.svg"
import conpLogo from "../../static/img/logos/conp-pcno-logo.png"
import switchLogo from "../../static/img/logos/switch-logo.png"

export default function PoweredByNexus() {
  return (
    <section className="call-out gradient" id="powered-by-nexus">
      <div className="container">
        <div className="content centered">
          <h4 className="title is-4">Powered by Nexus</h4>
          <div className="columns is-desktop">
            <div className="column">
              <a href="https://www.epfl.ch/research/domains/bluebrain/">
                <img
                  src={epflLogo}
                  alt="École Polytechnique Fédérale de Lausanne"
                  className="logo"
                />
                <p className="text-centered">Blue Brain Project</p>
              </a>
            </div>

            <div className="column">
              <a href="https://kg.ebrains.eu/">
                <img
                  src={ebrainsLogo}
                  alt="EBRAINS"
                  width="80px"
                  className="logo"
                />
                <p className="text-centered">EBRAINS</p>
              </a>
            </div>

            <div className="column">
              <a
                href="https://www.camh.ca/en/science-and-research/institutes-and-centres/krembil-centre-for-neuroinformatics"
                className="krembil-link"
              >
                <img
                  src={krembilLogo}
                  alt="Krembil Centre for Neuroinformatics"
                  className="krembil-logo"
                />
              </a>
            </div>

            <div className="column">
              <a href="https://conp.ca/">
                <img
                  src={conpLogo}
                  alt="Canadian Open Neuroscience Platform"
                  className="logo"
                />
                <p className="text-centered">
                  Canadian Open Neuroscience Platform
                </p>
              </a>
            </div>

            <div className="column">
              <a href="https://www.switch.ch/about/open-science/">
                <p className="text-centered">The Research Data Connectome</p>
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
