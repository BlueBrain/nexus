package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Metadata, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import monix.bio.IO

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * Representation of a composite view.
  *
  * @param id
  *   the id of the project
  * @param project
  *   the project to which this view belongs
  * @param sources
  *   the collection of sources for the view
  * @param projections
  *   the collection of projections for the view
  * @param rebuildStrategy
  *   the rebuild strategy of the view
  * @param uuid
  *   the uuid of the view
  * @param tags
  *   the tag -> rev mapping
  * @param source
  *   the original json document provided at creation or update
  * @param updatedAt
  *   the instant when the view was last updated
  */
final case class CompositeView(
    id: Iri,
    project: ProjectRef,
    sources: NonEmptySet[CompositeViewSource],
    projections: NonEmptySet[CompositeViewProjection],
    rebuildStrategy: Option[RebuildStrategy],
    uuid: UUID,
    tags: Map[UserTag, Long],
    source: Json,
    updatedAt: Instant
) {

  /**
    * @return
    *   [[CompositeView]] metadata
    */
  def metadata: Metadata = Metadata(uuid)
}

object CompositeView {

  /**
    * The rebuild strategy for a [[CompositeView]].
    */
  sealed trait RebuildStrategy extends Product with Serializable

  /**
    * Rebuild strategy defining rebuilding at a certain interval.
    */
  final case class Interval private[model] (value: FiniteDuration) extends RebuildStrategy
  object Interval {
    def apply(value: FiniteDuration, config: CompositeViewsConfig): Option[Interval] =
      Option.when(value gteq config.minIntervalRebuild)(new Interval(value))
  }

  final case class Metadata(uuid: UUID)

  object RebuildStrategy {
    @nowarn("cat=unused")
    implicit final val rebuildStrategyEncoder: Encoder.AsObject[RebuildStrategy] = {
      implicit val config: Configuration                          = Configuration.default.withDiscriminator(keywords.tpe)
      implicit val finiteDurationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString())
      deriveConfiguredEncoder[RebuildStrategy]
    }
  }

  @nowarn("cat=unused")
  implicit private def compositeViewEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeView] = {
    implicit val config: Configuration                    = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val encoderTags: Encoder[Map[UserTag, Long]] = Encoder.instance(_ => Json.Null)
    Encoder.encodeJsonObject.contramapObject { v =>
      deriveConfiguredEncoder[CompositeView]
        .encodeObject(v)
        .add(keywords.tpe, Set(nxv + "View", compositeViewType).asJson)
        .remove("tags")
        .remove("project")
        .remove("source")
        .remove("id")
        .remove("updatedAt")
        .mapAllKeys("context", _.noSpaces.asJson)
        .mapAllKeys("mapping", _.noSpaces.asJson)
        .mapAllKeys("settings", _.noSpaces.asJson)
        .addContext(v.source.topContextValueOrEmpty.excludeRemoteContexts.contextObj)
    }
  }

  implicit def compositeViewJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[CompositeView] = {
    val underlying: JsonLdEncoder[CompositeView] =
      JsonLdEncoder.computeFromCirce(_.id, ContextValue(contexts.compositeViews))
    new JsonLdEncoder[CompositeView] {

      private def parseJson(jsonString: Json) = jsonString.asString.fold(jsonString)(parse(_).getOrElse(jsonString))

      private def stringToJson(obj: JsonObject) =
        obj.mapAllKeys("mapping", parseJson).mapAllKeys("settings", parseJson).mapAllKeys("context", parseJson)

      override def context(value: CompositeView): ContextValue = underlying.context(value)

      override def expand(
          value: CompositeView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        underlying.expand(value)

      override def compact(
          value: CompositeView
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        underlying.compact(value).map(c => c.copy(obj = stringToJson(c.obj)))
    }
  }

  implicit private val compositeViewMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject("_uuid" -> meta.uuid.asJson))

  implicit val compositeViewMetadataJsonLdEncoder: JsonLdEncoder[Metadata]      =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.compositeViewsMetadata))
}
