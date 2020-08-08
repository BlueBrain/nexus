import * as React from "react"
import { Link } from "gatsby"

export default function NotFound() {
  return (
    <>
      <h1 className="title">404</h1>
      <h2 className="subtitle">
        <Link to={"/"}>to home</Link>
      </h2>
    </>
  )
}
