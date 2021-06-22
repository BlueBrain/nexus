import * as React from "react"
import Header from "../components/Header"
import Footer from "../components/Footer"
import SEO, { SEOProps } from "../components/SEO"

const MainLayout: React.FC<SEOProps> = ({ children, ...rest }) => {
  return (
    <>
      <SEO {...rest} />
      <Header></Header>
      {children}
      <Footer></Footer>
    </>
  )
}

export default MainLayout
