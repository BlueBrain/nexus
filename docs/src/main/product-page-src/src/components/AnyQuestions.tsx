import * as React from "react"

const getHelpLinks = [
  {
    title: "Explore the FAQ",
    href: "",
  },
  {
    title: "Reach out on Twitter",
    href: "",
  },
  {
    title: "Check the Roadmap",
    href: "",
  },
]

const GetHelpLink: React.FC<{
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

export default function AnyQuestions() {
  return (
    <section id="any-questions">
      <div className="container">
        <div className="content centered">
          <h2>Still have questions?</h2>
          <div className="tile is-ancestor wrapping">
            {getHelpLinks.map(getStartedLink => (
              <div className="tile is-parent is-4" key={getStartedLink.title}>
                <article className="tile is-child box">
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
