import * as THREE from "three"

export type WorldObj = {
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  update: (delta: number) => void
  resize: () => void
}

const World = (canvas: HTMLCanvasElement): WorldObj => {
  const scene = new THREE.Scene()
  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true,
    alpha: true,
  })
  renderer.setSize(canvas.clientWidth, canvas.clientHeight)
  renderer.setPixelRatio(window.devicePixelRatio)
  const camera = new THREE.PerspectiveCamera(
    45,
    canvas.clientWidth / canvas.clientHeight,
    0.01,
    1000
  )
  camera.position.set(3.5, 3.5, 0)

  return {
    scene,
    camera,
    update: (delta: number) => {
      renderer.render(scene, camera)
    },
    resize: () => {
      camera.aspect = canvas.clientWidth / canvas.clientHeight
      camera.updateProjectionMatrix()
      renderer.setSize(canvas.clientWidth, canvas.clientHeight)
    },
  }
}

export default World
