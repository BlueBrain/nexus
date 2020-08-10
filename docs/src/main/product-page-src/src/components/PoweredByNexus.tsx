import * as React from "react"

import camhLogo from "../../static/img/logos/camh.svg"
import hbpLogo from "../../static/img/logos/hbp.svg"
import epflLogo from "../../static/img/logos/epfl.svg"

export default function PoweredByNexus() {
  return (
    <section className="call-out gradient" id="powered-by-nexus">
      <div className="container">
        <div className="content centered">
          <h4 className="title is-4">Powered by Nexus</h4>
          <div className="columns">
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
              <a href="https://www.humanbrainproject.eu/en/">
                <img src={hbpLogo} alt="The Human Brain Project" />
                <p>Human Brain Poject</p>
              </a>
            </div>

            <div className="column">
              <a href="https://www.epfl.ch/research/domains/bluebrain/">
                <img
                  src={epflLogo}
                  alt="École Polytechnique Fédérale de Lausanne"
                />
                <p>Blue Brain Project</p>
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
