import svgify from "./src/libs/svgify"

import "./src/styles/global.css"

export const onClientEntry = () => {
  console.log("We've started!")
}

export const onInitialClientRender = () => {
  svgify()
}
