import * as React from "react"

import sandbox from "../../static/img/icons/sandbox.svg"
import py from "../../static/img/icons/py.svg"
import notebook from "../../static/img/icons/notebook.svg"

const getStartedLinks = [
  {
    title: "Try Nexus",
    image: sandbox,
    href: "https://sandbox.bluebrainnexus.io/",
  },
  {
    title: "Explore Python Notebooks",
    image: py,
    href:
      "https://github.com/BlueBrain/nexus-forge/tree/master/examples/notebooks",
  },
  {
    title: "Read the Documentation",
    image: notebook,
    href: "https://bluebrainnexus.io/docs",
  },
]

const GetStartedLink: React.FC<{
  title: string
  href: string
  image: string
}> = ({ title, href, image }) => {
  return (
    <div className="tile is-parent is-4">
      <a href={href} className="tile is-child box">
        <div className="get-started center-flex">
          <h4 className="title">{title}</h4>
          <div className="figure image is-64x64">
            <img src={image} alt={title} className="svgify primary subtle" />
          </div>
        </div>
      </a>
    </div>
  )
}

export default function GetStartedWithNexus() {
  return (
    <section id="getting-started">
      <div className="container with-room">
        <div className="content centered">
          <h2>Get Started</h2>
          <p className="subtitle">
            From playing with our sandbox web interface, exploring python
            notebooks, or reading more about technical aspects in the docs,
            you’re covered. You can also visit our code base on Github or
            contact us directly.
          </p>
          <div className="tile is-ancestor wrapping">
            {getStartedLinks.map(getStartedLink => (
              <GetStartedLink {...getStartedLink} key={getStartedLink.title} />
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
