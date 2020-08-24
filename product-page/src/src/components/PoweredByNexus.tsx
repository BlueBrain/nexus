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
          <div className="columns">
            <div className="column">
              <a href="https://www.epfl.ch/research/domains/bluebrain/">
                <img
                  src={epflLogo}
                  alt="École Polytechnique Fédérale de Lausanne"
                />
                <p className="text-centered">Blue Brain Project</p>
              </a>
            </div>

            <div className="column">
              <a href="https://kg.ebrains.eu/">
                <img src={ebrainsLogo} alt="EBRAINS" width="80px" />
                <p className="text-centered">EBRAINS</p>
              </a>
            </div>

            <div className="column">
              <a href="https://www.camh.ca/en/science-and-research/institutes-and-centres/krembil-centre-for-neuroinformatics">
                <img
                  src={krembilLogo}
                  alt="Krembil Centre for Neuroinformatics"
                  className="full"
                />
              </a>
            </div>

            <div className="column">
              <a href="https://conp.ca/">
                <img src={conpLogo} alt="Canadian Open Neuroscience Platform" />
                <p className="text-centered">
                  Canadian Open Neuroscience Platform
                </p>
              </a>
            </div>

            <div className="column">
              <a href="https://www.switch.ch/">
                <img src={switchLogo} alt="switch" className="full" />
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
