package ch.epfl.bluebrain.nexus.migration.v1_4

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import io.circe.optics.JsonOptics._
import monocle.function.Plated
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax._

object Contexts {

  private val resourceCtxUri: Iri = contexts + "resource.json"
  private val resolverCtxUri: Iri = contexts + "resolver.json"

  private val aliases = Map(
    resourceCtxUri.toString -> contexts.metadata,
    resolverCtxUri.toString -> contexts.resolvers
  )

  val updateContext: Json => Json = root.`@context`.json.modify { x =>
    x.asString match {
      case Some(s) => aliases.get(s).fold(x)(_.asJson)
      case None    =>
        Plated.transform[Json] { j =>
          j.asString match {
            case Some(n) => aliases.get(n).fold(j)(_.asJson)
            case None    => j
          }
        }(x)
    }

  }

}
