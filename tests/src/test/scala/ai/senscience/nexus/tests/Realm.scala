package ai.senscience.nexus.tests

final case class Realm(name: String) extends AnyVal

object Realm {

  val internal: Realm = Realm("internal")

}
