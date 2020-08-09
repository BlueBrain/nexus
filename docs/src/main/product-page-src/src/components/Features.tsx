import * as React from "react"

const ValuePropoisition: React.FC<{
  title: string
  description: string
}> = ({ title, description }) => {
  return (
    <div className="tile is-parent is-4">
      <article className="tile is-child box">
        <div className="value-prop center-flex">
          <div className="figure image is-128x128">
            <img
              src="https://bulma.io/images/placeholders/128x128.png"
              alt="placeholder"
            />
          </div>
          <h4 className="title">{title}</h4>
          <p>{description}</p>
        </div>
      </article>
    </div>
  )
}

const Features: React.FC<{
  title: string
  subtitle: string
  features: { title: string; description: string }[]
}> = ({ title, subtitle, features }) => {
  return (
    <section id="why-nexus">
      <div className="container">
        <div className="content centered">
          <h2>{title}</h2>
          <p className="subtitle">{subtitle}</p>
          <div className="tile is-ancestor wrapping">
            {features.map(feature => (
              <ValuePropoisition {...feature} key={feature.title} />
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}

export default Features
