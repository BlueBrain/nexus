/*
* Replace all SVG images (with .svg class) with inline SVG for styling purposes
*/
export default () => {
  document.querySelectorAll('img.svg').forEach(elm => {
    fetch(elm.src)
      .then(response => response.text())
      .then(data => {
        let parser = new DOMParser();
        let htmlDoc = parser.parseFromString(data, 'text/html');
        let svg = htmlDoc.querySelector('svg');
        svg.classList = elm.classList + ' replaced-svg';
        svg.id = elm.id;

        // Remove any invalid XML tags as per http://validator.w3.org
        svg.removeAttribute('xmlns:a');

        // Check if the viewport is set, if the viewport is not set the SVG wont't scale.
        if(!svg.getAttribute('viewBox') && svg.getAttribute('height') && svg.getAttribute('width')) {
            svg.setAttribute('viewBox', '0 0 ' + svg.getAttribute('height') + ' ' + svg.getAttribute('width'));
        }

        elm.parentNode.replaceChild(svg, elm);
      })
      .catch(error => console.error(error));
  });
};