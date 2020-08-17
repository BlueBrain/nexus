import * as React from "react"

import GithubLogoRound from "../../static/img/logos/github-round.svg"

export default function WeAreOpenSource() {
  return (
    <section className="call-out gradient" id="we-are-open-source">
      <div className="container">
        <div className="content">
          <img src={GithubLogoRound} alt="GitHub" />
          <p>
            We are open-source. <br />
            Check our{" "}
            <a href="https://github.com/BlueBrain/nexus" target="_blank">
              repositories
            </a>
            , report issues, and start contributing!
          </p>
        </div>
      </div>
    </section>
  )
}
