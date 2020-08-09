import * as React from "react"
import { Link } from "gatsby"
import MainLayout from "../layouts/Main"

export default function NotFound() {
  return (
    <MainLayout>
      <h1 className="title">404</h1>
      <h2 className="subtitle">
        <Link to={"/"}>to home</Link>
      </h2>
    </MainLayout>
  )
}
