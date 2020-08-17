import * as React from "react"
import Header from "../components/Header"
import Footer from "../components/Footer"
import SEO from "../components/SEO"

const MainLayout: React.FC = ({ children }) => {
  return (
    <>
      <SEO />
      <Header></Header>
      {children}
      <Footer></Footer>
    </>
  )
}

export default MainLayout
