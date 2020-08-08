import * as React from "react"

const getStartedLinks = [
  {
    title: "Play in the Sandbox",
    href: "",
  },
  {
    title: "Explore Python Notebooks",
    href: "",
  },
  {
    title: "Read the Documentation",
    href: "/docs",
  },
]

const GetStartedLink: React.FC<{
  title: string
  href: string
}> = ({ title, href }) => {
  return (
    <div className="get-started center-flex">
      <h4 className="title">{title}</h4>
      <div className="figure image is-128x128">
        <img
          src="https://bulma.io/images/placeholders/128x128.png"
          alt="placeholder"
        />
      </div>
    </div>
  )
}

export default function GetStartedWithNexus() {
  return (
    <section id="get-started">
      <div className="container">
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
              <div className="tile is-parent is-4" key={getStartedLink.title}>
                <article className="tile is-child box">
                  <GetStartedLink {...getStartedLink} />
                </article>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
