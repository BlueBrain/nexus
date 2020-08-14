import * as React from "react"
import svgify from "../libs/svgify"

const SVG: React.FC<{ src: string; alt: string; className: string }> = ({
  src,
  alt,
  className,
}) => {
  const ref = React.useRef<HTMLImageElement>(null)

  React.useEffect(() => {
    if (ref.current) {
      svgify(ref.current)
    }
  }, [ref.current])

  return <img src={src} alt={alt} className={className} ref={ref} />
}

export default SVG
