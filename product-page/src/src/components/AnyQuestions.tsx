import * as React from "react"

const getHelpLinks = [
  {
    title: "Explore the FAQ",
    href: "https://bluebrainnexus.io/docs/faq",
  },
  {
    title: "Reach out on Twitter",
    href: "https://twitter.com/bluebrainnexus",
    newTab: true,
  },
  {
    title: "Check the Roadmap",
    href: "https://bluebrainnexus.io/docs/roadmap",
  },
]

const GetHelpLink: React.FC<{
  title: string
  href: string
  newTab?: boolean
}> = ({ title, href, newTab }) => {
  return (
    <div className="get-started center-flex">
      <a
        href={href}
        className="get-help-link button"
        target={newTab ? "_blank" : ""}
      >
        {title}
      </a>
    </div>
  )
}

export default function AnyQuestions() {
  return (
    <section id="any-questions">
      <div className="container with-room">
        <div className="content centered">
          <h2 className="section-title">Looking for more?</h2>
          <div className="tile is-ancestor wrapping">
            {getHelpLinks.map(getStartedLink => (
              <div className="tile is-parent is-4" key={getStartedLink.title}>
                <article className="tile is-child">
                  <GetHelpLink {...getStartedLink} />
                </article>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
