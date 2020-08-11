import { isBrowser } from "./browser"

// @ts-ignore
const SVG = isBrowser() ? require("svgjs") : () => {}

/*
 * Draw decorative lines within an HTML Element
 *
 */

const numPaths = 25

const colors = ["#8de2ff", "#f8ceec", "#E7EDF3"]

export type CanvasBounds = {
  canvasWidth: number
  canvasHeight: number
  canvasMiddleX: number
  canvasMiddleY: number
}

function getCanvasBounds(elm: HTMLElement): CanvasBounds {
  return {
    canvasWidth: elm.clientWidth,
    canvasHeight: elm.clientHeight,
    canvasMiddleX: elm.clientWidth / 2,
    canvasMiddleY: elm.clientHeight / 2,
  }
}

function getRandomArbitraryRange(min: number, max: number) {
  return Math.floor(Math.random() * (max - min) + min)
}

function generatePoint(bounds: CanvasBounds) {
  const w = bounds.canvasWidth
  const h = bounds.canvasHeight
  const perimeter = 2 * w + 2 * h
  let point = getRandomArbitraryRange(50, perimeter - 50)
  if (point < h) {
    return [0, point]
  }
  point = point - h
  if (point < w) {
    return [point, h]
  }
  point = point - w
  if (point < h) {
    return [w, point]
  }
  point = point - h
  return [point, 0]
}

function generatePath(draw: SVG.Doc, bounds: CanvasBounds) {
  const selectedColorIndex = Math.floor(Math.random() * colors.length)
  const strokeColor = colors[selectedColorIndex]
  const entryPoint = generatePoint(bounds)
  const exitPoint = generatePoint(bounds)
  draw
    .line(entryPoint[0], entryPoint[1], exitPoint[0], exitPoint[1])
    .stroke({ width: 1, color: strokeColor })
}

function generatePaths(id: string) {
  const elm = document.getElementById(id)
  if (!elm) {
    return
  }
  elm.querySelectorAll("*").forEach(n => n.remove())
  const bounds = getCanvasBounds(elm)
  const draw = SVG(id)
    .size(bounds.canvasWidth, bounds.canvasHeight)
    .addClass("lines")
  for (let i = 0; i <= numPaths; i++) {
    generatePath(draw, bounds)
  }
}

export default (id: string) => {
  const generate = () => {
    if (!isBrowser()) return
    generatePaths(id)
  }
  window.addEventListener("resize", generate)
  generate()
  return () => {
    window.removeEventListener("resize", generate)
  }
}
