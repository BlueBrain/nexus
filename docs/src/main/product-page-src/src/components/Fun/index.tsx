import * as React from "react"
import useRaf from "@rooks/use-raf"
import World, { WorldObj } from "./World"
import useResize from "../../hooks/useResize"
import objects from "./objects"
import { isSmall } from "../../libs/browser"

const Fun: React.FC<{ object: string }> = ({ object }) => {
  const canvasRef = React.useRef<HTMLCanvasElement>(null)
  const world = React.useRef<WorldObj>()
  const isSmallEnvironment = isSmall()
  const shouldRun = !isSmallEnvironment
  const [lastTick, setLastTick] = React.useState(0)

  React.useEffect(() => {
    if (isSmallEnvironment) {
      return
    }
    if (canvasRef.current && !world.current) {
      world.current = World(canvasRef.current)
      if (objects[object]) {
        const focusObject = objects[object]()
        focusObject.name = "focusObject"
        world.current.scene.add(focusObject)
        world.current.camera.lookAt(focusObject.position)
      }
    }
  }, [canvasRef.current, world.current, isSmallEnvironment])

  useResize(() => {
    if (world.current) {
      world.current.resize()
    }
  })

  useRaf(() => {
    try {
      const now = Date.now()
      const delta = now - lastTick
      setLastTick(now)
      if (world.current && world.current.scene) {
        const focusObject = world.current.scene.getObjectByName("focusObject")
        if (focusObject) {
          focusObject.rotation.x += 0.01
          focusObject.rotation.y -= 0.01
        }
        world.current.update(delta)
      }
    } catch (error) {
      console.error(error)
    }
  }, shouldRun)

  return <canvas className="fun" ref={canvasRef} />
}

export default Fun
