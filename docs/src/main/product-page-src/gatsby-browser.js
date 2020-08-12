import svgify from "./src/libs/svgify"
import lines from "./src/libs/lines"

import "./src/styles/global.css"

export const onClientEntry = () => {
  console.log("We've started!")
}

export const onInitialClientRender = () => {
  svgify()
  lines("gradient")
}
