package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Json}

/**
  * Gives all delta related namespaces in Blazegraph
  */
final case class DeltaNamespaceSet(value: Set[String])

object DeltaNamespaceSet {

  private val namespacePredicate = iri"http://www.bigdata.com/rdf#/features/KB/Namespace"

  private val defaultNameSpace = "kb"

  implicit val deltaNamespacesDecoder: Decoder[DeltaNamespaceSet] = Decoder.instance { hc =>
    hc.downField("results").get[List[Json]]("bindings").map { bindings =>
      val namespaces = bindings.mapFilter { b =>
        for {
          _ <- b.hcursor.downField("predicate").get[Iri]("value").toOption.filter(_ == namespacePredicate)
          value <- b.hcursor.downField("object").get[String]("value").toOption.filterNot(_ == defaultNameSpace)
        } yield value
      }
      DeltaNamespaceSet(namespaces.toSet)
    }
  }
}
