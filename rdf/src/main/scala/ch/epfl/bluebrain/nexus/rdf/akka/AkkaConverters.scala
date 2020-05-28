package ch.epfl.bluebrain.nexus.rdf.akka

/**
  * Conversions between rdf data types and Akka [[akka.http.scaladsl.model.Uri]].
  */
object AkkaConverters extends FromAkkaConverters with ToAkkaConverters
