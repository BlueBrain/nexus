import SVG from "svgjs";

const numPaths = 25;

const colors = ["#8de2ff", "#f8ceec", "#E7EDF3"];

function getCanvasBounds(elm) {
  return {
    canvasWidth: elm.clientWidth,
    canvasHeight: elm.clientHeight,
    canvasMiddleX: elm.clientWidth / 2,
    canvasMiddleY: elm.clientHeight / 2
  };
}

function getRandomArbitraryRange(min, max) {
  return Math.floor(Math.random() * (max - min) + min);
}

function generatePoint(bounds) {
  let w = bounds.canvasWidth;
  let h = bounds.canvasHeight;
  let perimeter = 2 * w + 2 * h;
  let point = getRandomArbitraryRange(50, perimeter - 50);
  if (point < h) {
    return [0, point];
  }
  point = point - h;
  if (point < w) {
    return [point, h];
  }
  point = point - w;
  if (point < h) {
    return [w, point];
  }
  point = point - h;
  return [point, 0];
}

function generatePath(draw, bounds) {
  const selectedColorIndex = Math.floor(Math.random() * colors.length);
  const strokeColor = colors[selectedColorIndex];
  const entryPoint = generatePoint(bounds);
  const exitPoint = generatePoint(bounds);
  draw
    .line(entryPoint[0], entryPoint[1], exitPoint[0], exitPoint[1])
    .stroke({ width: 1, color: strokeColor });
}

function generatePaths(id) {
  const elm = document.getElementById(id);
  const bounds = getCanvasBounds(elm);
  const draw = SVG(id).size(bounds.canvasWidth, bounds.canvasHeight);
  for (let i = 0; i <= numPaths; i++) {
    generatePath(draw, bounds);
  }
}

export default id => {
  generatePaths(id);
};
