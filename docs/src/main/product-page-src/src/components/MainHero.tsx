import * as React from "react"
import { useLocation } from "@reach/router"
import { scrollIntoView } from "../libs/scroll"
import lines from "../libs/lines"

export default function MainHero() {
  const { pathname } = useLocation()

  React.useEffect(() => {
    return lines("gradient")
  }, [])

  return (
    <section className="hero is-fullheight">
      <div className="gradient" id="gradient" />
      <div className="hero-body">
        <div className="container">
          <h1 className="title is-spaced">
            Better data management starts here <br /> and we're open source
          </h1>
          <h2 className="subtitle">
            Quickly build, use, and manage knowledge graphs using our web app,
            backend services, or python framework.
          </h2>
          <div className="columns">
            <div className="column">
              <a
                href="#why-nexus"
                onClick={scrollIntoView(pathname, "why-nexus")}
              >
                <button className="button">Why Nexus?</button>
              </a>
            </div>
            <div className="column">
              <a
                href="#what-is-nexus"
                onClick={scrollIntoView(pathname, "what-is-nexus")}
              >
                <button className="button">What is Nexus?</button>
              </a>
            </div>
            <div className="column">
              <a
                href="#get-started"
                onClick={scrollIntoView(pathname, "get-started")}
              >
                <button className="button">Get Started</button>
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
