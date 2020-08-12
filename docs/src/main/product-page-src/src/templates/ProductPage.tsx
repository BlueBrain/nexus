import * as React from "react"
import { useLocation } from "@reach/router"
import MainLayout from "../layouts/Main"
import EmailCatch from "../containers/EmailCatch"
import { scrollIntoView } from "../libs/scroll"
import Features from "../components/Features"
import ProductDiagram from "../components/ProductDiagram"
import Fun from "../components/Fun"
import svgify from "../libs/svgify"

export type Product = {
  name: string
  slug: string
  features: { title: string; description: string }[]
  overviewText: string
  featureText: string
  tagLine: string
  description: string
}

const ProductPage: React.FC<{ pageContext: { product: Product } }> = ({
  pageContext: { product },
}) => {
  const {
    name,
    slug,
    tagLine,
    description,
    overviewText,
    featureText,
    features,
  } = product

  const { pathname } = useLocation()

  React.useEffect(() => {
    svgify()
  }, [])

  const object =
    slug === "nexus-fusion" ? "Ico" : slug === "nexus-forge" ? "Box" : "Pyramid"

  const docsLink = `/docs/${slug.replace("nexus-", "")}`

  return (
    <MainLayout>
      <section className="hero is-fullheight">
        <div className="full-height">
          <div className="gradient subtraction" />
          <Fun object={object} />
        </div>
        <div className="hero-body">
          <div
            className="container"
            style={{ position: "relative", top: "-2em" }}
          >
            <h1 className="title is-spaced">{name}</h1>
            <h2 className="subtitle">
              <em>{tagLine}</em>
            </h2>
            <p className="subtitle">{description}</p>
            <div className="columns" style={{ width: "50%" }}>
              <div className="column">
                <a
                  href="#overview"
                  onClick={scrollIntoView(pathname, "overview")}
                >
                  <button className="button">Overview</button>
                </a>
              </div>
              <div className="column">
                <a
                  href="#features"
                  onClick={scrollIntoView(pathname, "features")}
                >
                  <button className="button">Features</button>
                </a>
              </div>
              <div className="column">
                <a href={docsLink}>
                  <button className="button">Docs</button>
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>
      <section id="overview">
        <div className="container">
          <div className="content centered">
            <h2 className="title">What is {name}?</h2>
            <p>{overviewText}</p>
            <ProductDiagram name={slug} />
          </div>
        </div>
      </section>
      <EmailCatch />
      <Features title="Features" subtitle={featureText} features={features} />
    </MainLayout>
  )
}

export default ProductPage
