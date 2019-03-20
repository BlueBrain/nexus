import paper from 'paper';

const numPaths = 25;

/* globals view*/
/* globals Path*/
/* globals Point*/
/* globals window*/

const colors = [
  '#8de2ff',
  '#f8ceec',
  '#E7EDF3'
];

function getCanvasBounds() {
  return {
    canvasWidth: view.size.width,
    canvasHeight:  view.size.height,
    canvasMiddleX:  view.size.width / 2,
    canvasMiddleY:  view.size.height / 2,
  }
}

function getRandomArbitraryRange(min, max) {
  return Math.floor(Math.random() * (max - min) + min);
}

function generatePoint(bounds) {
  let w = bounds.canvasWidth;
  let h = bounds.canvasHeight;
  let perimeter = (2*w) + (2*h);
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

function generatePath (bounds) {
  var myPath = new Path();
  let selectedColorIndex = Math.floor(Math.random() * colors.length);
  myPath.strokeColor = colors[selectedColorIndex];
  let entryPoint = new Point(...generatePoint(bounds));
  let exitPoint = new Point(...generatePoint(bounds));
  myPath.add(entryPoint);
  myPath.add(exitPoint);
  return myPath;
}

function generatePaths () {
  let bounds = getCanvasBounds();
  for (let i = 0; i <= numPaths; i++) {
    generatePath(bounds);
  }
}

function init (selector) {
  paper.install(window);
  document.querySelectorAll(selector).forEach(elm => {
    paper.setup(elm);
    generatePaths();
  })
}

export default selector => {
  init(selector);
};
