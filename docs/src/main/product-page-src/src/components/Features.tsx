import * as React from "react"

import getIcon from "./featureIcon"
import SVG from "./SVG"

const ValuePropoisition: React.FC<{
  title: string
  description: string
  image?: string
}> = ({ title, description, image }) => {
  return (
    <div className="tile is-parent is-4">
      <article className="tile is-child box">
        <div className="value-prop center-flex">
          <div className="figure image is-64x64">
            {image ? (
              <SVG
                src={image}
                alt={title}
                className="svgify secondary subtle"
              />
            ) : (
              <SVG
                src={getIcon(title)}
                alt="placeholder"
                className="svgify secondary subtle"
              />
            )}
          </div>
          <h4 className="title" style={{ marginTop: "1em" }}>
            {title}
          </h4>
          <p>{description}</p>
        </div>
      </article>
    </div>
  )
}

const Features: React.FC<{
  id?: string
  title: string
  subtitle: string
  features: { title: string; description: string; image?: string }[]
}> = ({ id = "features", title, subtitle, features }) => {
  return (
    <section id={id}>
      <div className="container with-room">
        <div className="content centered">
          <h2>{title}</h2>
          <p className="subtitle">{subtitle}</p>
          <div className="tile is-ancestor wrapping centered">
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
