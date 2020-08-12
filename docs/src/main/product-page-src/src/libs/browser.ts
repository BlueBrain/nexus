export const isBrowser = () =>
  typeof window !== "undefined" && typeof document !== "undefined"

export const isSmall = () => document.body.clientWidth <= 600
