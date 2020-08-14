import svgify from "./src/libs/svgify"
import gtm from "./src/libs/gtm"

import "./src/styles/global.css"

export const onClientEntry = () => {
  console.log("We've started!")
  gtm()
}

export const onInitialClientRender = () => {
  svgify()
}
