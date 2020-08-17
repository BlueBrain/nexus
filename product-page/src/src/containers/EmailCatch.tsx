import * as React from "react"
import { useStaticQuery, graphql } from "gatsby"
import NewsletterCallout, {
  EMAIL_CATCH_STATUS,
} from "../components/NewsletterCallout"

const EmailCatch: React.FC<{}> = () => {
  const { site } = useStaticQuery(query)
  const [status, setStatus] = React.useState<EMAIL_CATCH_STATUS>(
    EMAIL_CATCH_STATUS.STANDBY
  )

  const { emailCatchAPI } = site.siteMetadata

  const submitEmail = (data: { email?: string }) => {
    setStatus(EMAIL_CATCH_STATUS.PENDING)
    return fetch(`${emailCatchAPI}?email=${data.email}`)
      .then(() => setStatus(EMAIL_CATCH_STATUS.SUCCESS))
      .catch(() => setStatus(EMAIL_CATCH_STATUS.ERROR))
  }

  return <NewsletterCallout submitEmail={submitEmail} status={status} />
}

const query = graphql`
  query emailCatch {
    site {
      siteMetadata {
        emailCatchAPI
      }
    }
  }
`

export default EmailCatch
