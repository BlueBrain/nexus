package ch.epfl.bluebrain.nexus.migration.v1_4.events.kg

final case class Digest(algorithm: String, value: String)
object Digest {
  val empty: Digest = Digest("", "")
}
