import * as React from "react"

const useResize = (cb: VoidFunction) => {
  React.useEffect(() => {
    window.addEventListener("resize", cb)
    return () => {
      window.removeEventListener("resize", cb)
    }
  })
}

export default useResize
