export const isBrowser = () =>
  typeof window !== "undefined" && typeof document !== "undefined"

export const isSmall = () => isBrowser() && document.body.clientWidth <= 600
