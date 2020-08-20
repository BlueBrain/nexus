import * as THREE from "three"

const colors = ["#8de2ff", "#f8ceec", "#FF7F50"]

const size = 2
const linewidth = 2

const geometries = [
  new THREE.BoxBufferGeometry(size / 1.5, size / 1.5, size / 1.5),
  new THREE.ConeBufferGeometry(size / 2, size, 4, 0),
  new THREE.IcosahedronBufferGeometry(1, 0),
]

export const Box = () => {
  const color = colors[0]
  const geometry = geometries[0]
  const material = new THREE.LineBasicMaterial({ color, linewidth })
  const mesh = new THREE.LineSegments(
    new THREE.EdgesGeometry(geometry),
    material
  )
  const object = new THREE.Object3D()
  object.add(mesh)
  return object
}

export const Pyramid = () => {
  const color = colors[1]
  const geometry = geometries[1]
  const material = new THREE.LineBasicMaterial({ color, linewidth })
  const mesh = new THREE.LineSegments(
    new THREE.EdgesGeometry(geometry),
    material
  )
  const object = new THREE.Object3D()
  object.add(mesh)
  return object
}

export const Ico = () => {
  const color = colors[2]
  const geometry = geometries[2]
  const material = new THREE.LineBasicMaterial({ color, linewidth })
  const mesh = new THREE.LineSegments(
    new THREE.EdgesGeometry(geometry),
    material
  )
  const object = new THREE.Object3D()
  object.add(mesh)
  return object
}

export default {
  Ico,
  Box,
  Pyramid,
}
