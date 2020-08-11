import * as React from "react"
import { useLocation } from "@reach/router"
import MainLayout from "../layouts/Main"
import EmailCatch from "../containers/EmailCatch"
import { scrollIntoView } from "../libs/scroll"
import Features from "../components/Features"
import ProductDiagram from "../components/ProductDiagram"

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

  return (
    <MainLayout>
      <section className="hero is-fullheight">
        <div className="gradient"></div>
        <div className="hero-body">
          <div className="container">
            <h1 className="title is-spaced">{name}</h1>
            <h2 className="subtitle">{tagLine}</h2>
            <p className="subtitle">{description}</p>
          </div>
          <div className="columns">
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
              <a href="/docs">
                <button className="button">Docs</button>
              </a>
            </div>
          </div>
        </div>
      </section>
      <section id="overview">
        <div className="container">
          <div className="content centered">
            <h2>{overviewText}</h2>
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
