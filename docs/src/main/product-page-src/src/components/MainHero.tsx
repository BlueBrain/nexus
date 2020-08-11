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
            Better data management starts here. <br /> and we're open source.
          </h1>
          <h2 className="subtitle">
            Quickly build, manage and leverage Knowledge Graphs using our Python
            framework, web application and services.
          </h2>
          <div className="columns" style={{ width: "50%" }}>
            <div className="column">
              <a href="#what" onClick={scrollIntoView(pathname, "what")}>
                <button className="button">What is Nexus?</button>
              </a>
            </div>
            <div className="column">
              <a href="#why" onClick={scrollIntoView(pathname, "why")}>
                <button className="button">Why Nexus?</button>
              </a>
            </div>

            <div className="column">
              <a
                href="#getting-started"
                onClick={scrollIntoView(pathname, "getting-started")}
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
