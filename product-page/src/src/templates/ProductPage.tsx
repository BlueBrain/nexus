import * as React from "react"
import { useLocation } from "@reach/router"
import MainLayout from "../layouts/Main"
import EmailCatch from "../containers/EmailCatch"
import { scrollIntoView } from "../libs/scroll"
import Features from "../components/Features"
import ProductDiagram from "../components/ProductDiagram"
import Fun from "../components/Fun"
import { isSmall } from "../libs/browser"

export type Product = {
  name: string
  slug: string
  ogTitle: string
  ogDescription: string
  features: { title: string; description: string }[]
  overviewText: string
  overviewItems?: string[]
  docsLink?: string
  featureText: string
  tagLine: string
  description: string
  additionalInfo?: string
}

const ProductPage: React.FC<{ pageContext: { product: Product } }> = ({
  pageContext: { product },
}) => {
  const {
    name,
    slug,
    ogTitle,
    ogDescription,
    tagLine,
    description,
    overviewText,
    overviewItems,
    featureText,
    features,
    additionalInfo,
    docsLink: doc,
  } = product

  const { pathname } = useLocation()

  const object =
    slug === "nexus-fusion" ? "Ico" : slug === "nexus-forge" ? "Box" : "Pyramid"

  const docsLink = doc
    ? doc
    : `https://bluebrainnexus.io/docs/${slug.replace("nexus-", "")}`

  const hasOverviewItems = !!overviewItems && overviewItems.length

  return (
    <MainLayout {...{ title: ogTitle, description: ogDescription }}>
      <section className="hero is-fullheight">
        <div className="full-height">
          <div className="gradient subtraction" />
          {!isSmall() && <Fun object={object} />}
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
            <div className="columns">
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
      <Features title="Features" subtitle={featureText} features={features} />
      <EmailCatch />
      <section id="overview">
        <div className="container">
          <div className={`content ${!hasOverviewItems && "centered"}`}>
            <h2 className="title text-centered">
              {name.replace("Nexus ", "")} inside the Nexus ecosystem
            </h2>
            {hasOverviewItems ? (
              <div className="columns">
                <div className="column">
                  <p>{overviewText}</p>
                  <ul className="list">
                    {overviewItems?.map((item, index) => (
                      <li key={`list-elm-${index}`}>
                        <p>{item}</p>
                      </li>
                    ))}
                  </ul>
                </div>
                <div className="column">
                  <ProductDiagram name={slug} />
                </div>
              </div>
            ) : (
              <>
                <p>{overviewText}</p>
                <ProductDiagram name={slug} />
              </>
            )}

            <p>{additionalInfo}</p>
          </div>
        </div>
      </section>
    </MainLayout>
  )
}

export default ProductPage
