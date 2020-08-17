import * as React from "react"
import { Link } from "gatsby"
import Fun from "./Fun"

const products = [
  {
    title: "Nexus Fusion",
    slug: "nexus-fusion",
    subtitle: "Enabling Collaborative Data and Knowledge Discovery",
    description:
      "Fusion is our extensible web application. It hosts different apps to accommodate various use cases. It comes by default with Studios (where you work with data), Admin (for managing the Nexus instance), and will soon support Workflows to organise your data activities. It runs on top of the Delta web services, and integrates neatly with our Forge Python framework.",
  },
  {
    title: "Nexus Forge",
    slug: "nexus-forge",
    subtitle: "Building and Using Knowledge Graphs Made Easy",
    description:
      "Knowledge Graphs are often built from heterogeneous data and knowledge (i.e. data models such as ontologies, schemas) coming from different sources and often with different formats (i.e. structured, unstructured). Nexus Forge enables data scientists, data and knowledge engineers to address these challenges by uniquely combining under a consistent and generic Python Framework all necessary components to build and search a Knowledge Graph.",
  },
  {
    title: "Nexus Delta",
    slug: "nexus-delta",
    subtitle: "Managing the Data and Knowledge Graph Lifecycle",
    description:
      "A scalable and secure service to store and leverage all your data, neatly organised in a Knowledge Graph. It offers an API to perform all your data management operations, this way it can easily integrate with your software stack. Its advanced indexing capabilities automatically build views from your metadata.",
  },
]

const ShortProductDescription: React.FC<{
  title: string
  subtitle: string
  description: string
  slug: string
}> = ({ title, slug, subtitle, description }) => {
  const object =
    slug === "nexus-fusion" ? "Ico" : slug === "nexus-forge" ? "Box" : "Pyramid"

  return (
    <div className="columns short-product-description alternating-orientation">
      <div className="column">
        <div className="placeholder">
          <Fun object={object} />
        </div>
      </div>
      <div className="column">
        <h3 className="title">{title}</h3>
        <h4 className="subtitle">{subtitle}</h4>
        <p>{description}</p>
        <Link to={`/products/${slug}`}>
          <button className="button">Read More</button>
        </Link>
      </div>
    </div>
  )
}

export default function WhatIsNexus() {
  return (
    <section id="what">
      <div className="container with-room">
        <div className="content centered" style={{ marginBottom: "4em" }}>
          <h2>What is Blue Brain Nexus?</h2>
          <p className="subtitle">
            Blue Brain Nexus is an ecosystem that allows you to organize and
            better leverage your data through the use of a Knowledge Graph. In
            addition to the products listed here, you’ll find a rich ecosystem
            of libraries and tools.
          </p>
        </div>
        <div className="content">
          {products.map(product => (
            <ShortProductDescription {...product} key={product.title} />
          ))}
        </div>
      </div>
    </section>
  )
}
