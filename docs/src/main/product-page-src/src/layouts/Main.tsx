import * as React from "react"
import Header from "../components/header"
import Footer from "../components/footer"
import SEO from "../components/SEO"

export default function MainLayout({ children }) {
  return (
    <>
      <SEO />
      <Header></Header>
      {children}
      <Footer></Footer>
    </>
  )
}
