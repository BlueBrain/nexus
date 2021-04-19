package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, defaultElasticsearchSettings, ElasticSearchViewRejection, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceResolvingDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}

/**
  * Decoder for [[ElasticSearchViewValue]] which maps some fields to string, before decoding to get around lack of support
  * for @json in json ld library.
  */
//TODO remove when support for @json is added in json-ld library
class ElasticSearchViewJsonLdSourceDecoder private (
    decoder: JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewValue]
) {

  def apply(project: Project, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, (Iri, ElasticSearchViewValue)] =
    applyDefaultSettings(source).flatMap { s => decoder(project, mapJsonToString(s)) }

  def apply(project: Project, iri: Iri, source: Json)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, ElasticSearchViewValue] =
    applyDefaultSettings(source).flatMap { s =>
      decoder(
        project,
        iri,
        mapJsonToString(s)
      )
    }

  /**
    * Apply default settings if nothing has been provided
    */
  private def applyDefaultSettings(source: Json): UIO[Json] =
    source.asObject match {
      case Some(o) if !o.contains("settings") =>
        defaultElasticsearchSettings.map { settings =>
          o.add("settings", settings.asJson).asJson
        }
      case _                                  => UIO.pure(source)
    }

  private def mapJsonToString(json: Json): Json = json
    .mapAllKeys("mapping", _.noSpaces.asJson)
    .mapAllKeys("settings", _.noSpaces.asJson)
}

object ElasticSearchViewJsonLdSourceDecoder {

  def apply(uuidF: UUIDF, contextResolution: ResolverContextResolution) = new ElasticSearchViewJsonLdSourceDecoder(
    new JsonLdSourceResolvingDecoder[ElasticSearchViewRejection, ElasticSearchViewValue](
      contexts.elasticsearch,
      contextResolution,
      uuidF
    )
  )
}
