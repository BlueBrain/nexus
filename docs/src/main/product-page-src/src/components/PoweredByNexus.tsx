import * as React from "react"

import camhLogo from "../../static/img/logos/camh.svg"
import ebrainsLogo from "../../static/img/logos/ebrains.svg"
import epflLogo from "../../static/img/logos/epfl.svg"
import conpLogo from "../../static/img/logos/conp-pcno-logo.png"

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
                <p>Blue Brain Project</p>
              </a>
            </div>

            <div className="column">
              <a href="https://kg.ebrains.eu/">
                <img src={ebrainsLogo} alt="EBRAINS" width="80px" />
                <p>EBRAINS</p>
              </a>
            </div>

            <div className="column">
              <a href="https://www.camh.ca/en/science-and-research/institutes-and-centres/krembil-centre-for-neuroinformatics">
                <img
                  src={camhLogo}
                  alt="The Centre for Addiction and Mental Health"
                />
                <p>Krembil Centre for Neuroinformatics</p>
              </a>
            </div>

            <div className="column">
              <a href="https://conp.ca/">
                <img src={conpLogo} alt="Canadian Open Neuroscience Platform" />
                <p>Canadian Open Neuroscience Platform</p>
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
