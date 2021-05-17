package ch.epfl.bluebrain.nexus.delta.rdf.instances

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import org.apache.jena.graph.Node

import java.time.Instant

trait TripleInstances {
  // $COVERAGE-OFF$
  implicit def createSubjectFromIriOrBNode(value: IriOrBNode): Node = subject(value)
  implicit def createPredicateFromIri(value: Iri): Node             = predicate(value)
  implicit def createObjectFromString(value: String): Node          = obj(value)
  implicit def createObjectFromInt(value: Int): Node                = obj(value)
  implicit def createObjectFromLong(value: Long): Node              = obj(value)
  implicit def createObjectFromBoolean(value: Boolean): Node        = obj(value)
  implicit def createObjectFromDouble(value: Double): Node          = obj(value)
  implicit def createObjectFromDouble(value: Float): Node           = obj(value)
  implicit def createObjectFromIri(value: IriOrBNode): Node         = obj(value)
  implicit def createObjectFromUri(value: Uri): Node                = obj(value)
  implicit def createObjectFromInstant(value: Instant): Node        = obj(value)

  // $COVERAGE-ON$
}
