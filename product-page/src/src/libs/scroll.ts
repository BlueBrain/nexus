import * as React from "react"

export const scrollIntoView = (pathname: string, id: string) => (
  e: React.MouseEvent
) => {
  e.preventDefault()
  const element = document.getElementById(id)
  if (element) {
    element.scrollIntoView({
      behavior: "smooth",
    })
  }
  history.replaceState(null, id, `${pathname}#${id}`)
}
