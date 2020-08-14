import gtm from "./src/libs/gtm"

import "./src/styles/global.css"

const sayHello = () => {
  const styleHeader = [
    "color: #050A56;",
    "font-family: sans-serif;",
    "font-weight: lighter;",
    "font-size: 2em;",
  ].join(" ")
  const styleText = [
    "color: #0083CB;",
    "font-family: sans-serif;",
    "font-weight: bold;",
    "font-size: 1em;",
  ].join(" ")
  console.log("%cBlue Brain Project", styleHeader)
  console.log("%cNeuroinformatics Frontend Team", styleText)
}

export const onClientEntry = () => {
  sayHello()
  gtm()
}
