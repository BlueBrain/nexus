import * as React from "react"
import { Link } from "gatsby"

export default function Home() {
  return (
    <div>
      <nav>
        <Link to="/products/forge">Forge</Link>
        <Link to="/products/fusion">Fusion</Link>
        <Link to="/products/delta">Delta</Link>
      </nav>
      Hello world!
    </div>
  )
}
