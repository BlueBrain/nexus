/*
 * Replace all SVG images (with .svg class) with inline SVG for styling purposes
 */
export default () => {
  document.querySelectorAll<HTMLImageElement>("img").forEach(elm => {
    if (
      !elm.src ||
      !elm.src.includes("data:image/svg+xml") ||
      !elm.classList.contains("svgify")
    ) {
      return
    }
    fetch(elm.src)
      .then(response => response.text())
      .then(data => {
        const parser = new DOMParser()
        const htmlDoc = parser.parseFromString(data, "text/html")
        const svg = htmlDoc.querySelector("svg")
        svg.classList.add("replaced-svg")
        elm.classList.forEach(className => svg.classList.add(className))
        if (elm.id) {
          svg.id = elm.id
        }

        // Remove any invalid XML tags as per http://validator.w3.org
        svg.removeAttribute("xmlns:a")

        // Check if the viewport is set, if the viewport is not set the SVG wont't scale.
        if (
          !svg.getAttribute("viewBox") &&
          svg.getAttribute("height") &&
          svg.getAttribute("width")
        ) {
          svg.setAttribute(
            "viewBox",
            "0 0 " +
              svg.getAttribute("height") +
              " " +
              svg.getAttribute("width")
          )
        }

        elm.parentNode.replaceChild(svg, elm)
      })
      .catch(error => console.error(error))
  })
}
