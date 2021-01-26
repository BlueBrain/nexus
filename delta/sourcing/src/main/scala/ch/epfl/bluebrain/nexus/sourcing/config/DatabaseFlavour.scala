package ch.epfl.bluebrain.nexus.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

/**
  * The enumeration of supported database flavours.
  */
sealed trait DatabaseFlavour extends Product with Serializable

object DatabaseFlavour {

  /**
    * The Postgres database.
    */
  final case object Postgres extends DatabaseFlavour

  /**
    * The Cassandra database.
    */
  final case object Cassandra extends DatabaseFlavour

  implicit final val flavourReader: ConfigReader[DatabaseFlavour] =
    deriveEnumerationReader

}
