package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchFiles, ElasticSearchViewEvent, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ValidateElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship.views.ElasticSearchViewProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus}
import io.circe.Decoder

import java.util.UUID

class ElasticSearchViewProcessor private (views: ElasticSearchViews, clock: EventClock)
    extends EventProcessor[ElasticSearchViewEvent] {

  override def resourceType: EntityType = ElasticSearchViews.entityType

  override def decoder: Decoder[ElasticSearchViewEvent] = ElasticSearchViewEvent.serializer.codec

  override def evaluate(event: ElasticSearchViewEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: ElasticSearchViewEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    val cRev                = event.rev - 1
    event match {
      case e: ElasticSearchViewCreated      => views.create(e.id, e.project, e.value)
      case e: ElasticSearchViewUpdated      => views.update(e.id, e.project, cRev, e.value)
      case e: ElasticSearchViewDeprecated   => views.deprecate(e.id, e.project, cRev)
      case e: ElasticSearchViewUndeprecated => views.undeprecate(e.id, e.project, cRev)
      case _: ElasticSearchViewTagAdded     => IO.unit // TODO: Check if this is correct
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object ElasticSearchViewProcessor {

  private val logger = Logger[ElasticSearchViewProcessor]

  def apply(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): IO[ElasticSearchViewProcessor] = {
    implicit val uuidF: UUIDF                    = UUIDF.random // TODO: Use correct UUID?
    implicit val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

    val noValidation = new ValidateElasticSearchView {
      override def apply(uuid: UUID, indexingRev: IndexingRev, v: ElasticSearchViewValue): IO[Unit] = IO.unit
    }
    val prefix       = "wrong_prefix" // TODO: fix prefix
    val files        = ElasticSearchFiles.mk(loader)

    for {
      f     <- files
      views <- ElasticSearchViews(
                 fetchContext,
                 rcr,
                 noValidation,
                 config,
                 prefix,
                 xas,
                 f.defaultMapping,
                 f.defaultSettings,
                 clock
               )
    } yield new ElasticSearchViewProcessor(views, clock)
  }

}
