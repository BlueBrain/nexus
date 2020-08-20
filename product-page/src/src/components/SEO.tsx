import * as React from "react"
import { Helmet } from "react-helmet"
import { useLocation } from "@reach/router"
import { useStaticQuery, graphql } from "gatsby"

import favicon from "../../static/favicon.ico"
import ogImage from "../../static/img/Nexus-v1.4-slate.png"

export type SEOProps = {
  title?: string
  description?: string
  image?: string
}

const SEO: React.FC<SEOProps> = ({ title, description, image }) => {
  const { pathname } = useLocation()
  const { site } = useStaticQuery(query)

  const {
    defaultTitle,
    defaultDescription,
    siteUrl,
    defaultImage,
    twitterUsername,
  } = site.siteMetadata

  const seo = {
    title: title || defaultTitle,
    description: description || defaultDescription,
    image: `${image || defaultImage}`,
    url: ogImage,
  }

  return (
    <Helmet>
      <title>{seo.title}</title>
      <link rel="icon" type="image/png" href={favicon} sizes="32x32" />
      <meta name="description" content={seo.description} />
      <meta name="image" content={seo.image} />
      <meta property="og:type" content="website" />

      <meta name="viewport" content="width=device-width, initial-scale=1.0" />

      {seo.url && <meta property="og:url" content={seo.url} />}

      {seo.title && <meta property="og:title" content={seo.title} />}
      {seo.image && <meta name="og:image" content={seo.image} />}

      {seo.description && (
        <meta property="og:description" content={seo.description} />
      )}

      {seo.image && <meta property="og:image" content={seo.image} />}

      <meta name="twitter:card" content="summary_large_image" />

      {twitterUsername && (
        <meta name="twitter:creator" content={twitterUsername} />
      )}

      {seo.title && <meta name="twitter:title" content={seo.title} />}

      {seo.description && (
        <meta name="twitter:description" content={seo.description} />
      )}

      {seo.image && <meta name="twitter:image" content={seo.image} />}
    </Helmet>
  )
}

const query = graphql`
  query SEO {
    site {
      siteMetadata {
        defaultTitle: title
        defaultDescription: description
        siteUrl: url
        defaultImage: image
        twitterUsername
      }
    }
  }
`

export default SEO
