package ch.epfl.bluebrain.nexus.migration.v1_4

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.Json
import io.circe.optics.JsonOptics._
import io.circe.optics.JsonPath.root
import io.circe.syntax._
import monocle.function.Plated

object SourceSanitizer {

  private val resourceCtxUri: Iri = contexts + "resource.json"
  private val resolverCtxUri: Iri = contexts + "resolver.json"
  private val storageCtxUri: Iri  = contexts + "storage.json"

  private val aliases = Map(
    resourceCtxUri.toString -> None,
    resolverCtxUri.toString -> Some(contexts.resolvers),
    storageCtxUri.toString  -> Some(iri"https://bluebrain.github.io/nexus/contexts/storages.json")
  )

  private val removeVocabAndBaseOn = Set(iri"https://bbp.neuroshapes.org")

  val deltaMetadataFields: Set[String] = Set(
    nxv.authorizationEndpoint,
    nxv.createdAt,
    nxv.createdBy,
    nxv.deprecated,
    nxv.endSessionEndpoint,
    nxv.eventSubject,
    nxv.grantTypes,
    nxv.instant,
    nxv.issuer,
    nxv.label,
    nxv.maxScore,
    nxv.next,
    nxv.organizationLabel,
    nxv.organizationUuid,
    nxv.project,
    nxv.resolverId,
    nxv.resourceId,
    nxv.schemaId,
    nxv.results,
    nxv.rev,
    nxv.revocationEndpoint,
    nxv.score,
    nxv.self,
    nxv.source,
    nxv.tokenEndpoint,
    nxv.total,
    nxv.types,
    nxv.updatedAt,
    nxv.updatedBy,
    nxv.userInfoEndpoint,
    nxv.uuid,
    nxv.path
  ).map(_.prefix) ++ Set("_constrainedBy", "_incoming", "_outgoing")

  def updateContext(id: Iri): Json => Json = root.`@context`.json.modify { x =>
    val modified = x.asString match {
      case Some(s) => aliases.get(s).fold(x)(_.asJson)
      case None    =>
        Plated.transform[Json] { j =>
          j.asString match {
            case Some(n) => aliases.get(n).fold(j)(_.asJson)
            case None    =>
              if (removeVocabAndBaseOn.contains(id))
                j.asObject match {
                  case Some(o) =>
                    val filtered = o.removeAllKeys("@vocab", "@base")
                    if (filtered.isEmpty)
                      Json.Null
                    else
                      filtered.asJson
                  case None    => j
                }
              else
                j
          }
        }(x)
    }
    modified.deepDropNullValues
  }

  private val dropMetadataFields = root.obj.modify(j => deltaMetadataFields.foldLeft(j) { case (c, k) => c.remove(k) })

  def sanitize(id: Iri): Json => Json = updateContext(id).andThen(dropMetadataFields).andThen(_.dropNullValues)

}
